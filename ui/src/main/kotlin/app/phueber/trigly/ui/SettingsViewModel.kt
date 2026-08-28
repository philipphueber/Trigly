package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backs `SettingsScreen`. `BackupSettings` is a plain `SharedPreferences`
 * read and write, not a `Flow`. Nothing else in the app changes it while
 * this screen is open, so there is nothing to collect, only a value to seed
 * state from once and write through on every change.
 */
class SettingsViewModel(private val backupSettings: BackupSettings) : ViewModel() {

    private val _cloudBackupEnabled = MutableStateFlow(backupSettings.cloudBackupEnabled())
    val cloudBackupEnabled: StateFlow<Boolean> = _cloudBackupEnabled.asStateFlow()

    fun setCloudBackupEnabled(enabled: Boolean) {
        backupSettings.setCloudBackupEnabled(enabled)
        _cloudBackupEnabled.value = enabled
    }

    companion object {
        fun factory(backupSettings: BackupSettings) = viewModelFactory {
            initializer { SettingsViewModel(backupSettings) }
        }
    }
}
