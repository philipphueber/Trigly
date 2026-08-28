package app.phueber.trigly.ui

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Turns `android:allowBackup="true"` from a fixed manifest attribute into
 * something a user can actually decide, without touching the attribute
 * itself.
 *
 * `allowBackup` cannot become a runtime setting on its own. It is read when
 * the APK is built. A custom `BackupAgent` is what the platform calls
 * instead of writing the app's data directly, so it is the one hook that can
 * still say no. `android:fullBackupOnly="true"` in the manifest is the other
 * half: it tells the system to keep using the full-backup contract, this
 * class's [onFullBackup], rather than switching to the older key/value
 * contract just because a custom agent exists. [onBackup] and [onRestore] are
 * only implemented because `BackupAgent` declares them abstract; with
 * `fullBackupOnly` set the system never calls either one, for backup or for
 * restore.
 *
 * A full-backup restore does not call this agent at all. The platform
 * unpacks files straight into the data directory, the same as if nobody had
 * customised anything. So the only decision this class makes is which files
 * reach the *next* backup pass, and [onFullBackup] is the whole class.
 */
class TriglyBackupAgent : BackupAgent() {

    private val settings by lazy { backupSettings(this) }

    /**
     * Decides what, if anything, this pass writes.
     *
     * `getTransportFlags()` and `FLAG_DEVICE_TO_DEVICE_TRANSFER` are both API
     * 28; `minSdk` here is 26, so a device below 28 always passes `false` for
     * [shouldWriteFullBackup]'s second argument, which is the same as saying
     * "cannot tell this apart from a cloud upload, so the switch alone
     * decides". That is the honest answer when the platform will not say
     * more.
     *
     * `super.onFullBackup(data)` is the platform's own default: back up the
     * whole data directory, exactly what ran before this class existed, since
     * there is still no `fullBackupContent` or `dataExtractionRules`
     * narrowing it. Calling it is how "backup on" keeps meaning what it has
     * always meant.
     *
     * The setting itself is written even while everything else is withheld.
     * A restore never calls this class, see the class KDoc, so an "off"
     * choice can only survive into whatever the *next* device finds already
     * sitting in its data directory after a restore. Withholding the setting
     * along with the database would mean a phase with nothing backed up at
     * all looks, to a later restore, identical to an app that was never
     * backed up. That reads back as the on-by-default answer, flipping a
     * deliberate "no" back to "yes" the moment it is least visible. Writing
     * only this one small file, which names no rule and carries no token,
     * costs nothing and keeps that from happening.
     */
    override fun onFullBackup(data: FullBackupDataOutput) {
        val deviceToDeviceTransfer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0

        if (shouldWriteFullBackup(settings.cloudBackupEnabled(), deviceToDeviceTransfer)) {
            super.onFullBackup(data)
        } else {
            fullBackupFile(File(sharedPreferencesFilePath(dataDir.path, BACKUP_PREFS_NAME)), data)
        }
    }

    // Never called: fullBackupOnly="true" on the manifest's <application>
    // routes both backup and restore through onFullBackup instead. Required
    // anyway, because BackupAgent declares both abstract.
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput,
        appVersionCode: Int,
        newState: ParcelFileDescriptor,
    ) = Unit
}
