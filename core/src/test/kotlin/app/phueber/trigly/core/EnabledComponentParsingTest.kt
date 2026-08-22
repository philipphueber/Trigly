package app.phueber.trigly.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "is my service enabled" secure settings are colon-separated flattened
 * component names. Getting this wrong means telling the user their access is
 * missing when it is not, or the reverse — both worse than a crash, because
 * both look like a working app.
 */
class EnabledComponentParsingTest {

    private val us = "app.phueber.trigly"

    @Test
    fun `finds our component among others`() {
        val setting =
            "com.other.app/.TheirService:app.phueber.trigly/.notification.TriglyNotificationListenerService"

        assertTrue(isPackageEnabledIn(setting, us))
    }

    @Test
    fun `finds our component when it is the only one`() {
        assertTrue(isPackageEnabledIn("app.phueber.trigly/.Service", us))
    }

    @Test
    fun `absent means not enabled`() {
        assertFalse(isPackageEnabledIn("com.other.app/.TheirService", us))
    }

    @Test
    fun `null and blank settings mean not enabled`() {
        assertFalse(isPackageEnabledIn(null, us))
        assertFalse(isPackageEnabledIn("", us))
        assertFalse(isPackageEnabledIn("   ", us))
    }

    @Test
    fun `trailing and repeated separators are tolerated`() {
        assertTrue(isPackageEnabledIn("app.phueber.trigly/.Service:", us))
        assertTrue(isPackageEnabledIn("::app.phueber.trigly/.Service::", us))
    }

    @Test
    fun `another package that merely starts with ours does not count`() {
        // The bug a naive `contains` check would have.
        assertFalse(isPackageEnabledIn("app.phueber.triglyevil/.Service", us))
        assertFalse(isPackageEnabledIn("com.evil.app.phueber.trigly/.Service", us))
    }
}
