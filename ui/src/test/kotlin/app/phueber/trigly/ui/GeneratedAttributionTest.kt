package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the generated `shippedDependencies` this module actually compiles
 * against, not the licensee report directly: this is what `AttributionScreen`
 * reads (by way of [groupIntoProjects]), and the one thing a JVM test can see
 * without a device.
 *
 * `generateAttributionList`, in `ui/build.gradle.kts`, must have already run
 * for this file to compile at all, since `shippedDependencies` does not exist
 * in checked-in source. That dependency, not an assertion here, is what
 * catches licensee failing to resolve or a report it cannot write.
 */
class GeneratedAttributionTest {

    @Test
    fun `the generated list is not empty`() {
        assertTrue(shippedDependencies.isNotEmpty())
    }

    @Test
    fun `a known dependency is present`() {
        assertTrue(shippedDependencies.any { it.groupId == "androidx.core" && it.artifactId == "core-ktx" })
    }

    /**
     * `:ui`'s release runtime classpath is the release APK's own dependency
     * set. A test-only artifact reaching this list would mean the wrong
     * classpath was read, not merely a wrong licence.
     */
    @Test
    fun `no test-only artifact is present`() {
        val testOnlyNeedles = listOf("junit", "espresso", "androidx.test", "coroutines-test", "turbine")
        shippedDependencies.forEach { dependency ->
            testOnlyNeedles.forEach { needle ->
                assertFalse(
                    "${dependency.coordinate} looks test-only",
                    dependency.coordinate.contains(needle, ignoreCase = true),
                )
            }
        }
    }

    /**
     * The five projects the owner chose to credit, per docs/architecture.md,
     * "Attribution": grouping 88 build outputs any finer would give Compose,
     * Room and Lifecycle their own lines, which was rejected on purpose.
     */
    @Test
    fun `the five expected projects are present`() {
        val names = shippedDependencies.groupIntoProjects().map { it.name }.toSet()
        assertEquals(
            setOf("AndroidX", "Kotlin", "Kotlin Coroutines", "Guava", "JetBrains Java Annotations"),
            names,
        )
    }

    /**
     * Four AndroidX artifacts carry a stale `http://source.android.com`
     * `scm.url` (`androidx.autofill:autofill`,
     * `androidx.concurrent:concurrent-futures`, `androidx.interpolator:interpolator`,
     * `androidx.versionedparcelable:versionedparcelable`). Grouping by
     * `groupId`, as `projectNameForGroup` in Attribution.kt does, must still
     * land every one of them under AndroidX rather than splitting them out.
     */
    @Test
    fun `AndroidX carries the artifacts with the stale scm url`() {
        val staleScmArtifacts = setOf(
            "androidx.autofill" to "autofill",
            "androidx.concurrent" to "concurrent-futures",
            "androidx.interpolator" to "interpolator",
            "androidx.versionedparcelable" to "versionedparcelable",
        )
        staleScmArtifacts.forEach { (groupId, artifactId) ->
            assertTrue(
                "$groupId:$artifactId is not on the real classpath; update this test's fixture list",
                shippedDependencies.any { it.groupId == groupId && it.artifactId == artifactId },
            )
        }

        val androidXCount = shippedDependencies.count { it.groupId == "androidx" || it.groupId.startsWith("androidx.") }
        val androidXProject = shippedDependencies.groupIntoProjects().single { it.name == "AndroidX" }
        assertEquals(androidXCount, androidXProject.artifactCount)
    }

    /**
     * Every artifact on the real classpath must map to one of the five known
     * projects. `groupIntoProjects` itself does not enforce this: an
     * unmapped `groupId` there falls back to showing under its own `groupId`
     * rather than crashing `AttributionHost`, see its KDoc in Attribution.kt.
     * This test is where the strictness lives instead, so a dependency
     * landing under a `groupId` nobody has mapped yet still fails the merge
     * gate rather than shipping a row nobody meant to add.
     */
    @Test
    fun `nothing is unmapped`() {
        val knownProjects = setOf("AndroidX", "Kotlin", "Kotlin Coroutines", "Guava", "JetBrains Java Annotations")
        val names = shippedDependencies.groupIntoProjects().map { it.name }

        names.forEach { name ->
            assertTrue(
                "\"$name\" is not a known project; add its groupId to projectNameForGroup in Attribution.kt",
                name in knownProjects,
            )
        }
    }

    /**
     * The soft-degrade behaviour `nothing is unmapped` relies on staying
     * soft: an unmapped `groupId` must still show up, under its own
     * `groupId`, with its artifact counted and its licence intact, rather
     * than vanishing from the page or crashing the screen that renders it.
     */
    @Test
    fun `an unmapped groupId is shown under its group id rather than vanishing`() {
        val withUnknownGroup = shippedDependencies +
            Attribution("com.example.unmapped", "some-artifact", "Apache-2.0", "https://example.com/unmapped")

        val fallbackProject = withUnknownGroup.groupIntoProjects().single { it.name == "com.example.unmapped" }

        assertEquals(1, fallbackProject.artifactCount)
        assertEquals("Apache-2.0", fallbackProject.license)
        assertEquals("https://example.com/unmapped", fallbackProject.url)
    }

    /**
     * `projectNameForGroup`'s own KDoc names the trap: four AndroidX artifacts
     * carry a stale `http://source.android.com` `scm.url`, left over from
     * before they moved out of AOSP into Jetpack. This is the same trap for
     * the URL a tap on AndroidX's row opens: it must be the real project page
     * the other 84 artifacts agree on, not whichever artifact `groupBy`
     * happens to list first, and not the stale one.
     */
    @Test
    fun `AndroidX links to its own page, not the stale source-android-com url`() {
        val androidXProject = shippedDependencies.groupIntoProjects().single { it.name == "AndroidX" }

        assertEquals("https://cs.android.com/androidx/platform/frameworks/support", androidXProject.url)
    }

    /**
     * A project with only one artifact has nothing to pick a "most common" URL
     * from; its one URL is simply the answer. Guava is the smallest of the
     * five known projects, so it is the cheapest real fixture for that case.
     */
    @Test
    fun `a single-artifact project links to its one artifact's url`() {
        val guavaProject = shippedDependencies.groupIntoProjects().single { it.name == "Guava" }

        assertEquals("https://github.com/google/guava", guavaProject.url)
    }
}
