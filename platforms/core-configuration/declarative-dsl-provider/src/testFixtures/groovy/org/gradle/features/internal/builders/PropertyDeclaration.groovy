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

package org.gradle.features.internal.builders

/**
 * Describes a property to be generated in a definition or build model type.
 *
 * <p>By default, the property generates a {@code Property<T>} getter. Flags modify the generated accessor:</p>
 * <ul>
 *     <li>{@code isList} — generates {@code ListProperty<T>} instead of {@code Property<T>}</li>
 *     <li>{@code isReadOnly} — generates a concrete return type (e.g. {@code Directory}) instead of wrapping in {@code Property<T>}</li>
 *     <li>{@code isJavaBean} — generates a getter/setter pair instead of a {@code Property<T>} getter</li>
 * </ul>
 *
 * <p>Some types receive special handling regardless of flags: {@code DirectoryProperty} and {@code RegularFileProperty}
 * generate their own type name directly rather than wrapping in {@code Property<T>}.</p>
 */
class PropertyDeclaration {
    /** Controls whether a java bean property generates abstract or concrete accessors. */
    enum Shape {
        /** Generates abstract getter/setter declarations. This is the default. */
        ABSTRACT,
        /** Generates a concrete backing field with getter/setter. Only valid for java bean properties in abstract classes. */
        CONCRETE
    }

    /** The property name, used to derive the getter name (e.g. "text" becomes "getText()"). */
    String name

    /** The property type (e.g. {@code String}, {@code DirectoryProperty}, {@code Directory}). */
    Class type

    /** If true, generates {@code ListProperty<T>} instead of {@code Property<T>}. */
    boolean isList = false

    /** If true, generates the concrete type as the return type (e.g. {@code Directory getDir()}) instead of {@code Property<T>}. */
    boolean isReadOnly = false

    /** If true, generates a getter/setter pair instead of a {@code Property<T>} getter. */
    boolean isJavaBean = false

    /** Controls whether a java bean property is abstract or concrete. Only relevant when {@code isJavaBean} is true. */
    Shape shape = Shape.ABSTRACT

    /** Sets the shape of this property declaration. */
    void shape(Shape s) { this.shape = s }

    /**
     * Returns true if this property requires a backing field initialized via {@code ObjectFactory}
     * in an abstract class constructor. This applies to {@code Property<Directory>} and
     * {@code Property<RegularFile>} — i.e., non-readOnly, non-javaBean, non-list properties
     * where the type is {@code Directory} or {@code RegularFile}.
     */
    boolean isPropertyFieldType() {
        return !isReadOnly && !isJavaBean && !isList &&
            (type == org.gradle.api.file.Directory || type == org.gradle.api.file.RegularFile)
    }
}
