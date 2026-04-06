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

package org.gradle.features.internal

import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.features.internal.builders.DefinitionBuilder
import org.gradle.features.internal.builders.Language
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.plugin.PluginBuilder
import spock.lang.Specification
import spock.lang.TempDir

class DefinitionBuilderTest extends Specification {
    @TempDir
    File tempDirFile

    TestFile getTempDir() { new TestFile(tempDirFile) }

    def "generates interface definition with properties"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)
        builder.property("foo", "Foo") {
            implementsDefinition("FooBuildModel") {
                property "barProcessed", String
            }
            property "bar", String
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text

        then:
        content.contains("public interface TestProjectTypeDefinition extends Definition<TestProjectTypeDefinition.ModelType>")
        content.contains("Property<String> getId();")
        content.contains("Foo getFoo();")
        content.contains("@HiddenInDefinition")
        content.contains("default void foo(Action<? super Foo> action)")
        content.contains("interface Foo extends Definition<FooBuildModel>")
        content.contains("Property<String> getBar();")
        content.contains("interface FooBuildModel extends BuildModel")
        content.contains("Property<String> getBarProcessed();")
        content.contains("interface ModelType extends BuildModel")
    }

    def "generates abstract class definition"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.shape(DefinitionBuilder.Shape.ABSTRACT_CLASS)
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)
        builder.property("foo", "Foo") {
            implementsDefinition("FooBuildModel") {
                property "barProcessed", String
            }
            property "bar", String
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text

        then:
        content.contains("public abstract class TestProjectTypeDefinition implements Definition<TestProjectTypeDefinition.ModelType>")
        content.contains("private final Foo foo;")
        content.contains("private boolean isFooConfigured = false;")
        content.contains("@Inject")
        content.contains("public TestProjectTypeDefinition(ObjectFactory objects)")
        content.contains("objects.newInstance(Foo.class)")
        content.contains("public abstract Property<String> getId();")
        content.contains("public Foo getFoo()")
        content.contains("isFooConfigured = true;")
        content.contains("public abstract static class Foo")
    }

    def "generates definition with injected services"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.injectedService("objects", ObjectFactory)
        builder.property("id", String)
        builder.property("foo", "Foo") {
            property "bar", String
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text

        then:
        content.contains("@Inject")
        content.contains("ObjectFactory getObjects();")
    }

    def "generates definition with nested injected services"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)
        builder.property("foo", "Foo") {
            injectedService "objects", ObjectFactory
            property "bar", String
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text

        then:
        content.contains("interface Foo")
        content.contains("ObjectFactory getObjects();")
        content.contains("Property<String> getBar();")
    }

    def "generates definition with NDOC property"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)
        builder.ndoc("foos", "Foo") {
            property "x", Integer
            property "y", Integer
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text

        then:
        content.contains("NamedDomainObjectContainer<Foo> getFoos();")
        content.contains("public abstract static class Foo implements Named")
    }

    def "generates definition with out-projected NDOC"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)
        builder.ndoc("foos", "Foo") {
            outProjected()
            property "x", Integer
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text

        then:
        content.contains("NamedDomainObjectContainer<? extends Foo> getOutFoos()")
    }

    def "generates definition with NDOC containing definitions"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)
        builder.ndoc("sources", "Source") {
            implementsDefinition("SourceModel") {
                property "sourceDir", String
            }
            property "sourceDir", String
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text

        then:
        content.contains("interface Source extends Definition<Source.SourceModel>, Named")
        content.contains("interface SourceModel extends BuildModel")
    }

    def "generates definition with no build model"() {
        given:
        def builder = new DefinitionBuilder("FeatureDefinition")
        builder.noBuildModel()
        builder.property("text", String)

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/FeatureDefinition.java").text

        then:
        content.contains("public interface FeatureDefinition extends Definition<BuildModel.None>")
        !content.contains("interface ModelType")
        !content.contains("interface FeatureModel")
    }

    def "generates definition with separate implementation type"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.implementationType("TestProjectTypeDefinitionImpl")
        builder.property("id", String)
        builder.property("foo", "Foo") {
            property "bar", String
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def publicContent = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text
        def implContent = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinitionImpl.java").text

        then:
        publicContent.contains("public interface TestProjectTypeDefinition")
        implContent.contains("public abstract class TestProjectTypeDefinitionImpl implements TestProjectTypeDefinition")
        implContent.contains("Property<String> getNonPublic();")
    }

    def "generates definition with parent definition"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)
        builder.property("foo", "Foo") {
            property "bar", String
        }
        builder.parentDefinition {
            injectedService "objects", ObjectFactory
        }

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def childContent = new File(tempDir, "src/main/java/org/gradle/test/TestProjectTypeDefinition.java").text
        def parentContent = new File(tempDir, "src/main/java/org/gradle/test/ParentTestProjectTypeDefinition.java").text

        then:
        childContent.contains("public interface TestProjectTypeDefinition extends ParentTestProjectTypeDefinition")
        parentContent.contains("ObjectFactory getObjects();")
    }

    def "generates definition with build model having separate impl type"() {
        given:
        def builder = new DefinitionBuilder("FeatureDefinition")
        builder.buildModel("FeatureModel") {
            property "text", String
            implementationType "FeatureModelImpl"
        }
        builder.property("text", String)

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/FeatureDefinition.java").text

        then:
        content.contains("interface FeatureModel extends BuildModel")
        content.contains("interface FeatureModelImpl extends FeatureModel")
    }

    def "generates definition with DirectoryProperty"() {
        given:
        def builder = new DefinitionBuilder("FeatureDefinition")
        builder.buildModel("FeatureModel") {
            property "text", String
            property "dir", DirectoryProperty
        }
        builder.property("text", String)

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/FeatureDefinition.java").text

        then:
        content.contains("DirectoryProperty getDir();")
    }

    def "generates definition with read-only property"() {
        given:
        def builder = new DefinitionBuilder("FeatureDefinition")
        builder.buildModel("FeatureModel") { property "text", String }
        builder.readOnlyProperty("dir", Directory)

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/FeatureDefinition.java").text

        then:
        content.contains("Directory getDir();")
        !content.contains("Property<Directory>")
    }

    def "generates definition with Java Bean-style properties"() {
        given:
        def builder = new DefinitionBuilder("FeatureDefinition")
        builder.buildModel("FeatureModel") { property "text", String }
        builder.javaBeanProperty("dir", Directory)

        when:
        def pluginBuilder = new PluginBuilder(tempDir)
        builder.build(pluginBuilder)
        def content = new File(tempDir, "src/main/java/org/gradle/test/FeatureDefinition.java").text

        then:
        content.contains("Directory getDir();")
        content.contains("void setDir(Directory value);")
    }

    def "provides correct fully qualified class names"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }

        expect:
        builder.fullyQualifiedPublicTypeClassName == "org.gradle.test.TestProjectTypeDefinition"
        builder.fullyQualifiedBuildModelClassName == "org.gradle.test.TestProjectTypeDefinition.ModelType"
        builder.buildModelFullPublicClassName == "TestProjectTypeDefinition.ModelType"
    }

    def "provides BuildModel.None when no build model"() {
        given:
        def builder = new DefinitionBuilder("FeatureDefinition")
        builder.noBuildModel()

        expect:
        builder.fullyQualifiedBuildModelClassName == null
        builder.buildModelFullPublicClassName == "org.gradle.features.binding.BuildModel.None"
    }

    def "generates build model mapping from properties"() {
        given:
        def builder = new DefinitionBuilder("TestProjectTypeDefinition")
        builder.buildModel("ModelType") { property "id", String }
        builder.property("id", String)

        expect:
        builder.getBuildModelMapping(Language.JAVA).contains("model.getId().set(definition.getId());")
        builder.getBuildModelMapping(Language.KOTLIN).contains("model.id.set(definition.id)")
        !builder.getBuildModelMapping(Language.KOTLIN).contains(";")
    }

    def "uses custom build model mapping when provided"() {
        given:
        def builder = new DefinitionBuilder("FeatureDefinition")
        builder.buildModel("FeatureModel") { property "text", String }
        builder.property("text", String)
        builder.buildModel {
            mapping("model.getText().set(parent.getText().map(text -> text + \" \" + definition.getText().get()));")
        }

        expect:
        builder.getBuildModelMapping(Language.JAVA).contains("model.getText().set(parent.getText()")
    }
}
