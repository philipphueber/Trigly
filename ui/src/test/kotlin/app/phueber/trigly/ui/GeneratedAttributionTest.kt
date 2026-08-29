package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
     * Every artifact on the real classpath must map to a project.
     * `groupIntoProjects` throws on a `groupId` nobody claims rather than
     * dropping the artifact or mislabelling it, and this is what turns that
     * throw into a merge-gate failure instead of a runtime one: see
     * `groupIntoProjects`'s KDoc in Attribution.kt.
     */
    @Test
    fun `nothing is unmapped`() {
        shippedDependencies.groupIntoProjects()
    }

    @Test
    fun `an unmapped groupId throws rather than vanishing or being mislabelled`() {
        val withUnknownGroup = shippedDependencies + Attribution("com.example.unmapped", "some-artifact", "Apache-2.0")

        assertThrows(IllegalStateException::class.java) {
            withUnknownGroup.groupIntoProjects()
        }
    }
}
