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
     * Persists the choice, then switches the launcher icon to match. The icon
     * switch runs on every call, not only when the alias actually changes -
     * [switchLauncherIcon] re-enabling an already-enabled alias is a cheap
     * no-op on the platform side, and checking first here would just be this
     * class re-deriving what that function already decides.
     */
    fun setColorSchemeChoice(choice: ColorSchemeChoice) {
        colorSchemeSettings.setColorSchemeChoice(choice)
        _colorSchemeChoice.value = choice
        switchLauncherIcon(aliasIdFor(choice), ColorPresets.map { it.id }, launcherIconEnabler)
    }

    companion object {
        fun factory(
            backupSettings: BackupSettings,
            colorSchemeSettings: ColorSchemeSettings,
            launcherIconEnabler: ComponentEnabler,
        ) = viewModelFactory {
            initializer { SettingsViewModel(backupSettings, colorSchemeSettings, launcherIconEnabler) }
        }
    }
}
