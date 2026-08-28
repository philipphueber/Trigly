package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [TriglyBackupAgent] is a thin shim over [shouldWriteFullBackup], so this is
 * where the actual decision is checked: on by default, off once the user
 * says so, and a device-to-device transfer running either way.
 */
class BackupPolicyTest {

    @Test
    fun `backup runs when the user has not turned it off`() {
        assertEquals(
            true,
            shouldWriteFullBackup(cloudBackupEnabled = true, isDeviceToDeviceTransfer = false),
        )
    }

    @Test
    fun `backup is withheld once the user turns it off`() {
        assertFalse(
            shouldWriteFullBackup(cloudBackupEnabled = false, isDeviceToDeviceTransfer = false),
        )
    }

    @Test
    fun `a device-to-device transfer runs with the switch off`() {
        // It does not put anything in an account: it moves data straight onto
        // the user's own next phone, which is not the exposure the switch
        // guards against.
        assertEquals(
            true,
            shouldWriteFullBackup(cloudBackupEnabled = false, isDeviceToDeviceTransfer = true),
        )
    }

    @Test
    fun `a device-to-device transfer running is not conditional on the switch`() {
        assertEquals(
            true,
            shouldWriteFullBackup(cloudBackupEnabled = true, isDeviceToDeviceTransfer = true),
        )
    }

    @Test
    fun `the setting's own file always sits under shared_prefs`() {
        assertEquals(
            "/data/data/app.phueber.trigly/shared_prefs/backup_settings.xml",
            sharedPreferencesFilePath("/data/data/app.phueber.trigly", "backup_settings"),
        )
    }
}
