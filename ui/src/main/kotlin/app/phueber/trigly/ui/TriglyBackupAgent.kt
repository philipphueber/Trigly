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
 * A full-backup restore does call this class, once per file, through
 * `onRestoreFile(ParcelFileDescriptor, long, File, int, long, long)`, which
 * this class does not override. `BackupAgent` declares that method
 * non-abstract, so the platform default runs instead, and it unpacks the
 * file straight into the data directory, the same as if nobody had
 * customised anything. So the only decision this class makes is which files
 * reach the *next* backup pass, and [onFullBackup] is the whole class.
 *
 * `ColorSchemeSettings` needs no line in this class. It lives in its own
 * `SharedPreferences` file, [COLOR_SCHEME_PREFS_NAME], so the plain
 * `super.onFullBackup(data)` path above already carries it along with the
 * rest of the data directory when backup is on, and the explicit
 * [fullBackupFile] call below - which names only [BACKUP_PREFS_NAME] - already
 * leaves it out when backup is off. Nothing here had to change for a second
 * setting to get the same answer the first one does.
 *
 * That decision leans on one assumption that is not public API: see
 * `sharedPreferencesFilePath`. If a future platform ever moved that file,
 * `fullBackupFile` would find nothing to read there. Android's own native
 * code returns a plain error code for a missing file, and `fullBackupFile`
 * does not check it: nothing is thrown, and nothing is written for that one
 * entry. So the failure this assumption can cause is narrow: the "off"
 * choice stops travelling into the next backup, and nothing more. It is not
 * a crash, and it is not a backup that writes more than the setting allows.
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
     * This class does not act on a restore: see the class KDoc for why a
     * restore runs the platform default instead. So an "off" choice can only
     * survive by way of what the *previous* backup pass wrote, since that is
     * what a restore copies back verbatim. Withholding the setting along
     * with the database would mean a phase with nothing backed up at all
     * looks, to a later restore, identical to an app that was never backed
     * up. That reads back as the on-by-default answer, flipping a deliberate
     * "no" back to "yes" the moment it is least visible. Writing only this
     * one small file, which names no rule and carries no token, costs
     * nothing and keeps that from happening.
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
    // routes backup through onFullBackup and restore through onRestoreFile
    // instead, per the class KDoc. Required anyway, because BackupAgent
    // declares both abstract.
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
