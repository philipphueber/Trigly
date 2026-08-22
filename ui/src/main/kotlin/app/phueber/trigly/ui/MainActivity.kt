package app.phueber.trigly.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.phueber.trigly.core.ComponentRequirement

class MainActivity : ComponentActivity() {

    private val viewModel: RulesViewModel by viewModels {
        val container = (application as TriglyApp).container
        RulesViewModel.factory(
            repository = container.ruleRepository,
            registry = container.registry,
            checker = container.requirementChecker,
        )
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The result is ignored deliberately: the checker re-reads the real
            // state on resume, which is also the only thing that can tell us
            // about a grant made in settings rather than in this dialog.
            viewModel.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val statuses by viewModel.statuses.collectAsStateWithLifecycle()
                RulesScreen(
                    statuses = statuses,
                    onEnabledChange = viewModel::setEnabled,
                    onResolve = ::resolve,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers the round trip to a system settings screen, which reports
        // nothing back.
        viewModel.refresh()
    }

    private fun resolve(requirement: ComponentRequirement) {
        when (requirement) {
            is ComponentRequirement.RuntimePermission ->
                requestPermission.launch(requirement.permission)

            is ComponentRequirement.SpecialAccess ->
                openSettings(requirement.kind.settingsAction)

            // Not resolvable; the UI does not offer a button for these.
            else -> Unit
        }
    }

    private fun openSettings(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Not every OEM ships every settings screen, and an unresolvable intent
        // would crash the app rather than merely fail to help.
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
