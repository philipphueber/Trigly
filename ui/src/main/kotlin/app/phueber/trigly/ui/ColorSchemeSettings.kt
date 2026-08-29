package app.phueber.trigly.ui

import android.content.Context

/**
 * The colour scheme the user asked for: [ColorSchemeChoice.Default] unless
 * they opened Settings and changed it. See `PresetSchemes.kt` for what each
 * choice resolves to, and `SettingsScreen`.
 */
interface ColorSchemeSettings {
    fun colorSchemeChoice(): ColorSchemeChoice
    fun setColorSchemeChoice(choice: ColorSchemeChoice)
}

/**
 * `SharedPreferences` file and key, named once so the two files that read
 * them cannot drift apart. Its own file, not [BACKUP_PREFS_NAME]: `SettingsScreen`'s
 * backup switch says what may leave the phone, and a colour choice is not
 * that kind of data, so it must not ride along in the same decision. Keeping
 * it in a separate file is also what makes the backup answer itself correct
 * for free: `TriglyBackupAgent.onFullBackup` backs up the whole data
 * directory when backup is on, which picks this file up with no code change,
 * and when backup is off `onFullBackup` writes only [BACKUP_PREFS_NAME]
 * explicitly, which correctly leaves this one out - forcing it in regardless
 * would back up data the person just asked not to back up.
 */
internal const val COLOR_SCHEME_PREFS_NAME = "color_scheme_settings"
private const val KEY_COLOR_SCHEME_CHOICE = "color_scheme_choice"

/**
 * Backed by `SharedPreferences`, the same choice [BackupSettings] makes and
 * for the same reason: one small value with no relationship to the rule
 * database Room holds.
 */
internal class SharedPreferencesColorSchemeSettings(context: Context) : ColorSchemeSettings {
    private val prefs = context.applicationContext
        .getSharedPreferences(COLOR_SCHEME_PREFS_NAME, Context.MODE_PRIVATE)

    override fun colorSchemeChoice(): ColorSchemeChoice =
        ColorSchemeChoice.fromStoredName(prefs.getString(KEY_COLOR_SCHEME_CHOICE, null))

    override fun setColorSchemeChoice(choice: ColorSchemeChoice) {
        prefs.edit().putString(KEY_COLOR_SCHEME_CHOICE, choice.storedName).apply()
    }
}

fun colorSchemeSettings(context: Context): ColorSchemeSettings = SharedPreferencesColorSchemeSettings(context)
