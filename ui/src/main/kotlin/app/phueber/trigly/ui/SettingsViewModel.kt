package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backs `SettingsScreen`. Both settings are a plain `SharedPreferences` read
 * and write, not a `Flow`. Nothing else in the app changes either one while
 * this screen is open, so there is nothing to collect, only a value to seed
 * state from once and write through on every change.
 */
class SettingsViewModel(
    private val backupSettings: BackupSettings,
    private val colorSchemeSettings: ColorSchemeSettings,
    private val launcherIconEnabler: ComponentEnabler,
    private val pokeEngine: () -> Unit,
) : ViewModel() {

    private val _cloudBackupEnabled = MutableStateFlow(backupSettings.cloudBackupEnabled())
    val cloudBackupEnabled: StateFlow<Boolean> = _cloudBackupEnabled.asStateFlow()

    private val _colorSchemeChoice = MutableStateFlow(colorSchemeSettings.colorSchemeChoice())
    val colorSchemeChoice: StateFlow<ColorSchemeChoice> = _colorSchemeChoice.asStateFlow()

    fun setCloudBackupEnabled(enabled: Boolean) {
        backupSettings.setCloudBackupEnabled(enabled)
        _cloudBackupEnabled.value = enabled
    }

    /**
     * Persists the choice, switches the launcher icon to match, and pokes the
     * engine so the shade catches up. The icon switch runs on every call, not
     * only when the alias actually changes - [switchLauncherIcon] re-enabling
     * an already-enabled alias is a cheap no-op on the platform side, and
     * checking first here would just be this class re-deriving what that
     * function already decides.
     *
     * **The poke is the fix for a colour change that appeared not to work.**
     * `EngineService` reads this setting when it *builds* its ongoing
     * notification, and it builds one only when it starts or when the rule
     * list changes. A person who picked a new scheme therefore watched the
     * launcher icon change while the notification in the shade kept the old
     * tint, sometimes for days, until an unrelated rule edit happened to
     * rebuild it. Nothing was wrong with the colour; nothing had asked for it
     * to be re-read.
     *
     * [pokeEngine] is a start request, which is already this app's way of
     * saying "something changed out here": `MainActivity` uses the same one
     * after a permission grant, and `EngineService.onStartCommand` re-posts
     * for exactly that reason. It costs nothing when the engine is not
     * running, because a service with no enabled rule ends itself again at
     * once.
     *
     * A function rather than an interface, unlike [ComponentEnabler] beside
     * it: there is one call and no ordering for a fake to observe, so an
     * interface would be a type with nothing to say.
     */
    fun setColorSchemeChoice(choice: ColorSchemeChoice) {
        colorSchemeSettings.setColorSchemeChoice(choice)
        _colorSchemeChoice.value = choice
        switchLauncherIcon(aliasIdFor(choice), ColorPresets.map { it.id }, launcherIconEnabler)
        pokeEngine()
    }

    companion object {
        fun factory(
            backupSettings: BackupSettings,
            colorSchemeSettings: ColorSchemeSettings,
            launcherIconEnabler: ComponentEnabler,
            pokeEngine: () -> Unit,
        ) = viewModelFactory {
            initializer {
                SettingsViewModel(backupSettings, colorSchemeSettings, launcherIconEnabler, pokeEngine)
            }
        }
    }
}
