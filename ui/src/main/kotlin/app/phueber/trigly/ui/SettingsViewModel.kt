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
) : ViewModel() {

    private val _cloudBackupEnabled = MutableStateFlow(backupSettings.cloudBackupEnabled())
    val cloudBackupEnabled: StateFlow<Boolean> = _cloudBackupEnabled.asStateFlow()

    private val _colorSchemeChoice = MutableStateFlow(colorSchemeSettings.colorSchemeChoice())
    val colorSchemeChoice: StateFlow<ColorSchemeChoice> = _colorSchemeChoice.asStateFlow()

    fun setCloudBackupEnabled(enabled: Boolean) {
        backupSettings.setCloudBackupEnabled(enabled)
        _cloudBackupEnabled.value = enabled
    }

    fun setColorSchemeChoice(choice: ColorSchemeChoice) {
        colorSchemeSettings.setColorSchemeChoice(choice)
        _colorSchemeChoice.value = choice
    }

    companion object {
        fun factory(backupSettings: BackupSettings, colorSchemeSettings: ColorSchemeSettings) = viewModelFactory {
            initializer { SettingsViewModel(backupSettings, colorSchemeSettings) }
        }
    }
}
