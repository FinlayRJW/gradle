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

package org.gradle.features

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.executer.ExecutionFailure
import org.gradle.integtests.fixtures.polyglot.PolyglotDslTest
import org.gradle.integtests.fixtures.polyglot.PolyglotTestFixture
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.features.registration.TaskRegistrar
import org.gradle.internal.declarativedsl.DeclarativeTestUtils
import org.gradle.features.internal.TestScenarioFixture
import org.gradle.test.fixtures.dsl.GradleDsl
import org.gradle.test.fixtures.plugin.PluginBuilder

import static org.gradle.features.internal.builders.DefinitionBuilder.Shape.ABSTRACT_CLASS

@PolyglotDslTest
class ProjectFeatureSafetyIntegrationTest extends AbstractIntegrationSpec implements TestScenarioFixture, PolyglotTestFixture {
    def setup() {
        file("gradle.properties") << "org.gradle.kotlin.dsl.dcl=true"
    }

    def 'can declare and configure a custom project feature with an unsafe definition'() {
        given:
        def pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                    shape ABSTRACT_CLASS
                }
                plugin {
                    bindsFeatureTo(type)
                    unsafeDefinition()
                }
            }
        }
        pluginBuilder.addBuildScriptContent(pluginBuildScriptForJava)
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        succeeds(":printProjectTypeDefinitionConfiguration", ":printFeatureDefinitionConfiguration")

        then:
        outputContains("definition text = foo")
        outputContains("model text = foo")
    }

    def 'sensible error when definition is declared safe but is not an interface'() {
        given:
        def pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                    shape ABSTRACT_CLASS
                }
                plugin {
                    bindsFeatureTo(type)
                }
            }
        }
        pluginBuilder.addBuildScriptContent(pluginBuildScriptForJava)
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeDefinitionHasDescriptionOrCause(failure,
            "Project feature 'feature' has a definition type which was declared safe but has the following issues:\n" +
            "  - Project feature 'feature' has a definition with type 'FeatureDefinition' which was declared safe but is not an interface.\n" +
            "    \n" +
            "    Reason: Safe definition types must be an interface.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Refactor the type as an interface."
        )
    }

    def 'sensible error when definition is declared safe but has an injected service'() {
        given:
        def pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                    injectedService "objects", ObjectFactory
                }
                plugin {
                    bindsFeatureTo(type)
                }
            }
        }
        pluginBuilder.addBuildScriptContent(pluginBuildScriptForJava)
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeDefinitionHasDescriptionOrCause(failure,
            "Project feature 'feature' has a definition type which was declared safe but has the following issues:\n" +
            "  - The definition type has @Inject annotated property 'objects' in type 'FeatureDefinition'.\n" +
            "    \n" +
            "    Reason: Safe definition types cannot inject services.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Remove the @Inject annotation from the 'objects' property."
        )
    }

    def 'sensible error when definition is declared safe but has a nested property with an injected service'() {
        given:
        def pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        injectedService "objects", ObjectFactory
                        property "buzz", String
                    }
                }
                plugin {
                    bindsFeatureTo(type)
                }
            }
        }
        pluginBuilder.addBuildScriptContent(pluginBuildScriptForJava)
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeDefinitionHasDescriptionOrCause(failure,
            "Project feature 'feature' has a definition type which was declared safe but has the following issues:\n" +
            "  - The definition type has @Inject annotated property 'objects' in type 'Fizz'.\n" +
            "    \n" +
            "    Reason: Safe definition types cannot inject services.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Remove the @Inject annotation from the 'objects' property."
        )
    }

    def 'sensible error when definition is declared safe but has multiple properties with an injected service'() {
        given:
        def pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    injectedService "objects", ObjectFactory
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        injectedService "objects", ObjectFactory
                        property "buzz", String
                    }
                }
                plugin {
                    bindsFeatureTo(type)
                }
            }
        }
        pluginBuilder.addBuildScriptContent(pluginBuildScriptForJava)
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeDefinitionHasDescriptionOrCause(failure,
            "Project feature 'feature' has a definition type which was declared safe but has the following issues:\n" +
            "  - The definition type has @Inject annotated property 'objects' in type 'Fizz'.\n" +
            "    \n" +
            "    Reason: Safe definition types cannot inject services.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Remove the @Inject annotation from the 'objects' property.\n" +
            "  - The definition type has @Inject annotated property 'objects' in type 'FeatureDefinition'.\n" +
            "    \n" +
            "    Reason: Safe definition types cannot inject services.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Remove the @Inject annotation from the 'objects' property."
        )
    }

    def 'sensible error when definition is declared safe but inherits an injected service'() {
        given:
        def pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                    parentDefinition {
                        injectedService "objects", ObjectFactory
                    }
                }
                plugin {
                    bindsFeatureTo(type)
                }
            }
        }
        pluginBuilder.addBuildScriptContent(pluginBuildScriptForJava)
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeDefinitionHasDescriptionOrCause(failure,
            "Project feature 'feature' has a definition type which was declared safe but has the following issues:\n" +
            "  - The definition type has @Inject annotated property 'objects' in type 'FeatureDefinition'.\n" +
            "    \n" +
            "    Reason: Safe definition types cannot inject services.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Remove the @Inject annotation from the 'objects' property."
        )
    }

    def 'sensible error when definition is declared safe but has several different errors'() {
        given:
        def pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                    shape ABSTRACT_CLASS
                    injectedService "objects", ObjectFactory
                }
                plugin {
                    bindsFeatureTo(type)
                }
            }
        }
        pluginBuilder.addBuildScriptContent(pluginBuildScriptForJava)
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeDefinitionHasDescriptionOrCause(failure,
            "Project feature 'feature' has a definition type which was declared safe but has the following issues:\n" +
            "  - Project feature 'feature' has a definition with type 'FeatureDefinition' which was declared safe but is not an interface.\n" +
            "    \n" +
            "    Reason: Safe definition types must be an interface.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Refactor the type as an interface.\n" +
            "  - The definition type has @Inject annotated property 'objects' in type 'FeatureDefinition'.\n" +
            "    \n" +
            "    Reason: Safe definition types cannot inject services.\n" +
            "    \n" +
            "    Possible solutions:\n" +
            "      1. Mark the definition as unsafe.\n" +
            "      2. Remove the @Inject annotation from the 'objects' property."
        )
    }

    def 'can declare and configure a custom project feature with an unsafe apply action'() {
        given:
        PluginBuilder pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                }
                plugin {
                    bindsFeatureTo(type)
                    applyAction {
                        injectedService "project", Project
                    }
                    unsafeApplyAction()
                }
            }
        }
        pluginBuilder.addBuildScriptContent pluginBuildScriptForJava
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        run(":printProjectTypeDefinitionConfiguration",":printFeatureDefinitionConfiguration")

        then:
        outputContains("definition text = foo")
        outputContains("model text = foo")

        and:
        outputContains("Applying TestProjectTypeImplPlugin")
        outputContains("Binding TestProjectTypeDefinition")
        outputContains("Binding FeatureDefinition")
    }

    def 'sensible error when project feature with an unsafe apply action is declared safe'() {
        given:
        PluginBuilder pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                }
                plugin {
                    bindsFeatureTo(type)
                    applyAction {
                        injectedService "project", Project
                    }
                }
            }
        }
        pluginBuilder.addBuildScriptContent pluginBuildScriptForJava
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printProjectTypeDefinitionConfiguration",":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeApplyActionHasDescriptionOrCause(failure,
            "Project feature 'feature' has a safe apply action that attempts to inject an unsafe service with type 'org.gradle.api.Project'.\n" +
            "\n" +
            "Reason: Only the following services are available in safe apply actions:\n" +
            "  - TaskRegistrar\n" +
            "  - ProjectFeatureLayout\n" +
            "  - ConfigurationRegistrar\n" +
            "  - ObjectFactory\n" +
            "  - ProviderFactory\n" +
            "  - DependencyFactory.\n" +
            "\n" +
            "Possible solutions:\n" +
            "  1. Mark the apply action as unsafe.\n" +
            "  2. Remove the 'org.gradle.api.Project' injection from the apply action."
        )
    }

    def 'sensible error when project feature with an unsafe apply action attempts to use an unknown service'() {
        given:
        PluginBuilder pluginBuilder = testScenario {
            def type = projectType("testProjectType") { }
            projectFeature("feature") {
                definition {
                    property "text", String
                    buildModel {
                        property "text", String
                    }
                    property("fizz", "Fizz") {
                        property "buzz", String
                    }
                }
                plugin {
                    bindsFeatureTo(type)
                    applyAction {
                        injectedService "unknown", TaskRegistrar
                    }
                    unsafeApplyAction()
                }
            }
        }
        pluginBuilder.addBuildScriptContent pluginBuildScriptForJava
        pluginBuilder.prepareToExecute()

        settingsFile() << pluginsFromIncludedBuild

        buildFile() << """
            testProjectType {
                feature {
                    text = "foo"

                    fizz {
                        buzz = "baz"
                    }
                }
            }
        """ << DeclarativeTestUtils.nonDeclarativeSuffixForKotlinDsl

        when:
        fails(":printProjectTypeDefinitionConfiguration",":printFeatureDefinitionConfiguration")

        then:
        assertUnsafeApplyActionHasDescriptionOrCause(failure,
            "Project feature 'feature' has an apply action that attempts to inject an unknown service with type 'org.gradle.test.FeatureImplPlugin\$ApplyAction\$UnknownService'.\n" +
            "\n" +
            "Reason: Services of type org.gradle.test.FeatureImplPlugin\$ApplyAction\$UnknownService are not available for injection into project feature apply actions.\n" +
            "\n" +
            "Possible solution: Remove the 'org.gradle.test.FeatureImplPlugin\$ApplyAction\$UnknownService' injection from the apply action."
        )
    }

    private String getPluginBuildScriptForJava() {
        return """

            tasks.withType(JavaCompile).configureEach {
                sourceCompatibility = "1.8"
                targetCompatibility = "1.8"
            }
        """
    }

    void assertUnsafeDefinitionHasDescriptionOrCause(ExecutionFailure failure, String expectedMessage) {
        if (currentDsl() == GradleDsl.DECLARATIVE) {
            failure.assertHasDescription(expectedMessage)
        } else {
            failure.assertHasCause(expectedMessage)
        }
    }

    void assertUnsafeApplyActionHasDescriptionOrCause(ExecutionFailure failure, String expectedMessage) {
        if (currentDsl() == GradleDsl.KOTLIN) {
            failure.assertHasDescription(expectedMessage)
        } else {
            failure.assertHasCause(expectedMessage)
        }
    }
}
