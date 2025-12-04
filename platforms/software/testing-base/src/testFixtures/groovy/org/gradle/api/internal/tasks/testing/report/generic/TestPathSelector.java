/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing.report.generic;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import org.gradle.api.Describable;
import org.gradle.util.Path;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A "selector" for test paths, which can match against multiple {@link Path}s.
 *
 * <p>
 * No relative paths are allowed, to simplify matching logic.
 * </p>
 *
 * <p>
 * TLDR for the special syntax: if you see a `^Foo`, then a test expects a `TEST-Foo.xml`.
 * If you see `:Foo:bar:_`, then it won't check anything in the JUnit XML.
 * If you see `^_`, then the actual class segment is not present, and it won't check anything in the JUnit XML.
 * </p>
 *
 * <p>
 * In addition to the normal path syntax, the following special syntax is supported:
 * <ul>
 *     <li>Special characters ({@code :~_^%}) can be escaped with a percent sign.
 *         Backslash was avoided to prevent extra escaping in regex segments.
 *     <li>A segment may start with {@code ~} to indicate a regex match. For example, {@code :com:~.*Test} will match
 *         {@code :com:MyTest} and {@code :com:YourTest}, but not {@code :com:TestCase}.
 *     <li>A segment may start with {@code ^} to mark it as the class segment for JUnit XML matching.
 *         For example, {@code :org.ASuite:^org.AClass:foo} indicates that {@code org.AClass} should be checked
 *         instead of the first segment. Only one class marker is allowed per path.
 *         The {@code ^} prefix may be combined with {@code ~} (e.g., {@code ^~pattern}), with {@code ^} first.
 *     <li>The last segment may be {@code _} to indicate that there are further "leaf" nodes below the specified path.
 *         It will not match those leaf nodes themselves, but this is necessary to allow interoperability between
 *         selecting tests from HTML and JUnit XML reports with the same code, as JUnit XML reports lack intermediate nodes.
 *         For example, {@code :com:example:_} will match {@code :com:example}, but not {@code :com:example:MyTest}.
 *         But it will not fail if an XML report does not contain a node for {@code :com:example}, but only {@code :com} and {@code :com:MyTest}.
 * </ul>
 *
 * @param segments the segments of the selector, in order. Note that the leaf marker segment ({@code _}) is not included in this list, but is instead represented by the {@code furtherLeafNodes} flag
 * @param furtherLeafNodes whether this selector matches further leaf nodes
 * @param classMarkerIndex the 0-based index of the segment marked as the class, or {@code null} if no class marker is present
 */
public record TestPathSelector(ImmutableList<Segment> segments, boolean furtherLeafNodes, @Nullable Integer classMarkerIndex) {
    public static TestPathSelector from(String path) {
        boolean absolute = path.startsWith(":");
        if (!absolute) {
            throw new IllegalArgumentException("Only absolute paths are supported, got '" + path + "'");
        }
        if (path.equals(":")) {
            return new TestPathSelector(ImmutableList.of(), false, null);
        }
        if (path.equals(":_")) {
            return new TestPathSelector(ImmutableList.of(), true, null);
        }
        ImmutableList.Builder<Segment> segments = ImmutableList.builder();
        boolean furtherLeafNodes = false;
        Integer classMarkerIndex = null;
        int segmentCount = 0;
        for (int index = 1; index <= path.length(); index++) {
            if (index == path.length()) {
                if (path.charAt(index - 1) == ':') {
                    segments.add(new Segment.Literal(""));
                }
                break;
            }
            if (path.charAt(index) == '_') {
                if (index + 1 == path.length()) {
                    furtherLeafNodes = true;
                    break;
                }
                throw new IllegalArgumentException("The '_' segment must be the last segment in the path: " + path);
            }
            boolean classMarker = false;
            if (path.charAt(index) == '^') {
                if (classMarkerIndex != null) {
                    throw new IllegalArgumentException("Only one class marker ('^') is allowed per path: " + path);
                }
                classMarker = true;
                index++;
            }
            Function<String, Segment> segmentConstructor;
            if (index < path.length() && path.charAt(index) == '~') {
                segmentConstructor = Segment.Regex::new;
                index++;
            } else {
                segmentConstructor = Segment.Literal::new;
            }
            if (classMarker && index < path.length() && path.charAt(index) == '_' && index + 1 == path.length()) {
                classMarkerIndex = segmentCount;
                furtherLeafNodes = true;
                break;
            }
            index = parseSegment(path, index, segments, segmentConstructor);
            if (classMarker) {
                classMarkerIndex = segmentCount;
            }
            segmentCount++;
        }
        return new TestPathSelector(segments.build(), furtherLeafNodes, classMarkerIndex);
    }

