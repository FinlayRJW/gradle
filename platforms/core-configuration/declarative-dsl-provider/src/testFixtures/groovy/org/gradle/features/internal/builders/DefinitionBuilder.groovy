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

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.Directory
import org.gradle.declarative.dsl.model.annotations.Adding
import org.gradle.declarative.dsl.model.annotations.HiddenInDefinition
import org.gradle.features.binding.BuildModel
import org.gradle.features.binding.Definition
import org.gradle.test.fixtures.plugin.PluginBuilder

/**
 * Generates Java source code for a definition interface or abstract class.
 *
 * <p>This is a compositional builder that replaces the 21 separate definition builder subclasses.
 * Instead of choosing a specific subclass for each variation, you declare what the generated
 * definition should contain: properties, nested types, services, NDOC containers, build model,
 * and shape (interface vs abstract class).</p>
 *
 * <p>The builder produces one or more Java source files when {@link #build} is called:</p>
 * <ul>
 *     <li>The public type (interface or abstract class)</li>
 *     <li>Optionally, a separate implementation type class (when {@link #implementationType} is called)</li>
 *     <li>Optionally, a parent type interface (when {@link #parentDefinition} is configured)</li>
 * </ul>
 *
 * <p>In addition to generating source files, this builder provides derived accessor methods
 * ({@link #getBuildModelMapping()}, {@link #displayDefinitionPropertyValues()}, etc.) that are
 * consumed by {@link PluginClassBuilder} to generate the mapping and display code in plugin apply actions.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * definition("TestProjectTypeDefinition") {
 *     buildModel("ModelType") { property "id", String }
 *     property "id", String
 *     property("foo", "Foo") {
 *         implementsDefinition("FooBuildModel") { property "barProcessed", String }
 *         property "bar", String
 *     }
 * }
 * </pre>
 */
class DefinitionBuilder {
    /** Controls whether the generated definition is an interface or an abstract class. */
    enum Shape {
        /** Generates a Java interface. This is the default. */
        INTERFACE,
        /** Generates a Java abstract class with constructor injection and concrete nested type fields. */
        ABSTRACT_CLASS
    }

    /** The simple class name of the definition (e.g. "TestProjectTypeDefinition"). */
    String className

    /** The Java package for generated source files. */
    String packageName = "org.gradle.test"

    /** Whether to generate an interface or abstract class. */
    Shape shape = Shape.INTERFACE

    /** The class name of the separate implementation type, or null if there is none. */
    String implementationClassName

    /** Dependencies declaration, or null if this definition does not support dependencies. */
    DependenciesDeclaration dependenciesDeclaration = null

    /** A parent definition whose injected services are inherited via an extends clause. */
    DefinitionBuilder parentDefinition = null

    /** The build model inner interface declaration. Null means no build model. */
    BuildModelDeclaration buildModel = null

    /** When true, the definition extends {@code Definition<BuildModel.None>} with no inner build model interface. */
    boolean usesNoBuildModel = false

    /** Top-level properties on this definition. */
    List<PropertyDeclaration> properties = []

    /** Services injected into this definition via {@code @Inject}. */
    List<ServiceDeclaration> injectedServices = []

    /** Nested types (both {@code @Nested} and NDOC) within this definition. */
    List<NestedTypeDeclaration> nestedTypes = []

    DefinitionBuilder() {}
    DefinitionBuilder(String className) { this.className = className }

    // --- Derived accessors used by PluginClassBuilder ---

    /** Returns the fully qualified class name (e.g. "org.gradle.test.TestProjectTypeDefinition"). */
    String getFullyQualifiedPublicTypeClassName() {
        return packageName + "." + className
    }

    /**
     * Returns the fully qualified build model class name, or null if no build model.
     * When a parent definition exists, the build model is declared on the parent, so the
     * parent's class name is used (required for Kotlin which can't resolve inherited inner types).
     */
    String getFullyQualifiedBuildModelClassName() {
        if (!buildModel) {
            return null
        }
        def owningClassName = parentDefinition ? packageName + ".Parent" + className : fullyQualifiedPublicTypeClassName
        return owningClassName + "." + buildModel.className
    }

    /**
     * Returns the build model type as used in apply action type parameters.
     * Falls back to {@code BuildModel.None} when no build model is declared.
     */
    String getBuildModelFullPublicClassName() {
        return buildModel ? className + "." + buildModel.className : "${BuildModel.class.name}.None"
    }

    /** Returns the build model implementation class name, or null if there is no separate implementation. */
    String getBuildModelFullImplementationClassName() {
        return (buildModel?.implementationClassName) ? className + "." + buildModel.implementationClassName : null
    }

    // --- DSL methods ---

