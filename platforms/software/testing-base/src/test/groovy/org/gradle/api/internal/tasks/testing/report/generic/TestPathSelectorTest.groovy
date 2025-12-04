/*
 * Copyright 2026 the original author or authors.
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

package org.gradle.api.internal.tasks.testing.report.generic

import org.gradle.util.Path
import spock.lang.Specification

class TestPathSelectorTest extends Specification {

    // region Parsing

    def "from() parses and round-trips #name"() {
        expect:
        TestPathSelector.from(input).toString() == input

        where:
        name                            | input
        "root"                          | ":"
        "single segment"                | ":a"
        "multi segment"                 | ":a:b:c"
        "simple absolute path"          | ":com:example"
        "trailing empty segment"        | ":com:"
        "regex segment"                 | ":com:~.*Test"
        "leaf marker"                   | ":com:example:_"
        "regex mid-path"                | ":a:~.*:b"
        "short leaf marker"             | ":a:_"
        "escaped colon"                 | ":%::"
        "escaped percent"               | ":%%"
        "escaped tilde"                 | ":%~"
        "escaped underscore"            | ":%_"
        "regex with escaped colon"      | ":~foo%:bar"
        "regex with escaped underscore" | ":~[a-z%_]+"
        "class marker"                  | ":a:^b:c"
        "class marker first segment"    | ":^a:b"
        "class marker with regex"       | ":a:^~.*:c"
        "class marker with leaf"        | ":a:^_"
        "class marker only leaf"        | ":^_"
        "escaped caret"                 | ":%^"
    }

    def "from() rejects non-absolute path: #input"() {
        when:
        TestPathSelector.from(input)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Only absolute paths are supported, got '" + input + "'"

        where:
        input << ["", "com:example"]
    }

    def "from() rejects underscore not in last position"() {
        when:
        TestPathSelector.from(":_:foo")

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "The '_' segment must be the last segment in the path: :_:foo"
    }

    def "from() parses classMarkerIndex for #name"() {
        expect:
        TestPathSelector.from(input).classMarkerIndex() == expectedIndex

        where:
        name                | input       | expectedIndex
        "no marker"         | ":a:b:c"    | null
        "marker on second"  | ":a:^b:c"   | 1
        "marker on first"   | ":^a:b"     | 0
        "marker with regex" | ":a:^~.*:c" | 1
        "escaped caret"     | ":%^"       | null
        "marker on last"    | ":a:b:^c"   | 2
        "marker on leaf"    | ":a:^_"     | 1
        "marker only leaf"  | ":^_"       | 0
    }

    def "classSegment() returns the marked segment"() {
        expect:
        TestPathSelector.from(":a:^b:c").classSegment() == new TestPathSelector.Segment.Literal("b")
        TestPathSelector.from(":a:b:c").classSegment() == null
        TestPathSelector.from(":a:^_").classSegment() == null
    }

    def "from() rejects multiple class markers"() {
        when:
        TestPathSelector.from(":^a:^b")

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Only one class marker ('^') is allowed per path: :^a:^b"
    }

    def "from() rejects invalid escape sequence: #input"() {
        when:
        TestPathSelector.from(input)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == expectedMessage

        where:
        input | expectedMessage
        ":%a" | "Invalid escape sequence: %a"
        ":a%" | "Dangling escape at end of path: :a%"
        ":%"  | "Dangling escape at end of path: :%"
    }

    // endregion

    // region Matching

    def "matches '#selector' against '#path' = #expectedMatch"() {
        when:
        def result = TestPathSelector.from(selector).matches(Path.path(path))

        then:
        result.isMatch() == expectedMatch

        where:
        selector         | path               | expectedMatch
        ":com:example"   | ":com:example"     | true  // exact literal match
        ":"              | ":"                | true  // root matches root
        ":com:~.*Test"   | ":com:MyTest"      | true  // regex match
        ":com:example:_" | ":com:example"     | true  // leaf marker matches one fewer segment
        ":_"             | ":"                | true  // leaf-marker-only matches root
        ":"              | ":foo"             | false // root doesn't match non-empty
        ":a:b:c"         | ":a:b"             | false // segment count mismatch
        ":a:b"           | ":a:c"             | false // literal mismatch
        ":com:~.*Test"   | ":com:TestCase"    | false // regex mismatch
        ":com:example:_" | ":com:example:foo" | false // leaf marker doesn't match same count
        ":a:_"           | ":a:b:c"           | false // leaf marker doesn't match extra segments
        ":a:^b:c"        | ":a:b:c"           | true  // class marker doesn't affect matching
        ":a:^~.*:c"      | ":a:foo:c"         | true  // class marker with regex still matches
        ":a:^_"          | ":a"               | true  // class marker with leaf marker matches
        ":^_"            | ":"                | true  // class marker with leaf marker only matches root
    }

    def "matched result is the Matched singleton"() {
        when:
        def result = TestPathSelector.from(":a").matches(Path.path(":a"))

        then:
        result == TestPathSelector.MatchResult.Matched.INSTANCE
    }

    def "mismatch results have descriptive display names"() {
        when:
        def result = TestPathSelector.from(selector).matches(Path.path(path))

        then:
        result.displayName == expectedMessage

        where:
        selector | path   | expectedMessage
        ":a:b:c" | ":a:b" | "segment count mismatch (expected: 3, actual: 2)"
        ":a:b"   | ":a:c" | "segment 1 mismatch (expected 'b', got 'c')"
        ":a:_"   | ":a:b" | "segment count mismatch (expected: 1, actual: 2)"
    }

    def "regex mismatch display name includes pattern"() {
        when:
        def result = TestPathSelector.from(":com:~.*Test").matches(Path.path(":com:TestCase"))

        then:
        result.displayName.contains("regex /.*Test/")
    }

    def "returns NotAbsolute for relative path"() {
        when:
        def result = TestPathSelector.from(":a").matches(Path.path("relative"))

        then:
        result == TestPathSelector.MatchResult.NotAbsolute.INSTANCE
    }

    // endregion

    // region ancestors()

    def "ancestors of '#input' are #expectedAncestors"() {
        when:
        def ancestors = TestPathSelector.from(input).ancestors().collect { it.toString() }

        then:
        ancestors == expectedAncestors

        where:
        input    | expectedAncestors
        ":a:b:c" | [":a:b:_", ":a:_", ":_"]
        ":a"     | [":_"]
        ":"      | []
    }

    def "class marker propagation for ancestors of '#input'"() {
        when:
        def ancestors = TestPathSelector.from(input).ancestors().collect { it }

        then:
        ancestors.collect { it.toString() } == expectedAncestorStrings
        ancestors.collect { it.classMarkerIndex() } == expectedClassMarkerIndices

        where:
        input     | expectedAncestorStrings     | expectedClassMarkerIndices
        ":a:^b:c" | [":a:^b:_", ":a:^_", ":^_"] | [1, 1, 0]
        ":^a:b:c" | [":^a:b:_", ":^a:_", ":^_"] | [0, 0, 0]
        ":a:b:^c" | [":a:b:^_", ":a:^_", ":^_"] | [2, 1, 0]
        ":a:^_"   | [":^_"]                     | [0]
    }

    def "leaf marker handling for ancestors of '#input' results in #expectedAncestors"() {
        when:
        def ancestors = TestPathSelector.from(input).ancestors().collect { it.toString() }

        then:
        ancestors == expectedAncestors

        where:
        input    | expectedAncestors
        ":a:b:_" | [":a:_", ":_"]
        ":a:_"   | [":_"]
        ":_"     | []
    }

    // endregion

    // region equals() / hashCode()

    def "selectors from same input are equal: #name"() {
        expect:
        TestPathSelector.from(input) == TestPathSelector.from(input)
        TestPathSelector.from(input).hashCode() == TestPathSelector.from(input).hashCode()

        where:
        name          | input
        "literal"     | ":a:b"
        "regex"       | ":~.*Test"
        "leaf marker" | ":a:_"
    }

    def "different selectors are not equal: '#a' vs '#b'"() {
        expect:
        TestPathSelector.from(a) != TestPathSelector.from(b)

        where:
        a          | b
        ":a:b"     | ":a:c"       // different literal
        ":a:b"     | ":a"         // different length
        ":~.*Test" | ":~.*Spec"   // different regex
        ":a:^b"    | ":a:b"       // class marker differs
    }

    // endregion
}
