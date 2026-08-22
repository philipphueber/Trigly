package app.phueber.trigly.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: RulesViewModel by viewModels {
        RulesViewModel.factory((application as TriglyApp).container.ruleRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val rules by viewModel.rules.collectAsStateWithLifecycle()
                RulesScreen(rules = rules, onEnabledChange = viewModel::setEnabled)
            }
        }
    }
}
