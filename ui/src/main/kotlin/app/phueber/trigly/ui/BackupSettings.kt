package app.phueber.trigly.ui

import android.content.Context

/**
 * The one setting this app has today: whether Android's backup may carry the
 * rules, the saved values, and any webhook token stored in the config
 * database. See [TriglyBackupAgent] and `SettingsScreen`.
 *
 * True unless the user turned it off. Backup stays useful for whoever wants
 * it: a new phone that comes back looking the same is a real convenience.
 * The default favours that, and the settings screen is where the exposure is
 * explained rather than avoided by defaulting off.
 */
interface BackupSettings {
    fun cloudBackupEnabled(): Boolean
    fun setCloudBackupEnabled(enabled: Boolean)
}

/** `SharedPreferences` file and key, named once so the two files that read them cannot drift apart. */
internal const val BACKUP_PREFS_NAME = "backup_settings"
private const val KEY_CLOUD_BACKUP_ENABLED = "cloud_backup_enabled"

/**
 * Backed by `SharedPreferences`, not Room, and that is a deliberate choice
 * rather than the path of least resistance.
 *
 * [TriglyBackupAgent] is what actually reads this, and the system can start it
 * in a fresh process with nothing else in the app initialised yet. A restore
 * is exactly that case. Opening the rule database to answer "may I back up
 * the rule database" would mean the answer depends on the thing being asked
 * about, and would cost a Room open on every full-backup pass for a single
 * boolean. `SharedPreferences` is a small file the platform keeps an
 * in-memory cache of after the first read in a process, which is what makes
 * the agent's question free to ask.
 *
 * This is also the first `SharedPreferences` or DataStore anywhere in Trigly.
 * A rule's own settings live in Room because they are the user's data and
 * outlive a reinstall; this one boolean is neither. It is how the app
 * behaves, not what it watches for, and Room would be the wrong tool for a
 * value with no relationship to any rule.
 */
internal class SharedPreferencesBackupSettings(context: Context) : BackupSettings {
    private val prefs = context.applicationContext
        .getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)

    override fun cloudBackupEnabled(): Boolean =
        prefs.getBoolean(KEY_CLOUD_BACKUP_ENABLED, true)

    override fun setCloudBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_BACKUP_ENABLED, enabled).apply()
    }
}

fun backupSettings(context: Context): BackupSettings = SharedPreferencesBackupSettings(context)
