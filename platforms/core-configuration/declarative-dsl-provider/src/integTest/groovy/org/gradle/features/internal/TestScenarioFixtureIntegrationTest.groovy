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

import org.gradle.features.internal.builders.DefinitionBuilder
import org.gradle.features.internal.builders.Language
import org.gradle.features.registration.TaskRegistrar
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.versions.KotlinGradlePluginVersions
import org.gradle.test.precondition.Requires
import org.gradle.test.preconditions.UnitTestPreconditions

/**
 * Integration tests that verify the {@link TestScenarioFixture} DSL generates
 * compilable plugin source code for various scenario configurations.
 *
 * <p>Each test generates plugin source files in both Java and Kotlin, includes the
 * plugin build, and verifies the expected class files exist after compilation.</p>
 */
@Requires(UnitTestPreconditions.Jdk23OrEarlier) // Kotlin does not support JDK 24 yet
class TestScenarioFixtureIntegrationTest extends AbstractIntegrationSpec implements TestScenarioFixture {

    def "simple project type compiles (#language)"() {
        given:
        testScenario {
            delegate.language(sourceLanguage)
            projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                }
            }
        }.tap { configureForLanguage(it, sourceLanguage) }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("TestProjectTypeImplPlugin", sourceLanguage).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "abstract class definition compiles (#language)"() {
        given:
        testScenario {
            delegate.language(sourceLanguage)
            projectType("testProjectType") {
                definition {
                    shape DefinitionBuilder.Shape.ABSTRACT_CLASS
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                    property("foo", "Foo") {
                        property "bar", String
                    }
                }
            }
        }.tap { configureForLanguage(it, sourceLanguage) }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("TestProjectTypeImplPlugin", sourceLanguage).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "definition with multiple properties compiles (#language)"() {
        given:
        testScenario {
            delegate.language(sourceLanguage)
            projectType("testProjectType") {
                definition {
                    buildModel("ModelType") {
                        property "id", String
                        property "name", String
                    }
                    property "id", String
                    property "name", String
                }
            }
        }.tap { configureForLanguage(it, sourceLanguage) }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "separate implementation type compiles (#language)"() {
        given:
        testScenario {
            delegate.language(sourceLanguage)
            projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                    property("foo", "Foo") { property "bar", String }
                    implementationType("TestProjectTypeDefinitionImpl")
                }
            }
        }.tap { configureForLanguage(it, sourceLanguage) }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("TestProjectTypeDefinitionImpl", Language.JAVA).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "project type + feature compiles (#language)"() {
        given:
        def pluginBuilder = testScenario {
            delegate.language(sourceLanguage)
            def type = projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                }
            }
            projectFeature("feature") {
                definition {
                    buildModel("FeatureModel") { property "text", String }
                    property "text", String
                }
                plugin {
                    bindsFeatureTo(type)
                }
            }
        }
        configureForLanguage(pluginBuilder, sourceLanguage)
        pluginBuilder.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("FeatureDefinition", Language.JAVA).exists()
        pluginClassFile("TestProjectTypeImplPlugin", sourceLanguage).exists()
        pluginClassFile("FeatureImplPlugin", sourceLanguage).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "feature binding to build model compiles (#language)"() {
        given:
        def pluginBuilder = testScenario {
            delegate.language(sourceLanguage)
            def type = projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                }
            }
            projectFeature("feature") {
                definition {
                    buildModel("FeatureModel") { property "text", String }
                    property "text", String
                }
                plugin {
                    bindToBuildModel()
                    bindsFeatureTo(type.definition.fullyQualifiedBuildModelClassName)
                }
            }
        }
        configureForLanguage(pluginBuilder, sourceLanguage)
        pluginBuilder.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("FeatureImplPlugin", sourceLanguage).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "multiple project types compile (#language)"() {
        given:
        testScenario {
            delegate.language(sourceLanguage)
            projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                }
            }
            projectType("anotherProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                }
            }
        }.tap { configureForLanguage(it, sourceLanguage) }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("AnotherProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("TestProjectTypeImplPlugin", sourceLanguage).exists()
        pluginClassFile("AnotherProjectTypeImplPlugin", sourceLanguage).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "feature with no build model compiles (#language)"() {
        given:
        def pluginBuilder = testScenario {
            delegate.language(sourceLanguage)
            def type = projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                }
            }
            projectFeature("feature") {
                definition {
                    noBuildModel()
                    property "text", String
                }
                plugin {
                    bindsFeatureTo(type)
                    noBuildModel()
                }
            }
        }
        configureForLanguage(pluginBuilder, sourceLanguage)
        pluginBuilder.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("FeatureDefinition", Language.JAVA).exists()
        pluginClassFile("FeatureImplPlugin", sourceLanguage).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "NDOC containing definitions compiles"() {
        given:
        testScenario {
            projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                    ndoc("sources", "Source") {
                        implementsDefinition("SourceModel") {
                            property "sourceDir", String
                        }
                        property "sourceDir", String
                    }
                }
                plugin {
                    providesBuildModelImpl("DefaultSourceModel", "Source.SourceModel") {
                        property "processedDir", String
                    }
                    unsafeApplyAction()
                }
            }
        }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("TestProjectTypeImplPlugin", Language.JAVA).exists()
    }

    def "parent definition compiles (#language)"() {
        given:
        testScenario {
            delegate.language(sourceLanguage)
            projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                    parentDefinition {}
                }
            }
        }.tap { configureForLanguage(it, sourceLanguage) }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeDefinition", Language.JAVA).exists()
        pluginClassFile("ParentTestProjectTypeDefinition", Language.JAVA).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    def "eager value reads compiles"() {
        given:
        testScenario {
            projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                    property("foo", "Foo") { property "bar", String }
                }
                plugin {
                    applyAction {
                        injectedService "taskRegistrar", TaskRegistrar
                        eagerlyReadDefinitionValues()
                    }
                    unsafeApplyAction()
                }
            }
        }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeImplPlugin", Language.JAVA).exists()
    }

    def "settings defaults compiles (#language)"() {
        given:
        testScenario {
            delegate.language(sourceLanguage)
            def type = projectType("testProjectType") {
                definition {
                    buildModel("ModelType") { property "id", String }
                    property "id", String
                }
            }
            settings {
                defaults {
                    defaultFor(type) {
                        property "id", "from-defaults"
                    }
                }
            }
        }.tap { configureForLanguage(it, sourceLanguage) }.prepareToExecute()
        includePluginBuild()

        when:
        succeeds(":help")

        then:
        pluginClassFile("TestProjectTypeImplPlugin", sourceLanguage).exists()

        where:
        sourceLanguage << [Language.JAVA, Language.KOTLIN]
        language = sourceLanguage.name().toLowerCase()
    }

    private void includePluginBuild() {
        settingsFile << pluginsFromIncludedBuild
    }

    private void configureForLanguage(pluginBuilder, Language language) {
        if (language == Language.KOTLIN) {
            pluginBuilder.applyBuildScriptPlugin("org.jetbrains.kotlin.jvm", new KotlinGradlePluginVersions().getLatestStableOrRC())
            pluginBuilder.addBuildScriptContent pluginBuildScriptForKotlin
        } else {
            pluginBuilder.addBuildScriptContent pluginBuildScriptForJava
        }
    }

    private File pluginClassFile(String className, Language language) {
        def sourceSet = (language == Language.KOTLIN) ? "kotlin" : "java"
        return file("plugins/build/classes/${sourceSet}/main/org/gradle/test/${className}.class")
    }
}