    /**
     * Configures the existing {@code BuildModel} inner interface (e.g. to add properties).
     * The build model must already exist (set by the factory method or a prior call).
     *
     * @param config closure to configure the build model
     */
    void buildModel(
        @DelegatesTo(value = BuildModelDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config
    ) {
        if (this.buildModel == null) {
            throw new IllegalStateException("No build model exists. Use buildModel(String, Closure) to create one first.")
        }
        config.delegate = this.buildModel
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /**
     * Declares a {@code BuildModel} inner interface on this definition with the given class name.
     *
     * @param modelClassName the simple class name (e.g. "ModelType")
     * @param config optional closure to add properties to the build model
     */
    void buildModel(String modelClassName,
        @DelegatesTo(value = BuildModelDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config = {}
    ) {
        this.buildModel = new BuildModelDeclaration(className: modelClassName)
        config.delegate = this.buildModel
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /**
     * Removes the build model inner interface and makes the definition extend {@code Definition<BuildModel.None>}.
     * This is used for feature definitions that don't produce a build model.
     */
    void noBuildModel() {
        this.buildModel = null
        this.usesNoBuildModel = true
    }

    /** Adds a {@code Property<T>} getter to the definition. */
    void property(String name, Class type) {
        properties.add(new PropertyDeclaration(name: name, type: type))
    }

    /** Adds a read-only property that returns the concrete type directly (e.g. {@code Directory getDir()}). */
    void readOnlyProperty(String name, Class type) {
        properties.add(new PropertyDeclaration(name: name, type: type, isReadOnly: true))
    }

    /** Adds a Java Bean property with getter/setter pair instead of {@code Property<T>}. */
    void javaBeanProperty(String name, Class type) {
        properties.add(new PropertyDeclaration(name: name, type: type, isJavaBean: true))
    }

    /** Adds a Java Bean property with getter/setter pair, with optional configuration (e.g. shape). */
    void javaBeanProperty(String name, Class type,
        @DelegatesTo(value = PropertyDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config
    ) {
        def property = new PropertyDeclaration(name: name, type: type, isJavaBean: true)
        config.delegate = property
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        properties.add(property)
    }

    /** Adds a {@code ListProperty<T>} getter to the definition. */
    void listProperty(String name, Class elementType) {
        properties.add(new PropertyDeclaration(name: name, type: elementType, isList: true))
    }

    /**
     * Adds a {@code @Nested} type with its own properties, services, and sub-nested types.
     *
     * @param name the accessor name (e.g. "foo" generates {@code getFoo()} and {@code foo(Action)})
     * @param nestedTypeName the type name of the nested interface/class (e.g. "Foo")
     * @param config closure to configure the nested type's properties
     */
    void property(String name, String nestedTypeName,
        @DelegatesTo(value = NestedTypeDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config = {}
    ) {
        def nestedType = new NestedTypeDeclaration(name: name, typeName: nestedTypeName)
        config.delegate = nestedType
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        nestedTypes.add(nestedType)
    }

    /**
     * Adds a {@code NamedDomainObjectContainer<T>} property to the definition.
     *
     * @param name the accessor name (e.g. "sources" generates {@code getSources()})
     * @param elementTypeName the element type name (e.g. "Source")
     * @param config closure to configure the NDOC element type
     */
    void ndoc(String name, String elementTypeName,
        @DelegatesTo(value = NestedTypeDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config = {}
    ) {
        def nestedType = new NestedTypeDeclaration(name: name, typeName: elementTypeName, isNdoc: true)
        config.delegate = nestedType
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        nestedTypes.add(nestedType)
    }

    /** Adds an {@code @Inject} service accessor to the definition. */
    void injectedService(String name, Class type) {
        injectedServices.add(new ServiceDeclaration(name: name, type: type))
    }

    /** Sets the shape (interface or abstract class) of the generated definition. */
    void shape(Shape s) { this.shape = s }

    /**
     * Declares that this definition has a separate implementation type class.
     * This generates an additional file with an abstract class that implements the definition interface.
     */
    void implementationType(String implClassName) {
        this.implementationClassName = implClassName
    }

    /**
     * Declares a dependencies block with named dependency collectors.
     *
     * @param config closure delegating to {@link DependenciesDeclaration}
     */
    void dependencies(
        @DelegatesTo(value = DependenciesDeclaration, strategy = Closure.DELEGATE_FIRST)
        Closure config
    ) {
        this.dependenciesDeclaration = new DependenciesDeclaration()
        config.delegate = this.dependenciesDeclaration
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }

    /**
     * Declares a parent definition whose injected services are inherited.
     * Generates a parent interface and makes this definition extend it.
     *
     * @param config closure to configure the parent (typically just adding injected services)
     */
    void parentDefinition(
        @DelegatesTo(value = DefinitionBuilder, strategy = Closure.DELEGATE_FIRST)
        Closure config
    ) {
        def parent = new DefinitionBuilder()
        config.delegate = parent
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        this.parentDefinition = parent
    }

    // --- Code generation ---

    /**
     * Generates Java source files and writes them to the plugin builder.
     * Produces 1-3 files depending on configuration: the public type, an optional
     * implementation type, and an optional parent type.
     */
    void build(PluginBuilder pluginBuilder) {
        pluginBuilder.file("src/main/java/${packageName.replace('.', '/')}/${className}.java").text = getPublicTypeClassContent()
        if (implementationClassName) {
            pluginBuilder.file("src/main/java/${packageName.replace('.', '/')}/${implementationClassName}.java").text = getImplementationTypeClassContent()
        }
        if (parentDefinition) {
            def parentClassName = "Parent${className}"
            pluginBuilder.file("src/main/java/${packageName.replace('.', '/')}/${parentClassName}.java").text = getParentClassContent(parentClassName)
        }
        if (dependenciesDeclaration) {
            pluginBuilder.file("src/main/java/${packageName.replace('.', '/')}/${dependenciesDeclaration.interfaceName}.java").text = getDependenciesInterfaceContent()
        }
    }

    String getPublicTypeClassContent() {
        if (shape == Shape.ABSTRACT_CLASS) {
            return generateAbstractClassContent(className)
        }
        if (parentDefinition) {
            return generateChildInterfaceContent(className)
        }
        return generateInterfaceContent(className)
    }

    String getImplementationTypeClassContent() {
        return """
            package ${packageName};

            import org.gradle.api.Action;
            import org.gradle.api.model.ObjectFactory;
            import org.gradle.api.provider.ListProperty;
            import org.gradle.api.provider.Property;

            import javax.inject.Inject;

            public abstract class ${implementationClassName} implements ${className} {
                ${generateImplConstructorAndFields()}

                public abstract Property<String> getNonPublic();
            }
        """
    }

    // --- Build model mapping (consumed by PluginClassBuilder) ---

    /**
     * Returns code that maps definition property values to build model properties,
     * formatted for the specified language. Returns the custom mapping if set,
     * otherwise auto-derives mappings from matching property names.
     */
    String getBuildModelMapping(Language language) {
        if (buildModel == null) {
            return ""
        }
        if (buildModel.customMappings.containsKey(language)) {
            return buildModel.customMappings[language]
        }
        // Only simple (non-list) properties are expected to map to build model properties.
        // List properties and nested types are display-only and don't participate in mapping.
        def mappableProperties = properties.findAll { !it.isList }
        def unmappedDefinitionProperties = mappableProperties.findAll { defProp ->
            !buildModel.properties.any { it.name == defProp.name }
        }
        if (!unmappedDefinitionProperties.isEmpty()) {
            def names = unmappedDefinitionProperties.collect { it.name }.join(", ")
            throw new IllegalStateException(
                "Definition '${className}' has properties [${names}] with no matching build model properties. " +
                "Either add matching properties to the build model or provide a custom buildModelMapping."
            )
        }
        def mappings = []
        buildModel.properties.each { buildModelProperty ->
            def definitionProperty = properties.find { it.name == buildModelProperty.name }
            if (definitionProperty) {
                mappings << generatePropertyMapping(buildModelProperty, definitionProperty, language)
            }
        }
        return mappings.join("\n")
    }

    /**
     * Returns code that prints all definition property values, formatted for the specified language.
     * Used inside the plugin's task body for test verification.
     */
    String displayDefinitionPropertyValues(Language language) {
        def lines = []
        properties.each { property ->
            if (property.isList && dependenciesDeclaration) {
                def accessor = "definition.printList(${propertyAccessor('definition', property.name, language)}.get())"
                lines << printStatement("definition", property.name, accessor, language)
            } else {
                lines << printStatement("definition", property.name, generatePropertyAccess("definition", property, language), language)
            }
        }
        nestedTypes.each { nestedType ->
            if (nestedType.isNdoc) {
                def accessor = "${propertyAccessor('definition', nestedType.name, language)}.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(\", \"))"
                lines << printStatement("definition", nestedType.name, accessor, language)
            } else {
                nestedType.properties.each { property ->
                    def parentAccessor = propertyAccessor("definition", nestedType.name, language)
                    if (property.isList && dependenciesDeclaration) {
                        def accessor = "definition.printList(${parentAccessor}.get${capitalize(property.name)}().get())"
                        lines << printStatement("definition", "${nestedType.name}.${property.name}", accessor, language)
                    } else {
                        lines << printStatement("definition", "${nestedType.name}.${property.name}",
                            generatePropertyAccess(parentAccessor, property, language), language)
                    }
                }
            }
        }
        if (dependenciesDeclaration) {
            dependenciesDeclaration.collectors.each { collectorName ->
                def accessor = "definition.printDependencies(${propertyAccessor('definition', 'dependencies', language)}.get${capitalize(collectorName)}())"
                lines << printStatement("definition", collectorName, accessor, language)
            }
        }
        if (shape == Shape.ABSTRACT_CLASS) {
            nestedTypes.findAll { !it.isNdoc }.each { nestedType ->
                def methodCall = "definition.maybe${capitalize(nestedType.name)}Configured()"
                lines << printCall("\"definition \" + ${methodCall}", language)
            }
        }
        return lines.join("\n")
    }

    /**
     * Returns code that prints all build model property values, formatted for the specified language.
     * Used inside the plugin's task body for test verification.
     */
    String displayModelPropertyValues(Language language) {
        if (buildModel == null) {
            return ""
        }
        def lines = []
        buildModel.properties.each { property ->
            lines << printStatement("model", property.name, generatePropertyAccess("model", property, language), language)
        }
        return lines.join("\n")
    }

    // --- Private helpers ---

    private String generateInterfaceContent(String effectiveClassName) {
        def extendsClause
        if (buildModel != null) {
            extendsClause = "extends ${Definition.class.simpleName}<${effectiveClassName}.${buildModel.className}>"
        } else if (usesNoBuildModel) {
            extendsClause = "extends ${Definition.class.simpleName}<${BuildModel.class.simpleName}.None>"
        } else {
            extendsClause = ""
        }

        return """
            package ${packageName};

            import ${HiddenInDefinition.class.name};

            import org.gradle.api.Action;
            import org.gradle.api.Named;
            import org.gradle.api.NamedDomainObjectContainer;
            import org.gradle.api.model.ObjectFactory;
            import org.gradle.api.provider.ListProperty;
            import org.gradle.api.provider.Property;
            import org.gradle.api.file.DirectoryProperty;
            import org.gradle.api.file.RegularFileProperty;
            import org.gradle.api.file.Directory;
            import org.gradle.api.file.RegularFile;
            import org.gradle.api.tasks.Nested;
            import ${Definition.class.name};
            import ${BuildModel.class.name};

            import javax.inject.Inject;

            public interface ${effectiveClassName} ${extendsClause} {
                ${generatePropertyDeclarations(false)}

                ${generateInjectedServiceDeclarations(injectedServices, false)}

                ${generateNestedTypeDeclarations(false)}

                ${generateNestedTypeInterfaces(effectiveClassName)}

                ${generateBuildModelInterface()}
            }
        """
    }

    private String generateAbstractClassContent(String effectiveClassName) {
        def implementsClause = buildModel != null
            ? "implements ${Definition.class.simpleName}<${effectiveClassName}.${buildModel.className}>"
            : ""

        return """
            package ${packageName};

            import ${HiddenInDefinition.class.name};
            import ${Adding.class.name};

            import org.gradle.api.Action;
            import org.gradle.api.Named;
            import org.gradle.api.NamedDomainObjectContainer;
            import org.gradle.api.model.ObjectFactory;
            import org.gradle.api.provider.ListProperty;
            import org.gradle.api.provider.Property;
            import org.gradle.api.file.DirectoryProperty;
            import org.gradle.api.file.RegularFileProperty;
            import org.gradle.api.file.Directory;
            import org.gradle.api.file.RegularFile;
            import org.gradle.api.tasks.Nested;
            import ${Definition.class.name};
            import ${BuildModel.class.name};

            import javax.inject.Inject;

            public abstract class ${effectiveClassName} ${implementsClause} {
                ${generateAbstractClassFields()}

                ${generateAbstractClassConstructor(effectiveClassName)}

                ${generatePropertyDeclarations(true)}

                ${generateAddingMethods(properties)}

                ${generateAbstractNestedGetters()}

                ${generateDependenciesMembers()}

                ${generateInjectedServiceDeclarations(injectedServices, true)}

                ${generateNestedAbstractClassTypes()}

                ${generateAbstractClassMethods()}

                ${generateBuildModelInterface()}
            }
        """
    }

    private String generateChildInterfaceContent(String effectiveClassName) {
        def parentClassName = "Parent${effectiveClassName}"
        return """
            package ${packageName};

            import org.gradle.api.Action;
            import org.gradle.api.model.ObjectFactory;
            import org.gradle.api.provider.ListProperty;
            import org.gradle.api.provider.Property;

            import javax.inject.Inject;

            public interface ${effectiveClassName} extends ${parentClassName} { }
        """
    }

    private String getParentClassContent(String parentClassName) {
        // Generate the parent using the standard interface template but with the parent's injected services
        def parentBuilder = new DefinitionBuilder(parentClassName)
        parentBuilder.packageName = packageName
        parentBuilder.buildModel = buildModel
        parentBuilder.properties = properties
        parentBuilder.injectedServices = parentDefinition.injectedServices
        parentBuilder.nestedTypes = nestedTypes
        return parentBuilder.generateInterfaceContent(parentClassName)
    }

    private String generatePropertyDeclarations(boolean isAbstract) {
        def lines = []
        properties.each { property ->
            lines << generatePropertyGetter(property, isAbstract)
        }
        return lines.join("\n\n")
    }

    private String generatePropertyGetter(PropertyDeclaration property, boolean isAbstract) {
        def returnType = getPropertyReturnType(property)
        def prefix = isAbstract ? "public abstract " : ""
        def getterName = "get${capitalize(property.name)}"

        if (property.isJavaBean) {
            if (property.shape == PropertyDeclaration.Shape.CONCRETE) {
                return """private ${property.type.simpleName} ${property.name};

                public ${property.type.simpleName} ${getterName}() {
                    return ${property.name};
                }

                public void set${capitalize(property.name)}(${property.type.simpleName} value) {
                    this.${property.name} = value;
                }"""
            }
            def setter = isAbstract ? "public abstract void set${capitalize(property.name)}(${property.type.simpleName} value);" : "void set${capitalize(property.name)}(${property.type.simpleName} value);"
            return """${prefix}${property.type.simpleName} ${getterName}();
                ${setter}"""
        }

        if (property.isPropertyFieldType()) {
            return """public ${returnType} ${getterName}() {
                    return ${property.name};
                }"""
        }

        return "${prefix}${returnType} ${getterName}();"
    }

    private String generateNestedTypeDeclarations(boolean isAbstract) {
        def lines = []
        nestedTypes.each { nestedType ->
            if (nestedType.isNdoc) {
                if (nestedType.isOutProjected) {
                    // Private getter + public out-projected getter
                    lines << "abstract NamedDomainObjectContainer<${nestedType.typeName}> get${capitalize(nestedType.name)}();"
                    lines << "public NamedDomainObjectContainer<? extends ${nestedType.typeName}> getOut${capitalize(nestedType.name)}() { return get${capitalize(nestedType.name)}(); };"
                } else {
                    lines << "public abstract NamedDomainObjectContainer<${nestedType.typeName}> get${capitalize(nestedType.name)}();"
                }
            } else {
                def prefix = isAbstract ? "public abstract " : ""
                lines << """@Nested
                ${prefix}${nestedType.typeName} get${capitalize(nestedType.name)}();

                @${HiddenInDefinition.class.simpleName}
                ${isAbstract ? 'public ' : ''}default void ${nestedType.name}(Action<? super ${nestedType.typeName}> action) {
                    action.execute(get${capitalize(nestedType.name)}());
                }"""
            }
        }
        return lines.join("\n\n")
    }

    private String generateNestedTypeInterfaces(String parentClassName) {
        def lines = []
        nestedTypes.each { nestedType ->
            lines << generateNestedTypeInterface(nestedType, parentClassName)
            // Build model interfaces for nested types are siblings, not children
            if (nestedType.buildModel && !nestedType.isNdoc) {
                lines << generateNestedBuildModelInterface(nestedType)
            }
        }
        return lines.join("\n\n")
    }

    private String generateNestedTypeInterface(NestedTypeDeclaration nestedType, String parentClassName) {
        if (nestedType.isNdoc && !nestedType.implementsDefinition) {
            return generateNdocElementClass(nestedType)
        }

        if (nestedType.isNdoc && nestedType.implementsDefinition) {
            // NDOC element that implements Definition - generates interface with inner build model
            def extendsClause = nestedType.buildModel ? "extends ${Definition.class.simpleName}<${nestedType.typeName}.${nestedType.buildModel.className}>, Named" : ""
            def propertyGetters = nestedType.properties.collect { property ->
                "public abstract ${getPropertyReturnType(property)} get${capitalize(property.name)}();"
            }.join("\n")
            def subNestedType = nestedType.nestedTypes.collect { subNestedType ->
                generateNestedTypeInterface(subNestedType, parentClassName)
            }.join("\n\n")
            def bmIface = ""
            if (nestedType.buildModel) {
                def buildModelPropertyGetters = nestedType.buildModel.properties.collect { property ->
                    "${getPropertyReturnType(property)} get${capitalize(property.name)}();"
                }.join("\n")
                bmIface = """
                public interface ${nestedType.buildModel.className} extends BuildModel {
                    ${buildModelPropertyGetters}
                }
                """
            }
            return """
            public interface ${nestedType.typeName} ${extendsClause} {
                ${propertyGetters}
                ${subNestedType}
                ${bmIface}
            }
            """
        }

        def extendsClause = ""
        if (nestedType.implementsDefinition && nestedType.buildModel) {
            extendsClause = "extends ${Definition.class.simpleName}<${nestedType.buildModel.className}>"
        }

        def propertyGetters = nestedType.properties.collect { property ->
            "public abstract ${getPropertyReturnType(property)} get${capitalize(property.name)}();"
        }.join("\n")

        def services = generateInjectedServiceDeclarations(nestedType.injectedServices, false)

        def nestedAccessors = nestedType.nestedTypes.collect { subNestedType ->
            if (subNestedType.isNdoc) {
                "public abstract NamedDomainObjectContainer<${subNestedType.typeName}> get${capitalize(subNestedType.name)}();"
            } else {
                """@Nested
                ${subNestedType.typeName} get${capitalize(subNestedType.name)}();

                @${HiddenInDefinition.class.simpleName}
                default void ${subNestedType.name}(Action<? super ${subNestedType.typeName}> action) {
                    action.execute(get${capitalize(subNestedType.name)}());
                }"""
            }
        }.join("\n\n")

        def nestedInterfaces = nestedType.nestedTypes.collect { subNestedType ->
            def result = generateNestedTypeInterface(subNestedType, parentClassName)
            if (subNestedType.buildModel && !subNestedType.isNdoc) {
                result += "\n" + generateNestedBuildModelInterface(subNestedType)
            }
            result
        }.join("\n\n")

        return """
            public interface ${nestedType.typeName} ${extendsClause} {
                ${services}
                ${propertyGetters}
                ${nestedAccessors}
                ${nestedInterfaces}
            }
        """
    }

    private static String generateNestedBuildModelInterface(NestedTypeDeclaration nestedType) {
        if (!nestedType.buildModel) {
            return ""
        }
        def buildModelPropertyGetters = nestedType.buildModel.properties.collect { property ->
            "${getPropertyReturnType(property)} get${capitalize(property.name)}();"
        }.join("\n")
        return """
            public interface ${nestedType.buildModel.className} extends BuildModel {
                ${buildModelPropertyGetters}
            }
        """
    }

    private String generateNdocElementClass(NestedTypeDeclaration nestedType) {
        def propertyGetters = nestedType.properties.collect { property ->
            "public abstract ${getPropertyReturnType(property)} get${capitalize(property.name)}();"
        }.join("\n")

        // Generate accessors and inner types for sub-nested types
        def nestedAccessors = nestedType.nestedTypes.collect { subNestedType ->
            if (subNestedType.isNdoc) {
                "public abstract NamedDomainObjectContainer<${subNestedType.typeName}> get${capitalize(subNestedType.name)}();"
            } else {
                """@Nested
                public abstract ${subNestedType.typeName} get${capitalize(subNestedType.name)}();

                @${HiddenInDefinition.class.simpleName}
                public void ${subNestedType.name}(Action<? super ${subNestedType.typeName}> action) {
                    action.execute(get${capitalize(subNestedType.name)}());
                }"""
            }
        }.join("\n\n")

        def nestedInterfaces = nestedType.nestedTypes.collect { subNestedType ->
            def result = generateNestedTypeInterface(subNestedType, nestedType.typeName)
            if (subNestedType.buildModel && !subNestedType.isNdoc) {
                result += "\n" + generateNestedBuildModelInterface(subNestedType)
            }
            result
        }.join("\n\n")

        return """
            public abstract static class ${nestedType.typeName} implements Named {
                private String name;

                public ${nestedType.typeName}(String name) {
                    this.name = name;
                }

                @Override
                public String getName() {
                    return name;
                }

                ${propertyGetters}

                ${nestedAccessors}

                ${nestedInterfaces}

                @Override
                public String toString() {
                    return "${nestedType.typeName}(name = " + name${nestedType.properties.collect { property -> " + \", ${property.name} = \" + get${capitalize(property.name)}().get()" }.join('')} + ")";
                }
            }
        """
    }

    private String generateBuildModelInterface() {
        if (buildModel == null) {
            return ""
        }
        def buildModelPropertyGetters = buildModel.properties.collect { property ->
            "${getPropertyReturnType(property)} get${capitalize(property.name)}();"
        }.join("\n")

        def implInterface = ""
        if (buildModel.implementationClassName) {
            def implPropertyGetters = buildModel.properties.collect { property ->
                "${getPropertyReturnType(property)} get${capitalize(property.name)}();"
            }.join("\n")
            implInterface = """
                public interface ${buildModel.implementationClassName} extends ${buildModel.className} {
                    ${implPropertyGetters}
                }
            """
        }

        return """
            public interface ${buildModel.className} extends BuildModel {
                ${buildModelPropertyGetters}
            }
            ${implInterface}
        """
    }

    private String generateAbstractClassFields() {
        def lines = []
        nestedTypes.findAll { !it.isNdoc }.each { nestedType ->
            lines << "private final ${nestedType.typeName} ${nestedType.name};"
            lines << "private boolean is${capitalize(nestedType.name)}Configured = false;"
        }
        properties.findAll { it.isPropertyFieldType() }.each { property ->
            lines << "private final Property<${property.type.simpleName}> ${property.name};"
        }
        return lines.join("\n")
    }

    private String generateAbstractClassConstructor(String effectiveClassName) {
        def hasNestedTypes = nestedTypes.any { !it.isNdoc }
        def hasPropertyFields = properties.any { it.isPropertyFieldType() }
        if (hasNestedTypes || hasPropertyFields) {
            def needsObjectFactory = hasNestedTypes || hasPropertyFields
            def params = needsObjectFactory ? "ObjectFactory objects" : ""
            def inits = []
            nestedTypes.findAll { !it.isNdoc }.each {
                inits << "this.${it.name} = objects.newInstance(${it.typeName}.class);"
            }
            properties.findAll { it.isPropertyFieldType() }.each {
                inits << "this.${it.name} = ${propertyFieldInitializer(it)};"
            }
            return """
                @Inject
                public ${effectiveClassName}(${params}) {
                    ${inits.join("\n")}
                }
            """
        }
        def hasConcreteJavaBeans = properties.any { it.isJavaBean && it.shape == PropertyDeclaration.Shape.CONCRETE }
        if (hasConcreteJavaBeans) {
            return """
                @Inject
                public ${effectiveClassName}() {
                }
            """
        }
        return ""
    }

    private String generateAbstractNestedGetters() {
        def lines = []
        nestedTypes.each { nestedType ->
            if (nestedType.isNdoc) {
                if (nestedType.isOutProjected) {
                    lines << "abstract NamedDomainObjectContainer<${nestedType.typeName}> get${capitalize(nestedType.name)}();"
                    lines << "public NamedDomainObjectContainer<? extends ${nestedType.typeName}> getOut${capitalize(nestedType.name)}() { return get${capitalize(nestedType.name)}(); };"
                } else {
                    lines << "public abstract NamedDomainObjectContainer<${nestedType.typeName}> get${capitalize(nestedType.name)}();"
                }
            } else {
                lines << """
                public ${nestedType.typeName} get${capitalize(nestedType.name)}() {
                    is${capitalize(nestedType.name)}Configured = true; // TODO: get rid of the side effect in the getter
                    return ${nestedType.name};
                }

                @${HiddenInDefinition.class.simpleName}
                public void ${nestedType.name}(Action<? super ${nestedType.typeName}> action) {
                    action.execute(${nestedType.name});
                }
                """
            }
        }
        return lines.join("\n")
    }

    private String generateNestedAbstractClassTypes() {
        def lines = []
        nestedTypes.each { nestedType ->
            if (nestedType.isNdoc) {
                lines << generateNdocElementClass(nestedType)
            } else {
                def extendsClause = nestedType.implementsDefinition && nestedType.buildModel
                    ? "implements ${Definition.class.simpleName}<${nestedType.buildModel.className}>"
                    : ""

                def nestedPropertyGetters = nestedType.properties.collect { property ->
                    "public abstract ${getPropertyReturnType(property)} get${capitalize(property.name)}();"
                }.join("\n")

                def nestedServices = generateInjectedServiceDeclarations(nestedType.injectedServices, true)

                def nestedAddingMethods = generateAddingMethods(nestedType.properties)

                lines << """
                    public abstract static class ${nestedType.typeName} ${extendsClause} {
                        public ${nestedType.typeName}() { }

                        ${nestedServices}

                        ${nestedPropertyGetters}

                        ${nestedAddingMethods}
                    }
                """

                if (nestedType.buildModel) {
                    def buildModelPropertyGetters = nestedType.buildModel.properties.collect { property ->
                        "${getPropertyReturnType(property)} get${capitalize(property.name)}();"
                    }.join("\n")
                    lines << """
                        public interface ${nestedType.buildModel.className} extends BuildModel {
                            ${buildModelPropertyGetters}
                        }
                    """
                }
            }
        }
        return lines.join("\n")
    }

    private String getDependenciesInterfaceContent() {
        if (!dependenciesDeclaration) {
            return ""
        }
        def collectorGetters = dependenciesDeclaration.collectors.collect { name ->
            "DependencyCollector get${capitalize(name)}();"
        }.join("\n\n")
        return """
            package ${packageName};

            import org.gradle.api.artifacts.dsl.Dependencies;
            import org.gradle.api.artifacts.dsl.DependencyCollector;

            public interface ${dependenciesDeclaration.interfaceName} extends Dependencies {
                ${collectorGetters}
            }
        """
    }

    private String generateDependenciesMembers() {
        if (!dependenciesDeclaration) {
            return ""
        }
        return """
            @Nested
            public abstract ${dependenciesDeclaration.interfaceName} getDependencies();

            @${HiddenInDefinition.class.simpleName}
            public void dependencies(Action<? super ${dependenciesDeclaration.interfaceName}> action) {
                action.execute(getDependencies());
            }

            public String printDependencies(org.gradle.api.artifacts.dsl.DependencyCollector collector) {
                return collector.getDependencies().get().stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", "));
            }

            public String printList(java.util.List<?> list) {
                return list.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", "));
            }
        """
    }

    private String generateAddingMethods(List<PropertyDeclaration> props) {
        def lines = []
        props.findAll { it.isList }.each { property ->
            lines << """
                @${Adding.class.simpleName}
                public void addTo${capitalize(property.name)}(${property.type.simpleName} value) {
                    get${capitalize(property.name)}().add(value);
                }
            """
        }
        return lines.join("\n")
    }

    private String generateAbstractClassMethods() {
        def lines = []
        nestedTypes.findAll { !it.isNdoc }.each { nestedType ->
            lines << """
                public String maybe${capitalize(nestedType.name)}Configured() {
                    return is${capitalize(nestedType.name)}Configured ? "(${nestedType.name} is configured)" : "";
                }
            """
        }
        return lines.join("\n")
    }

    private String generateImplConstructorAndFields() {
        def nestedFields = nestedTypes.findAll { !it.isNdoc }
        if (nestedFields.isEmpty()) {
            return ""
        }
        def fields = nestedFields.collect { "private final ${it.typeName} ${it.name};" }.join("\n")
        def inits = nestedFields.collect { "this.${it.name} = objects.newInstance(${it.typeName}.class);" }.join("\n")
        def getters = nestedFields.collect {
            """@Override
                public ${it.typeName} get${capitalize(it.name)}() {
                    return ${it.name};
                }"""
        }.join("\n\n")

        return """
            ${fields}

            @Inject
            public ${implementationClassName}(ObjectFactory objects) {
                ${inits}
            }

            ${getters}
        """
    }

    private static String generateInjectedServiceDeclarations(List<ServiceDeclaration> services, boolean isAbstract) {
        return services.collect { service ->
            if (isAbstract) {
                """@Inject
                abstract ${service.type.name} get${capitalize(service.name)}();"""
            } else {
                """@Inject
                ${service.type.name} get${capitalize(service.name)}();"""
            }
        }.join("\n")
    }

    private String generatePropertyMapping(PropertyDeclaration buildModelProperty, PropertyDeclaration definitionProperty, Language language) {
        def modelAccessor = propertyAccessor("model", buildModelProperty.name, language)
        def definitionAccessor = propertyAccessor("definition", definitionProperty.name, language)
        def statementEnd = (language == Language.KOTLIN) ? "" : ";"
        return "${modelAccessor}.set(${definitionAccessor})${statementEnd}"
    }

    private static String generatePropertyAccess(String objectExpression, PropertyDeclaration property, Language language) {
        def accessor = propertyAccessor(objectExpression, property.name, language)
        if (property.isReadOnly || property.isJavaBean) {
            if (property.type == Directory || property.type == RegularFile) {
                def asFile = (language == Language.KOTLIN) ? ".asFile.absolutePath" : ".getAsFile().getAbsolutePath()"
                return "${accessor}${asFile}"
            }
            if (property.isReadOnly) {
                return accessor
            }
        }
        if (property.isList) {
            return "${accessor}.get()"
        }
        if (property.type == DirectoryProperty || property.type == RegularFileProperty) {
            def asFile = (language == Language.KOTLIN) ? ".asFile.absolutePath" : ".getAsFile().getAbsolutePath()"
            return "${accessor}.get()${asFile}"
        }
        if (property.type == Directory || property.type == RegularFile) {
            def asFile = (language == Language.KOTLIN) ? ".asFile.absolutePath" : ".getAsFile().getAbsolutePath()"
            return "${accessor}.get()${asFile}"
        }
        return "${accessor}.getOrNull()"
    }

    /**
     * Generates a property accessor expression appropriate for the language.
     * Java: {@code object.getFoo()}, Kotlin: {@code object.foo}.
     */
    static String propertyAccessor(String objectExpression, String propertyName, Language language) {
        if (language == Language.KOTLIN) {
            return "${objectExpression}.${propertyName}"
        }
        return "${objectExpression}.get${capitalize(propertyName)}()"
    }

    private static String printStatement(String objectType, String propertyName, String valueExpression, Language language) {
        return printCall("\"${objectType} ${propertyName} = \" + ${valueExpression}", language)
    }

    private static String printCall(String expression, Language language) {
        if (language == Language.KOTLIN) {
            return "println(${expression})"
        }
        return "System.out.println(${expression});"
    }

    private static String getPropertyReturnType(PropertyDeclaration property) {
        if (property.isReadOnly) {
            return property.type.simpleName
        }
        if (property.isJavaBean) {
            return property.type.simpleName
        }
        if (property.isList) {
            return "ListProperty<${property.type.simpleName}>"
        }
        if (property.type == DirectoryProperty) {
            return "DirectoryProperty"
        }
        if (property.type == RegularFileProperty) {
            return "RegularFileProperty"
        }
        return "Property<${property.type.simpleName}>"
    }

    private static boolean isFileType(Class type) {
        return type in [DirectoryProperty, RegularFileProperty, Directory, RegularFile]
    }

    private static String propertyFieldInitializer(PropertyDeclaration property) {
        if (property.type == Directory) {
            return "objects.directoryProperty()"
        }
        if (property.type == RegularFile) {
            return "objects.fileProperty()"
        }
        throw new IllegalStateException("Unsupported property field type: ${property.type}")
    }

    static String capitalize(String name) {
        return name.length() == 1 ? name.toUpperCase() : name[0].toUpperCase() + name[1..-1]
    }
}
