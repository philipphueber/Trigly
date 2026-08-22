package app.phueber.trigly.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Rule

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as TriglyApp).container }

    private val listViewModel: RulesViewModel by viewModels {
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
            listViewModel.refresh()
        }

    /** Held between choosing "export" and the document picker returning. */
    private var pendingExport: String? = null

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val payload = pendingExport
            pendingExport = null
            if (uri != null && payload != null) writeText(uri, payload)
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { listViewModel.import(readText(it)) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws apps behind the system bars whether they opt in or
        // not; calling this makes the behaviour the same on every version we
        // support, so the screens only have to handle one case. The insets are
        // then owned by `BlockHeader` and `BlockBottomBar`.
        enableEdgeToEdge()
        setContent {
            TriglyTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.RuleList) }

                val message by listViewModel.message.collectAsStateWithLifecycle()
                message?.let {
                    Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                    listViewModel.clearMessage()
                }

                // Read once, here: the package query costs a few hundred
                // milliseconds and every app-package field wants the same answer.
                val installedApps by rememberInstalledApps()
                CompositionLocalProvider(LocalInstalledApps provides installedApps) {
                    Destination(
                        screen = screen,
                        onNavigate = { screen = it },
                    )
                }
            }
        }
    }

    /** The two destinations, split out so the providers above stay readable. */
    @androidx.compose.runtime.Composable
    private fun Destination(screen: Screen, onNavigate: (Screen) -> Unit) {
        when (screen) {
            Screen.RuleList -> {
                val statuses by listViewModel.statuses.collectAsStateWithLifecycle()
                RulesScreen(
                    statuses = statuses,
                    onEnabledChange = listViewModel::setEnabled,
                    onResolve = ::resolve,
                    onNewRule = { onNavigate(Screen.RuleEditor(null)) },
                    onEditRule = { onNavigate(Screen.RuleEditor(it)) },
                    onExportAll = { export(listViewModel.exportAll(), "trigly-rules.json") },
                    onExportRule = ::exportSingle,
                    onImport = { openDocument.launch(arrayOf("application/json", "text/*")) },
                    describeComponent = container.registry::displayNameOf,
                )
            }

            is Screen.RuleEditor -> {
                BackHandler { onNavigate(Screen.RuleList) }
                EditorHost(
                    ruleId = screen.ruleId,
                    onDone = { onNavigate(Screen.RuleList) },
                )
            }
        }
    }

    /**
     * A ViewModel per edited rule, keyed by id so opening a different rule does
     * not inherit the previous draft.
     */
    @androidx.compose.runtime.Composable
    private fun EditorHost(ruleId: String?, onDone: () -> Unit) {
        val editor: RuleEditorViewModel = viewModel(
            key = "editor-${ruleId ?: "new"}",
            factory = RuleEditorViewModel.factory(
                repository = container.ruleRepository,
                registry = container.registry,
                checker = container.requirementChecker,
                ruleId = ruleId,
            ),
        )

        val state by editor.state.collectAsStateWithLifecycle()
        if (state.saved) {
            onDone()
            return
        }

        RuleEditorScreen(
            state = state,
            triggerOptions = editor.triggerOptions,
            actionOptions = editor.actionOptions,
            descriptorFor = editor::descriptorFor,
            onNameChange = editor::setName,
            onEnabledChange = editor::setEnabled,
            onChooseTrigger = editor::chooseTrigger,
            onAddAction = editor::addAction,
            onChangeActionType = editor::changeActionType,
            onRemoveAction = editor::removeAction,
            onMoveAction = editor::moveAction,
            onConfigChange = editor::setConfigValue,
            onSave = editor::save,
            onDelete = editor::delete,
            onBack = onDone,
            onResolveRequirement = ::resolve,
        )
    }

    override fun onResume() {
        super.onResume()
        // Covers the round trip to a system settings screen, which reports
        // nothing back.
        listViewModel.refresh()
    }

    private fun exportSingle(rule: Rule) {
        val name = rule.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        export(listViewModel.exportOne(rule), "trigly-${name.ifEmpty { "rule" }}.json")
    }

    /**
     * Writes through the document picker rather than to a path of our choosing:
     * no storage permission, and the file lands somewhere the user picked and can
     * find again — which is the whole point of an export.
     */
    private fun export(payload: String, suggestedName: String) {
        pendingExport = payload
        createDocument.launch(suggestedName)
    }

    private fun writeText(uri: Uri, text: String) {
        val result = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("could not open that file for writing")
        }
        Toast.makeText(
            this,
            result.fold({ "Exported." }, { "Export failed: ${it.message}" }),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun readText(uri: Uri): String =
        runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("could not open that file")
        }.getOrElse { "" }

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
