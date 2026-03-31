/*
 * Copyright 2020 the original author or authors.
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

package gradlebuild.buildutils.tasks

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import org.gradle.util.GradleVersion
import org.gradle.util.internal.VersionNumber
import org.jetbrains.annotations.VisibleForTesting
import org.jsoup.Jsoup

/**
 * Fetch the latest AGP versions and write a properties file.
 * Never up-to-date, non-cacheable.
 *
 * AGP major versions are aligned with Gradle major versions.
 * IOW, AGP X.y officially only supports Gradle X.z.
 *
 * This task leverages that alignment to automatically select which
 * versions of AGP we should test.
 */
@UntrackedTask(because = "Not worth tracking")
abstract class UpdateAgpVersions : AbstractVersionsUpdateTask() {

    @get:Internal
    @get:Option(
        option = "include-pre-releases",
        description = "Include alpha and beta versions in the update. By default, only stable and RC versions are considered."
    )
    abstract val includePreReleases: Property<Boolean>

    @get:Internal
    abstract val currentGradleVersion: Property<GradleVersion>

    @get:Internal
    abstract val minimumSupported: Property<String>

    @get:Internal
    abstract val compatibilityDocFile: RegularFileProperty

    @TaskAction
    fun fetch() {
        val includePreReleases = includePreReleases.get()
        val existingProperties = readExistingProperties()
        val currentLatests = existingProperties.getProperty("latests")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val allVersions = fetchVersionsFromMavenMetadata(
            "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml"
        )
        val latests = selectVersionsFrom(
            currentGradleVersion.get(),
            minimumSupported.orNull?.let { VersionNumber.parse(it) },
            allVersions,
            includePreReleases,
            currentLatests
        )

        val buildToolsVersion = fetchBuildToolsVersion(
            "https://developer.android.com/tools/releases/build-tools"
        )

        val (nightlyBuildId, nightlyVersion) = if (includePreReleases) {
            val buildId = fetchNightlyBuildId("https://androidx.dev/studio/builds")
            val version = fetchNightlyVersion(
                "https://androidx.dev/studio/builds/$buildId/artifacts/artifacts/repository/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml"
            )
            buildId to version
        } else {
            existingProperties.getProperty("nightlyBuildId") to existingProperties.getProperty("nightlyVersion")
        }

        val aapt2Versions = fetchAapt2Versions(
            latests.toSet() + listOfNotNull(nightlyVersion),
            "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/maven-metadata.xml"
        )

        updateProperties {
            setProperty("latests", latests.joinToString(","))
            nightlyBuildId?.let { setProperty("nightlyBuildId", it) }
            nightlyVersion?.let { setProperty("nightlyVersion", it) }
            setProperty("aapt2Versions", aapt2Versions.joinToString(","))
            setProperty("buildToolsVersion", buildToolsVersion)
        }

        updateCompatibilityDoc(latests)
    }

    private
    fun updateCompatibilityDoc(latestAgpVersions: List<String>) =
        updateCompatibilityDoc(
            compatibilityDocFile,
            "Gradle is tested with Android Gradle Plugin",
            latestAgpVersions.firstBaseVersion,
            latestAgpVersions.last()
        )

    private
    val List<String>.firstBaseVersion: String
        get() = VersionNumber.parse(first()).minorBaseVersion

    private
    val VersionNumber.minorBaseVersion: String
        get() = "$major.$minor"

    private
    fun fetchAapt2Versions(agpVersions: Set<String>, mavenMetadataUrl: String): List<String> {
        return fetchVersionsFromMavenMetadata(mavenMetadataUrl)
            .filter { version -> version.substringBeforeLast("-") in agpVersions }
            .sortedBy { VersionNumber.parse(it) }
    }

    private
    fun fetchNightlyBuildId(buildListUrl: String): String =
        Jsoup.connect(buildListUrl)
            .get()
            .select("main li a")
            .first()!!
            .text()

    private
    fun fetchNightlyVersion(mavenMetadataUrl: String): String =
        fetchVersionsFromMavenMetadata(mavenMetadataUrl)
            .single()

