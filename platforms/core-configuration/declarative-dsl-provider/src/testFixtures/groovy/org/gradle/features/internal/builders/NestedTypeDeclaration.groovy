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
 * Describes a nested type within a definition.
 *
 * <p>A nested type can be either a plain {@code @Nested} type (generates a getter with {@code @Nested}
 * and an {@code @HiddenInDefinition} configurator method) or a {@code NamedDomainObjectContainer}
 * (NDOC) element type.</p>
 *
 * <p>Nested types can themselves contain properties, injected services, sub-nested types, and
 * optionally implement {@code Definition<BuildModel>} (for NDOC elements that are definitions).</p>
 */
class NestedTypeDeclaration {
    /** The accessor name for this nested type (e.g. "foo" generates "getFoo()"). */
    String name

    /** The type name of the nested type (e.g. "Foo"). */
    String typeName

    /** Whether this nested type extends {@code Definition<BuildModel>}. */
    boolean implementsDefinition = false

    /** The build model for this nested type, if it implements Definition. */
    BuildModelDeclaration buildModel = null

    /** Whether this nested type is a {@code NamedDomainObjectContainer} element. */
    boolean isNdoc = false

    /** Whether the NDOC getter uses out-projection ({@code ? extends T}). */
    boolean isOutProjected = false

    /** Simple properties on this nested type. */
    List<PropertyDeclaration> properties = []

    /** Injected services on this nested type. */
    List<ServiceDeclaration> injectedServices = []

    /** Sub-nested types within this nested type. */
    List<NestedTypeDeclaration> nestedTypes = []

    /** Adds a simple property to this nested type. */
    void property(String name, Class type) {
        properties.add(new PropertyDeclaration(name: name, type: type))
    }

    /** Adds a {@code ListProperty<T>} to this nested type. */
    void listProperty(String name, Class elementType) {
        properties.add(new PropertyDeclaration(name: name, type: elementType, isList: true))
    }

    /**
     * Adds a sub-nested type with its own properties.
     *
     * @param name the accessor name
     * @param nestedTypeName the type name for the sub-nested type
     * @param config optional configuration closure
     */
    void property(String name, String nestedTypeName,
        @DelegatesTo(value = NestedTypeDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config = {}
    ) {
        def nested = new NestedTypeDeclaration(name: name, typeName: nestedTypeName)
        config.delegate = nested
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        nestedTypes.add(nested)
    }

    /**
     * Adds a {@code NamedDomainObjectContainer} sub-nested type.
     *
     * @param name the accessor name
     * @param elementTypeName the element type name
     * @param config optional configuration closure
     */
    void ndoc(String name, String elementTypeName,
        @DelegatesTo(value = NestedTypeDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config = {}
    ) {
        def nested = new NestedTypeDeclaration(name: name, typeName: elementTypeName, isNdoc: true)
        config.delegate = nested
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        nestedTypes.add(nested)
    }

    /** Adds an injected service to this nested type. */
    void injectedService(String name, Class type) {
        injectedServices.add(new ServiceDeclaration(name: name, type: type))
    }

    /**
     * Marks this nested type as implementing {@code Definition<BuildModel>}, which
     * makes it eligible for NDOC-based registration and feature binding.
     *
     * @param buildModelName the class name of the build model interface
     * @param config optional configuration for the build model's properties
     */
    void implementsDefinition(String buildModelName,
        @DelegatesTo(value = BuildModelDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config = {}
    ) {
        this.implementsDefinition = true
        this.buildModel = new BuildModelDeclaration(className: buildModelName)
        config.delegate = this.buildModel
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /** Marks this type as a {@code NamedDomainObjectContainer} element. */
    void asNdoc() { this.isNdoc = true }

    /** Marks the NDOC getter as out-projected ({@code NamedDomainObjectContainer<? extends T>}). */
    void outProjected() { this.isOutProjected = true }
}
