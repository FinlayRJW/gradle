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

package org.gradle.performance.regression.ip

import org.gradle.performance.AbstractCrossVersionPerformanceTest
import org.gradle.performance.AndroidSyncPerformanceTestFixture
import org.gradle.performance.annotations.RunFor
import org.gradle.performance.annotations.Scenario
import org.gradle.profiler.mutations.ApplyAbiChangeToKotlinSourceFileMutator

import static org.gradle.performance.annotations.ScenarioType.PER_COMMIT
import static org.gradle.performance.results.OperatingSystem.LINUX

/**
 * Verifies android studio sync performance with IP enabled does not regress between versions.
 */
@RunFor(
    @Scenario(type = PER_COMMIT, operatingSystems = [LINUX], testProjects = ["android500Kts"])
)
class IsolatedProjectsAndroidSyncPerformanceRegressionTest extends AbstractCrossVersionPerformanceTest {

    private static String cold = "cold"
    private static String warm = "warm"

    private static int maxWorkers = 8

    def "build logic abi change with #daemon daemon"() {
        def runner = getRunner() // otherwise, IDEA thinks it's PerformanceTestRunner despite the override
        runner.useDaemon = daemon == warm
        // Use multiple warm-ups for cold scenario to warm-up Android Studio itself
        runner.warmUpRuns = daemon == warm ? 10 : 5
        runner.runs = 10

        AndroidSyncPerformanceTestFixture.configureStudio(runner)

        runner.args.addAll([
            "--no-scan",
            "-Dorg.gradle.caching=true",
            "-Dorg.gradle.workers.max=$maxWorkers",
            "-Dorg.gradle.unsafe.isolated-projects=true",
        ])

        runner.addBuildMutator { settings ->
            new ApplyAbiChangeToKotlinSourceFileMutator(new File(settings.projectDir, "build-logic/convention/src/main/kotlin/org/example/awesome/utils.kt"))
        }

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        daemon | _
        cold   | _
        warm   | _
    }

}