    private
    fun fetchBuildToolsVersion(buildToolsUrl: String): String =
        Jsoup.connect(buildToolsUrl)
            .get()
            .select("section:has(> h3#kts)")
            .first()
            ?.text()
            ?.lines()
            ?.firstOrNull { it.contains("buildToolsVersion = ") }
            ?.substringAfter("buildToolsVersion = ")
            ?.trim('"', ' ')
            ?: error("Couldn't find buildToolsVersion on $buildToolsUrl")

    companion object {
        @VisibleForTesting
        @JvmStatic
        @JvmOverloads
        fun selectVersionsFrom(
            currentGradleVersion: GradleVersion,
            minimumSupported: VersionNumber?,
            allVersions: List<String>,
            includePreReleases: Boolean = true,
            currentLatests: List<String> = emptyList()
        ): List<String> {
            val parsedVersions = allVersions.map { VersionNumber.parse(it) }
            val allMinorLatests = parsedVersions.latestPerMinor()

            val candidates = if (includePreReleases) {
                allMinorLatests
            } else {
                parsedVersions.filter { it.isStableOrRc }.latestPerMinor()
            }

            val gradleMajor = VersionNumber.version(currentGradleVersion.majorVersion)
            val minimumFallback = when {

                allMinorLatests.any { it.major >= gradleMajor.major && it.isStable } -> {
                    validateMinimumSupported(minimumSupported, gradleMajor)
                    gradleMajor
                }

                else -> {
                    val gradlePreviousMajor = VersionNumber.version(gradleMajor.major - 1)
                    validateMinimumSupported(minimumSupported, gradlePreviousMajor)
                    allMinorLatests.last { it.major == gradlePreviousMajor.major && it.isStable }
                }
            }

            val effectiveMinimum = minimumSupported ?: minimumFallback
            val filteredCandidates = candidates.applyMinimumSupported(effectiveMinimum)

            if (includePreReleases) {
                return filteredCandidates.map { it.toString() }
            }

            // Merge with existing latests: preserve existing entries that are not superseded
            val existingByMinor = currentLatests.associate { version ->
                val parsed = VersionNumber.parse(version)
                VersionNumber.version(parsed.major, parsed.minor) to parsed
            }
            val candidateByMinor = filteredCandidates.associateBy { version ->
                VersionNumber.version(version.major, version.minor)
            }

            val allMinorSeriesInMaven = parsedVersions
                .map { VersionNumber.version(it.major, it.minor) }
                .toSet()

            val allMinors = (existingByMinor.keys + candidateByMinor.keys)
                .filter { it.baseVersion >= effectiveMinimum }
                .sorted()
                .distinct()

            return allMinors.mapNotNull { minor ->
                val candidate = candidateByMinor[minor]
                val existing = existingByMinor[minor]

                when {
                    // Stale: minor series no longer in Maven
                    minor !in allMinorSeriesInMaven -> null
                    // Candidate available and better than or equal to existing
                    candidate != null && (existing == null || candidate >= existing) -> candidate
                    // Existing is better (e.g. an alpha from a widened run, no stable/RC yet)
                    existing != null -> existing
                    else -> null
                }
            }.map { it.toString() }
        }

        private fun validateMinimumSupported(minimumSupported: VersionNumber?, minimumMinimum: VersionNumber) {
            if (minimumSupported != null) {
                require(minimumSupported >= minimumMinimum) {
                    "minimumSupported must be at least $minimumMinimum, was $minimumSupported"
                }
            }
        }

        private
        val VersionNumber.isStable: Boolean
            get() = qualifier == null

        private
        val VersionNumber.isStableOrRc: Boolean
            get() = qualifier == null || qualifier?.lowercase()?.startsWith("rc") == true

        private
        fun List<VersionNumber>.latestPerMinor(): List<VersionNumber> = sorted()
            .groupBy { VersionNumber.version(it.major, it.minor) }
            .map { (_, versions) -> versions.last() }

        private
        fun List<VersionNumber>.applyMinimumSupported(minimumSupported: VersionNumber?): List<VersionNumber> =
            when (minimumSupported) {
                null -> this
                else -> filter { it.baseVersion >= minimumSupported }
            }
    }
}
