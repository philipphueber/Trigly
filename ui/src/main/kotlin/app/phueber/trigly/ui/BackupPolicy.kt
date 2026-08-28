package app.phueber.trigly.ui

/**
 * The decision [TriglyBackupAgent] exists to make, kept apart from it so a JVM
 * test can check the decision without a device. The agent stays a thin shim:
 * it reads the setting, asks the platform whether this pass is a
 * device-to-device transfer, and hands both booleans here.
 *
 * [cloudBackupEnabled] is the user's own switch on the settings screen, on by
 * default. [isDeviceToDeviceTransfer] is true only when the platform told the
 * agent this pass moves data straight onto the phone the user is switching to
 * (`FullBackupDataOutput.getTransportFlags()` and
 * `BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER`, both API 28 and later). A
 * caller on an older platform cannot ask that question and always passes
 * false. That makes the switch alone decide, the same answer this function
 * gave before device-to-device transfer existed.
 *
 * A device-to-device transfer runs even with the switch off. It does not put
 * the rules, the saved values, or a webhook token into anybody's account: it
 * moves them straight onto the user's own next phone, which is the
 * convenience case the switch was never meant to block. A cloud upload is
 * what the switch blocks, and a device-to-device transfer is not one.
 */
fun shouldWriteFullBackup(cloudBackupEnabled: Boolean, isDeviceToDeviceTransfer: Boolean): Boolean =
    cloudBackupEnabled || isDeviceToDeviceTransfer

/**
 * Where a `SharedPreferences` file sits under the app's data directory.
 *
 * There is no public API that hands this back. `Context.getSharedPreferences`
 * returns the `SharedPreferences` object and nothing about where it lives on
 * disk. This is the path the platform has used for every such file since
 * Android's first release: `getSharedPreferences` and the default full-backup
 * implementation both walk the same real "shared_prefs" directory under the
 * app's data directory, so relying on it is relying on the layout the
 * platform's own backup code already relies on, not on an implementation
 * detail nobody else depends on.
 *
 * [TriglyBackupAgent] needs exactly this file's path, and only when it is
 * withholding everything else: see its own KDoc for why the setting must
 * still travel even then. Kept as a pure function of two strings, rather than
 * inlined with a `File` built from `Context.getDataDir()`, so the path itself
 * can be checked without a device.
 */
fun sharedPreferencesFilePath(dataDirPath: String, preferencesName: String): String =
    "$dataDirPath/shared_prefs/$preferencesName.xml"
