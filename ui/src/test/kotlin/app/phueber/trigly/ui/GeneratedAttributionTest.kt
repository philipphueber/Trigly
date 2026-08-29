package app.phueber.trigly.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the generated `shippedDependencies` this module actually compiles
 * against, not the licensee report directly: this is what `AttributionScreen`
 * reads, and the one thing a JVM test can see without a device.
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
        assertTrue(shippedDependencies.any { it.name == "androidx.core:core-ktx" })
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
                    "${dependency.name} looks test-only",
                    dependency.name.contains(needle, ignoreCase = true),
                )
            }
        }
    }
}
