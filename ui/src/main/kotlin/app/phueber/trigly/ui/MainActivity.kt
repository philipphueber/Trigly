package app.phueber.trigly.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import app.phueber.trigly.actions.declaredKeptButtons
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ControllerLivenessProbe
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.all
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.variableNameProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as TriglyApp).container }

    /**
     * Bumped whenever this activity resumes, to re-read permission state.
     *
     * Granting happens in a system settings screen, which reports nothing back —
     * the same round trip `onResume` already refreshes the rule list for. The
     * editor needs it too, since it now hides a requirement once it is met, and
     * a row that stayed on screen after the grant would look like the grant had
     * not worked. Compose state, so reading it inside composition is what makes
     * the redraw happen.
     */
    private var grantEpoch by mutableIntStateOf(0)

    private val listViewModel: RulesViewModel by viewModels {
        RulesViewModel.factory(
            repository = container.ruleRepository,
            registry = container.registry,
            checker = container.requirementChecker,
            ruleFaults = container.ruleFaults,
            // Built from the same two ports the app already wires for actions
            // to reach the notification listener and the accessibility
            // service, rather than asking `:triggers` the same question a
            // second way. See `ControllerLivenessProbe`.
            livenessProbe = ControllerLivenessProbe(container.notifications, container.ui),
        )
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The result is ignored deliberately: the checker re-reads the real
            // state on resume, which is also the only thing that can tell us
            // about a grant made in settings rather than in this dialog.
            listViewModel.refresh()
            // A grant can change what the engine's own notification is allowed
            // to say, and the engine hears nothing about permissions. Poking it
            // makes it re-post; with no rules enabled the service simply ends
            // itself again, which costs nothing visible.
            EngineService.start(this)
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
            uri?.let { picked ->
                // Launched rather than awaited inline: reading the file is
                // suspending, and an activity-result callback is not.
                lifecycleScope.launch { listViewModel.import(readImportFile(picked)) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh launch, not on every recreation: a rotation would
        // otherwise re-ask on each turn of the phone.
        if (savedInstanceState == null) askAboutNotificationsIfNeeded()
        // Android 15 draws apps behind the system bars whether they opt in or
        // not; calling this makes the behaviour the same on every version we
        // support, so the screens only have to handle one case. The insets are
        // then owned by `BlockHeader` and `BlockBottomBar`.
        enableEdgeToEdge(
            // The status bar sits on `BlockHeader`, which is `colorScheme.primary`
            // — the logo orange, and the one role that is the *same value* in
            // both schemes (see `Palette.kt`). So the band behind the clock does
            // not change with the theme, and neither should the icons on it.
            //
            // `light` is named for the background, not the icons: it asks for
            // dark icons, which is the pair that measures 5.66:1 on `#EC6206`.
            // Light icons on it would be 3.32:1, under the 3:1 floor once you
            // account for the thin strokes a status-bar glyph is made of.
            //
            // This used to be `auto { … }` with the polarity inverted, because
            // primary inverted with the theme and the framework default read the
            // *page* rather than the band. One fixed band, one fixed answer.
            statusBarStyle = SystemBarStyle.light(
                scrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
            // The navigation bar is left alone deliberately: it sits over the page
            // background, which does follow the theme, so the default is right.
        )
        setContent {
            // Read once here, at the top, rather than inside SettingsHost: the
            // theme has to know the choice before the first frame, and
            // SettingsHost only exists while that one screen is open. The same
            // SettingsViewModel instance answers both call sites - viewModel()
            // resolves to this activity's one instance of the class either
            // way - so the two never see a different answer.
            val settings: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    backupSettings = container.backupSettings,
                    colorSchemeSettings = container.colorSchemeSettings,
                    launcherIconEnabler = container.launcherIconEnabler,
                ),
            )
            val colorSchemeChoice by settings.colorSchemeChoice.collectAsStateWithLifecycle()
            val resolved = resolvedColors(
                context = this,
                choice = colorSchemeChoice,
                darkTheme = isSystemInDarkTheme(),
            )

            TriglyTheme(colorScheme = resolved.colorScheme, extraColors = resolved.extra) {
                // Saveable, so rotating the phone does not throw the user out of
                // the editor — and so a rotation is not mistaken for leaving it,
                // which is what `EditorHost` keys discarding the draft on.
                var screen by rememberSaveable(stateSaver = ScreenSaver) {
                    mutableStateOf<Screen>(Screen.RuleList)
                }

                // One handler for the whole app, registered once and never
                // removed while the activity lives.
                //
                // It used to be one handler per destination, added and removed as
                // the screen changed. That is the arrangement that makes back
                // unreliable: the editor's handler is disposed *by the navigation
                // it just performed*, so a back press arrives while the callback
                // that should answer it is being torn down. The rule list had no
                // handler at all and leaned on the framework default to finish the
                // activity — which is also why "back on the list closes the app"
                // was never something this app actually stated.
                //
                // Now it states it. [backTarget] holds the decision; null means
                // the list is the bottom of the stack and back leaves.
                BackHandler {
                    val target = backTarget(screen)
                    if (target == null) finish() else screen = target
                }

                // Same rule as the editor's exit below: showing a toast and
                // clearing the flag are things that *happen*, not things a
                // composition describes. Done inline, a recomposition between the
                // toast and the clear shows the message twice.
                val message by listViewModel.message.collectAsStateWithLifecycle()
                LaunchedEffect(message) {
                    message?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        listViewModel.clearMessage()
                    }
                }

                // Read once, here, for the same reason in all three cases: each is
                // a query costing hundreds of milliseconds, and every field of
                // that kind wants the same answer. Provided rather than passed,
                // because only one branch of `ConfigFieldEditor` reads each and
                // threading three lists through two screens would put them in
                // signatures that have no use for them.
                val installedApps by rememberInstalledApps()
                val deviceSounds by rememberDeviceSounds()
                val pairedDevices by rememberPairedDevices()
                // A function, not a snapshot: notifications come and go while the
                // editor is open, so the picker reads them when it opens.
                // The rules themselves, for the field that points at one. A
                // snapshot rather than a reader function, unlike the
                // notifications above: the list is already collected here and
                // changes redraw the editor anyway.
                val allRules by listViewModel.statuses.collectAsStateWithLifecycle()
                val ruleChoices = remember(allRules) {
                    allRules.map { RuleChoice(it.rule.id, it.rule.name, it.rule.enabled) }
                }

                // The names "Press a kept button" can be pointed at. Two halves:
                // what the rules declare, which is derived here and changes only
                // when a rule does, and what is kept in this process right now,
                // which the reader asks for each time the dialog opens.
                // Remembered so the provided lambda stays the same object across
                // recomposition — a static local re-provided with a fresh value
                // invalidates everything under it.
                val declaredKept = remember(allRules) {
                    declaredKeptButtons(allRules.map { it.rule })
                }
                val keptButtonReader: () -> List<KeptButton> = remember(declaredKept) {
                    { keptButtons(container.notifications.capturedNames(), declaredKept) }
                }

                CompositionLocalProvider(
                    LocalInstalledApps provides installedApps,
                    LocalDeviceSounds provides deviceSounds,
                    LocalPairedDevices provides pairedDevices,
                    LocalActiveNotifications provides container.notifications::activeNotifications,
                    LocalRules provides ruleChoices,
                    LocalKeptButtons provides keptButtonReader,
                ) {
                    // The page, as a Surface, for the content colour rather than
                    // for the fill — `window_background` in `res/values*` already
                    // paints the fill before Compose starts, and this agrees with
                    // it because both come from `Tone.Paper` / `Tone.Ink`.
                    //
                    // Without a Surface anywhere above them, the screens ran on
                    // `LocalContentColor`'s own default, which is hard black. On
                    // the light theme that is very nearly `onSurface` and the bug
                    // is invisible; on the dark theme every `Text` that did not
                    // name a colour was black on a near-black page. "ENABLED"
                    // beside the rule's toggle was the one that showed it.
                    //
                    // Fixed here rather than by colouring that one label, because
                    // the next `Text` written without a `color` would have been
                    // wrong in exactly the same way, and would have looked right
                    // to anyone working in light mode.
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Destination(
                            screen = screen,
                            onNavigate = { screen = it },
                        )
                    }
                }
            }
        }
    }

    /** The two destinations, split out so the providers above stay readable. */
    @androidx.compose.runtime.Composable
    private fun Destination(screen: Screen, onNavigate: (Screen) -> Unit) {
        val installedApps = LocalInstalledApps.current
        when (screen) {
            Screen.RuleList -> {
                val statuses by listViewModel.statuses.collectAsStateWithLifecycle()
                // Re-read on the same signal as every permission check below:
                // granting this happens in a settings screen that reports
                // nothing back, and grantEpoch is what makes onResume's
                // refresh visible to this composable.
                val ignoringBatteryOptimizations = remember(grantEpoch) { ignoringBatteryOptimizations() }
                // Collected here rather than folded into RulesViewModel: the
                // rules list has no other business with the variable store, and
                // a count is the only thing this screen wants from it.
                val savedValues by container.variableStore.all()
                    .collectAsStateWithLifecycle(initialValue = emptyMap())
                RulesScreen(
                    statuses = statuses,
                    onEnabledChange = listViewModel::setEnabled,
                    onResolve = ::resolve,
                    onNewRule = { onNavigate(Screen.RuleEditor(null)) },
                    onEditRule = { onNavigate(Screen.RuleEditor(it)) },
                    onExportAll = { export(listViewModel.exportAll(), "trigly-rules.json") },
                    onExportRule = ::shareSingle,
                    onDuplicateRule = listViewModel::duplicate,
                    onImport = { openDocument.launch(arrayOf("application/json", "text/*")) },
                    // About the whole rule set rather than any one rule, which
                    // is why this is on this screen at all. It is a row in the
                    // list and not a header action: see `SavedValuesEntry`.
                    onSavedValues = { onNavigate(Screen.SavedValues) },
                    savedValueCount = savedValues.size,
                    onSettings = { onNavigate(Screen.Settings) },
                    describeComponent = container.registry::displayNameOf,
                    ignoringBatteryOptimizations = ignoringBatteryOptimizations,
                    onFixBatteryOptimization = ::requestIgnoreBatteryOptimizations,
                )
            }

            is Screen.RuleEditor -> {
                EditorHost(
                    ruleId = screen.ruleId,
                    onDone = { onNavigate(Screen.RuleList) },
                )
            }

            Screen.SavedValues -> {
                SavedValuesHost(onDone = { onNavigate(Screen.RuleList) })
            }

            Screen.Settings -> {
                SettingsHost(
                    onAttribution = { onNavigate(Screen.Attribution) },
                    onDone = { onNavigate(Screen.RuleList) },
                )
            }

            Screen.Attribution -> {
                AttributionHost(onDone = { onNavigate(Screen.Settings) })
            }
        }
    }

    /**
     * A single instance for the life of the activity, the same shape as
     * [listViewModel]: unlike the editor, there is no per-rule draft to key on,
     * so one ViewModel is what every visit to this screen shares.
     */
    @androidx.compose.runtime.Composable
    private fun SavedValuesHost(onDone: () -> Unit) {
        val savedValues: SavedValuesViewModel = viewModel(
            factory = SavedValuesViewModel.factory(
                variableStore = container.variableStore,
                ruleVariableStore = container.ruleVariableStore,
                ruleRepository = container.ruleRepository,
                substitutionsFor = container.registry::substitutionsFor,
            ),
        )
        val state by savedValues.state.collectAsStateWithLifecycle()

        SavedValuesScreen(
            values = state.values,
            ruleValues = state.ruleValues,
            onDeleteRuleValue = savedValues::deleteRuleValue,
            // One ViewModel method for both: an edit is an ordinary write, with
            // no ceremony beyond what adding a value already gets. See
            // `SavedValuesViewModel.setValue`.
            onAddValue = savedValues::setValue,
            onEditValue = savedValues::setValue,
            onDeleteValue = savedValues::delete,
            // A pure function, not routed through the ViewModel: it is
            // synchronous and side-effect free already, so there is nothing to
            // wait for and no reason to round-trip a keystroke through state.
            nameProblem = ::variableNameProblem,
            onBack = onDone,
        )
    }

    /**
     * A single instance for the life of the activity, the same reasoning
     * [SavedValuesHost] gives for itself: one switch, no per-rule draft to key
     * on.
     */
    @androidx.compose.runtime.Composable
    private fun SettingsHost(onAttribution: () -> Unit, onDone: () -> Unit) {
        // The same instance `setContent` already built for the theme -
        // viewModel() keys on the class and this activity's ViewModelStore,
        // not on the call site - so this is a second handle on one object,
        // not a second one.
        val settings: SettingsViewModel = viewModel(
            factory = SettingsViewModel.factory(
                backupSettings = container.backupSettings,
                colorSchemeSettings = container.colorSchemeSettings,
                launcherIconEnabler = container.launcherIconEnabler,
            ),
        )
        val cloudBackupEnabled by settings.cloudBackupEnabled.collectAsStateWithLifecycle()
        val colorSchemeChoice by settings.colorSchemeChoice.collectAsStateWithLifecycle()

        SettingsScreen(
            cloudBackupEnabled = cloudBackupEnabled,
            onCloudBackupEnabledChange = settings::setCloudBackupEnabled,
            colorSchemeChoice = colorSchemeChoice,
            onColorSchemeChoiceChange = settings::setColorSchemeChoice,
            onAttribution = onAttribution,
            onBack = onDone,
        )
    }

    /**
     * A single instance for the life of the activity, the same reasoning
     * [SettingsHost] gives for itself: nothing here changes while the screen
     * is open, so there is no ViewModel.
     *
     * The version comes from `packageManager` rather than from `BuildConfig`:
     * this module has `buildFeatures.buildConfig` off, and turning it on for
     * one string is not worth it. `getPackageInfo(String, Int)` is deprecated
     * from API 33 in favour of the `PackageInfoFlags` overload, but this app's
     * `minSdk` is 26, so the deprecated overload is what every supported
     * device actually runs; the suppression below is deliberate, not an
     * oversight.
     *
     * `remember` rather than a `val` on the activity: both reads are cheap
     * enough that there is no reason to pay for them before this screen is
     * ever opened, and a fresh read each time it opens costs nothing a
     * `Composable` needs to guard against, since neither the version nor the
     * bundled licence text changes while the app is running.
     */
    @androidx.compose.runtime.Composable
    private fun AttributionHost(onDone: () -> Unit) {
        val appVersion = remember {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }
        val licenseText = remember {
            resources.openRawResource(R.raw.license_apache_2_0)
                .bufferedReader()
                .use { it.readText() }
        }

        AttributionScreen(
            appVersion = appVersion,
            projects = shippedDependencies.groupIntoProjects(),
            licenseText = licenseText,
            onBack = onDone,
        )
    }

    /**
     * A ViewModel per edited rule, keyed by id so opening a different rule does
     * not inherit the previous draft.
     */
    @androidx.compose.runtime.Composable
    private fun EditorHost(ruleId: String?, onDone: () -> Unit) {
        val installedApps = LocalInstalledApps.current
        // Read for one purpose only: the folder names already in use, offered
        // when filing this rule. See the `existingFolders` argument below.
        val statuses by listViewModel.statuses.collectAsStateWithLifecycle()
        val editor: RuleEditorViewModel = viewModel(
            key = "editor-${ruleId ?: "new"}",
            factory = RuleEditorViewModel.factory(
                repository = container.ruleRepository,
                registry = container.registry,
                checker = container.requirementChecker,
                variableStore = container.variableStore,
                ruleVariableStore = container.ruleVariableStore,
                ruleId = ruleId,
            ),
        )

        // Why the editor has to be told what to show.
        //
        // These ViewModels live in the *activity's* store, so they outlive the
        // screen. A new rule has no id to key on, so its editor is necessarily
        // keyed on the constant "editor-new" and one instance serves every new
        // rule for the life of the activity; an existing rule keeps its own
        // instance under "editor-<id>". Either way, left alone the draft is
        // whatever was last typed into it — saved, abandoned, half-finished — and
        // reopening shows that instead of what the rule actually is.
        //
        // Seeded on *entry*, not cleared on exit. An earlier version reset on
        // dispose, reasoning that dispose catches every way of leaving — it does
        // not catch them reliably: the disposal that coincides with a
        // configuration change has to be guarded out (or a rotation wipes the
        // draft), and any exit that is guarded out leaves the retained ViewModel
        // dirty for the next entry. That was the "new rule is sometimes
        // prefilled" bug. Entry has no such gap: there is one way in, and it is
        // either a genuine open or a configuration-change restoration.
        //
        // Every rule, not just a new one. `reset()` means "show what this editor
        // should start from", which is empty for a new rule and the *stored* rule
        // for an existing one — so abandoning an edit and reopening shows what is
        // saved rather than the abandoned typing. For an existing rule that means
        // a blank form for the frame or two the read takes, which is the honest
        // way round: a flash of "loading" beats a flash of stale edits that look
        // committed.
        OnFreshEntry { editor.reset() }

        // Exit still has one job of its own: stop a test that is still running.
        // `play_alert` loops for up to a minute, and walking out of the editor
        // must silence it. Guarded on `isChangingConfigurations` so a rotation
        // does not cut a test off mid-run.
        DisposableEffect(Unit) {
            onDispose { if (!isChangingConfigurations) editor.stopTest() }
        }

        val state by editor.state.collectAsStateWithLifecycle()

        // An effect, not a branch. Navigating from inside composition writes the
        // screen state while it is being read, which Compose is free to handle by
        // recomposing — so the editor could be entered and left more than once for
        // one save, churning the BackHandler registered beside it and leaving a
        // back press with nothing sensible to do. `exitHandled` is what stops the
        // signal being read again when this rule is next opened.
        LaunchedEffect(state.finished) {
            if (state.finished) {
                editor.exitHandled()
                onDone()
            }
        }

        RuleEditorScreen(
            state = state,
            triggerOptionsFor = editor::triggerOptionsFor,
            actionOptions = editor.actionOptions,
            descriptorFor = editor::descriptorFor,
            onNameChange = editor::setName,
            onEnabledChange = editor::setEnabled,
            onFolderChange = editor::setFolder,
            // The folders that already exist, so filing a rule is picking a name
            // rather than retyping one. Sourced here rather than in the editor's
            // own ViewModel deliberately: that one holds a snapshot of the single
            // rule being edited and has no business reading the whole table, while
            // the list's ViewModel is already observing exactly that.
            //
            // Folder names compare exactly and case-sensitively — see
            // `normalizeFolder` — so "Car" and "car" really are two folders. This
            // list is what stops someone creating the second one by accident.
            existingFolders = remember(statuses) {
                statuses.mapNotNull { it.rule.folder }.distinct().sorted()
            },
            onChooseTrigger = editor::chooseTrigger,
            onAddAction = editor::addAction,
            onChangeActionType = editor::changeActionType,
            onRemoveAction = editor::removeAction,
            onMoveAction = editor::moveAction,
            onConfigChange = editor::setConfigValue,
            onTestAction = editor::testAction,
            onSave = editor::save,
            onDelete = editor::delete,
            onBack = onDone,
            onResolveRequirement = ::resolve,
            // Re-made whenever the activity has resumed, so returning from a
            // settings screen re-reads what is granted. The epoch is read here
            // rather than inside the lambda so the change is visible to Compose
            // as a new lambda, not as a value nobody subscribed to.
            isRequirementSatisfied = remember(grantEpoch) {
                { requirement -> container.requirementChecker.isSatisfied(requirement) }
            },
            // The trigger tree: one slot that may be a group, addressed by
            // path. Every one of these defaults to a no-op on the screen, so
            // leaving any of them unwired would show the affordance and do
            // nothing when tapped — which is why they are all listed here
            // explicitly rather than relying on the defaults.
            onChangeTriggerType = editor::changeTriggerType,
            onAddTrigger = editor::addTrigger,
            onSetTriggerOp = editor::setTriggerOp,
            onRemoveTrigger = editor::removeTrigger,
            onSetTriggerConfigValue = editor::setTriggerConfigValue,
            onPinShortcut = ::pinShortcut,
            // What each block offers on itself, asked of the registry so this
            // screen never learns a component's name. `grantEpoch` is not in
            // play here: tools follow from configuration, not from what is
            // granted.
            toolsFor = { type, config ->
                container.registry.toolsFor(ComponentSpec(type, config))
            },
            // What this rule's trigger tree lets an action read, and how each
            // component's fields are escaped as configured right now. See
            // `docs/variables.md`. `editor.availableVariables` reads the live
            // draft, so it is recomputed on every recomposition `state`
            // triggers, the same as `state` itself.
            availableVariables = editor.availableVariables,
            // The action half of the same question, which depends on where the
            // action sits: see `RuleEditorViewModel.availableVariablesForAction`.
            availableVariablesForAction = editor::availableVariablesForAction,
            substitutionsFor = { type, config ->
                container.registry.substitutionsFor(ComponentSpec(type, config))
            },
            // The inspector opens over the editor instead of navigating to it, so
            // consulting what a notification actually contains cannot cost a
            // half-written rule. Read through lambdas rather than passed as a
            // list: it must show what is posted *now*, and hoisting the read here
            // would freeze it at whatever was on screen when the editor opened.
            inspectorNotifications = { container.notifications.activeNotifications() },
            inspectorConnected = { container.notifications.isConnected },
            describeApp = installedApps::labelFor,
            // Names a type-qualified variable group the same way the trigger
            // picker and the rules list already name that trigger. See
            // `RulesScreen`'s own `describeComponent`.
            describeComponent = container.registry::displayNameOf,
        )
    }

    /**
     * Asks the launcher to pin a home-screen button for a shortcut trigger.
     *
     * Needs an `Activity` rather than the application context, because the
     * launcher shows a confirmation dialog attributed to the foreground app —
     * which is why this lives here and not in the screen.
     *
     * A launcher that does not support pinning is reported rather than ignored.
     * Silence would be indistinguishable from success, and the user would go
     * looking for a button that was never going to appear.
     */
    private fun pinShortcut(config: Map<String, String>) {
        val id = config["shortcutId"]?.takeIf { it.isNotBlank() } ?: return
        val label = config["label"]?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)

        when (ShortcutPinning.requestPinShortcut(this, id, label, config["icon"].orEmpty())) {
            PinShortcutResult.Requested -> Unit
            PinShortcutResult.UnsupportedByLauncher -> Toast.makeText(
                this,
                "This launcher cannot add shortcuts to the home screen.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers the round trip to a system settings screen, which reports
        // nothing back.
        listViewModel.refresh()
        grantEpoch++
    }

    /**
     * Asks for `POST_NOTIFICATIONS`, which is about the engine rather than about
     * any one action.
     *
     * `EngineService` posts an ongoing notification, and from Android 13 the
     * system silently drops it while this permission is refused — the service
     * keeps running, invisibly. That is the one outcome this app must not ship:
     * an automation app watching the device with nothing on screen to say so.
     * The user is still free to say no; they then get a running service they can
     * find in the system's active-apps list, which is Android's decision, not
     * ours. Asking is what makes it a decision rather than an accident.
     *
     * Asked here rather than left to `post_notification`'s own requirement,
     * because that one only surfaces if the user happens to add that action.
     * The system stops showing the dialog after two refusals, so a no stays a no.
     */
    private fun askAboutNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return

        requestPermission.launch(permission)
    }

    /**
     * Hands one rule to the share sheet.
     *
     * This used to open the document picker, the same save-a-file flow as
     * "Export all", under a control labelled Share. Two differently named
     * controls did one thing, and the name that promised the share sheet was the
     * one that did not open it. They are different jobs now: Export all writes a
     * file you keep, Share sends this rule somewhere.
     *
     * A failure is reported rather than swallowed. Nothing guarantees that any
     * installed app accepts an `application/json` send, and a Share button that
     * does nothing on a device with no such app is the silent failure this
     * project keeps designing against.
     */
    private fun shareSingle(rule: Rule) {
        runCatching {
            startActivity(shareRuleIntent(this, rule.name, listViewModel.exportOne(rule)))
        }.exceptionOrNull()?.let { cause ->
            Toast.makeText(
                this,
                "Could not share that rule: ${cause.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
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

    /**
     * Reads a chosen file for import.
     *
     * Off the main thread, on [Dispatchers.IO]: the document picker can be
     * backed by a cloud provider, so opening and reading its stream can block
     * for as long as a network call, and this used to run inline in the
     * activity-result callback with nothing to say otherwise.
     *
     * Bounded by [readAtMost] while reading rather than checked afterward, so
     * a file bigger than [MAX_IMPORT_BYTES] is never read whole into memory
     * just to be refused. Every failure becomes its own [ImportRead] instead
     * of an empty string, which used to reach `RuleJson.decode` and be
     * reported as "not valid JSON" whatever the real cause was.
     */
    private suspend fun readImportFile(uri: Uri): ImportRead = withContext(Dispatchers.IO) {
        val bytes = try {
            val stream = contentResolver.openInputStream(uri)
                ?: return@withContext ImportRead.CouldNotRead
            stream.use { it.readAtMost(MAX_IMPORT_BYTES) }
        } catch (_: Exception) {
            return@withContext ImportRead.CouldNotRead
        }
        if (bytes == null) ImportRead.TooLarge else ImportRead.Read(bytes.decodeToString())
    }

    private fun resolve(requirement: ComponentRequirement) {
        when (requirement) {
            is ComponentRequirement.RuntimePermission ->
                if (needsSettingsToGrant(requirement.permission)) {
                    openAppDetails()
                } else {
                    requestPermission.launch(requirement.permission)
                }

            is ComponentRequirement.SpecialAccess ->
                openSettings(requirement.kind)

            // Not resolvable; the UI does not offer a button for these.
            else -> Unit
        }
    }

    /**
     * Whether a runtime permission can only be granted in settings.
     *
     * True for exactly one permission, and the platform is what makes it a
     * special case. From Android 11 `ACCESS_BACKGROUND_LOCATION` may not be
     * asked for in a dialog: the request returns denied at once, no dialog is
     * drawn, and nothing distinguishes that from the user saying no. A Grant
     * button that quietly does nothing is worse than no button, so the tap goes
     * to the screen that can actually grant it. On Android 10 the dialog does
     * work and is the shorter road, which is why this is a version test and not
     * a flat rule.
     *
     * Kept as a question about a permission rather than a branch inside
     * [resolve], so the reason lives with the fact instead of in the caller.
     */
    private fun needsSettingsToGrant(permission: String): Boolean =
        permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * This app's own row in system settings, where "Allow all the time" lives.
     *
     * Always resolvable, unlike the screens [openSettings] handles: every
     * Android build ships app details, so there is no fallback to write.
     */
    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:$packageName".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openSettings(kind: SpecialAccessKind) {
        val intent = Intent(kind.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Some of these screens land on this app's own row when handed a
        // `package:` URI, and on a list of every installed app when not. Which
        // ones is declared on the kind rather than guessed here, because handing
        // the URI to a screen that does not accept it makes the intent
        // unresolvable — and the fallback below would then drop the user at the
        // top of Settings, which is worse than the list.
        if (kind.packageScoped) {
            intent.data = "package:$packageName".toUri()
        }
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

    /**
     * Whether Android currently excuses Trigly from battery optimisation.
     *
     * `PowerManager` is the only honest source: nothing else on the platform
     * answers this, and a flag this app set itself would only record whether
     * someone was once sent to the settings screen, not what they did there
     * or whether an OEM battery manager later took the exemption back.
     *
     * `?: true` is the fallback for the one system that has no
     * `PowerManager`, which is not a real device. Falling back to "already
     * excused" rather than "not excused" is deliberate: this class does not
     * accuse the app of a fault it could not actually check for.
     */
    private fun ignoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    /**
     * Sends someone to grant the battery optimisation exemption, direct
     * dialog first.
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` opens a one-tap system
     * dialog rather than a settings screen, and needs the
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission declared in the
     * manifest. Google Play restricts that intent to an app whose core
     * function needs to keep running in the background while the screen is
     * off, which is a fair description of Trigly's engine: without the
     * exemption Android can stop it while the phone is idle, and every rule
     * stops with it. This is a declared, deliberate use of the intent for
     * that reason, not a shortcut around Play's own review of it.
     *
     * Falls back to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, the list
     * screen, when the dialog does not resolve, and to `ACTION_SETTINGS`
     * when neither does. The same defence as [openSettings]: not every
     * manufacturer ships every settings screen, and an unresolvable intent
     * would crash the app rather than merely fail to help.
     */
    private fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val intent = when {
            direct.resolveActivity(packageManager) != null -> direct
            list.resolveActivity(packageManager) != null -> list
            else -> Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