    private static int parseSegment(String path, int index, ImmutableList.Builder<Segment> segments, Function<String, Segment> segmentConstructor) {
        StringBuilder segment = new StringBuilder();
        boolean escape = false;
        for (; index < path.length(); index++) {
            char c = path.charAt(index);
            if (escape) {
                if (!isSpecialChar(c)) {
                    throw new IllegalArgumentException("Invalid escape sequence: %" + c);
                }
                segment.append(c);
                escape = false;
            } else if (c == '%') {
                escape = true;
            } else if (c == ':') {
                break;
            } else {
                segment.append(c);
            }
        }
        if (escape) {
            throw new IllegalArgumentException("Dangling escape at end of path: " + path);
        }
        segments.add(segmentConstructor.apply(segment.toString()));
        return index;
    }

    private static boolean isSpecialChar(char c) {
        return switch (c) {
            case ':', '~', '_', '%', '^' -> true;
            default -> false;
        };
    }

    private static String escapeSegment(String segment) {
        StringBuilder escaped = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (isSpecialChar(c)) {
                escaped.append('%');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    public sealed interface MatchResult extends Describable {
        enum Matched implements MatchResult {
            INSTANCE;

            @Override
            public String getDisplayName() {
                return "matched";
            }
        }

        enum NotAbsolute implements MatchResult {
            INSTANCE;

            @Override
            public String getDisplayName() {
                return "path is not absolute";
            }
        }

        record SegmentCountMismatch(int expected, int actual) implements MatchResult {
            @Override
            public String getDisplayName() {
                return String.format("segment count mismatch (expected: %d, actual: %d)", expected, actual);
            }
        }

        record SegmentMismatch(int segmentIndex, String expectedPhrase, String actual) implements MatchResult {
            @Override
            public String getDisplayName() {
                return "segment " + segmentIndex + " mismatch (expected " + expectedPhrase + ", got '" + actual + "')";
            }
        }

        default boolean isMatch() {
            return this == Matched.INSTANCE;
        }
    }

    public sealed interface Segment {
        record Literal(String value) implements Segment {
            @Override
            public boolean matches(String segment) {
                return value.equals(segment);
            }

            @Override
            public String describe() {
                return "'" + value + "'";
            }

            @Override
            public String toString() {
                return escapeSegment(value);
            }
        }

        record Regex(Pattern pattern) implements Segment {
            Regex(String pattern) {
                this(Pattern.compile(pattern));
            }

            @Override
            public boolean matches(String segment) {
                return pattern.matcher(segment).matches();
            }

            @Override
            public String describe() {
                return "regex /" + pattern.pattern() + "/";
            }

            @Override
            public String toString() {
                return "~" + escapeSegment(pattern.pattern());
            }

            // Have to override equals and hashCode as Pattern doesn't implement them.
            // We assume equality based on the pattern string and flags.

            @Override
            public boolean equals(Object o) {
                return o instanceof Regex regex
                    && pattern.pattern().equals(regex.pattern.pattern())
                    && pattern.flags() == regex.pattern.flags();
            }

            @Override
            public int hashCode() {
                return Objects.hash(pattern.pattern(), pattern.flags());
            }
        }

        boolean matches(String segment);

        String describe();
    }

    /**
     * Determines whether the given path matches this selector.
     *
     * <p>
     * A path matches a selector if:
     * <ul>
     *     <li>The path is absolute.
     *     <li>Either:
     *     <ul>
     *     <li>The segment counts match, and each segment in the selector matches the corresponding segment in the path
     *     <li>The last segment in the selector is a leaf marker ({@code _}), the path has one less segment than the selector, and the preceding segments match.
     *     </ul>
     * </ul>
     *
     * @param path the path to check
     * @return {@link MatchResult.Matched} if the path matches this selector; a descriptive {@link MatchResult} otherwise
     */
    public MatchResult matches(Path path) {
        if (!path.isAbsolute()) {
            return MatchResult.NotAbsolute.INSTANCE;
        }
        List<String> pathSegments = path.segments();
        if (segments.isEmpty()) {
            return pathSegments.isEmpty()
                ? MatchResult.Matched.INSTANCE
                : new MatchResult.SegmentCountMismatch(0, pathSegments.size());
        }
        List<Segment> selectorSegments = segments;
        if (pathSegments.size() != selectorSegments.size()) {
            return new MatchResult.SegmentCountMismatch(selectorSegments.size(), pathSegments.size());
        }
        for (int i = 0; i < selectorSegments.size(); i++) {
            Segment selectorSegment = selectorSegments.get(i);
            String pathSegment = pathSegments.get(i);
            if (!selectorSegment.matches(pathSegment)) {
                return new MatchResult.SegmentMismatch(i, selectorSegment.describe(), pathSegment);
            }
        }
        return MatchResult.Matched.INSTANCE;
    }

    /**
     * Returns an iterable over all ancestor selectors of this selector, starting from the immediate parent
     * up to the root selector.
     *
     * <p>
     * For leaf-marker selectors, the leaf marker is propagated to intermediate ancestors.
     * For example, {@code :a:b:_} produces {@code :a:_}, {@code :_}.
     * </p>
     *
     * @return an iterable over all ancestor selectors of this selector
     */
    public Iterable<TestPathSelector> ancestors() {
        return () -> new AbstractIterator<>() {
            private int currentSize = segments.size();

            @Nullable
            @Override
            protected TestPathSelector computeNext() {
                if (currentSize == 0) {
                    return endOfData();
                }
                currentSize--;
                // Ancestors always get furtherLeafNodes = true, as there is always this selector which was
                // either a leaf or has further leaf nodes.
                Integer ancestorClassMarkerIndex = computeAncestorClassMarkerIndex();
                return new TestPathSelector(segments.subList(0, currentSize), true, ancestorClassMarkerIndex);
            }

            @Nullable
            private Integer computeAncestorClassMarkerIndex() {
                Integer ancestorClassMarkerIndex;
                if (classMarkerIndex == null) {
                    ancestorClassMarkerIndex = null;
                } else if (classMarkerIndex < segments.size()) {
                    if (classMarkerIndex == segments.size() - 1 && currentSize == classMarkerIndex) {
                        // The class marker was on the segment being trimmed; move it to the leaf position
                        ancestorClassMarkerIndex = currentSize;
                    } else if (classMarkerIndex <= currentSize) {
                        ancestorClassMarkerIndex = classMarkerIndex;
                    } else {
                        ancestorClassMarkerIndex = currentSize;
                    }
                } else {
                    // Class marker was on the leaf (from ^_), clamp to new size
                    ancestorClassMarkerIndex = Math.min(classMarkerIndex, currentSize);
                }
                return ancestorClassMarkerIndex;
            }
        };
    }

    /**
     * Returns the segment marked as the class, or {@code null} if no class marker is present.
     */
    @Nullable
    public Segment classSegment() {
        return classMarkerIndex != null && classMarkerIndex < segments.size() ? segments.get(classMarkerIndex) : null;
    }

    @Override
    public String toString() {
        if (segments.isEmpty()) {
            if (!furtherLeafNodes) {
                return ":";
            }
            return classMarkerIndex != null ? ":^_" : ":_";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            sb.append(':');
            if (classMarkerIndex != null && classMarkerIndex == i) {
                sb.append('^');
            }
            sb.append(segments.get(i));
        }
        if (furtherLeafNodes) {
            sb.append(':');
            if (classMarkerIndex != null && classMarkerIndex == segments.size()) {
                sb.append('^');
            }
            sb.append('_');
        }
        return sb.toString();
    }
}
