package app.phueber.trigly.core

/** Outcome of one [Action] run. Failure is expected traffic, not exceptional. */
sealed interface ActionResult {
    /**
     * @param outputs What this run produced, for a later action in the same rule
     *   run to read as `{{action.<key>}}` or `{{<this action's type>.<key>}}`.
     *   See `docs/variables.md` and [ComponentFactory.variables]. Empty for
     *   almost every action: an action declares an output only for a value it
     *   computed and that nothing else could have known in advance, such as
     *   `set_rule_enabled` reporting which way `TOGGLE` actually went.
     */
    data class Success(val outputs: Map<String, String> = emptyMap()) : ActionResult

    data class Failure(val reason: String, val cause: Throwable? = null) : ActionResult
}

/**
 * Something the app does in response to a [TriggerEvent].
 *
 * Implementations live in `:actions`, never here. [execute] is suspending and
 * must be cancellable: the engine cancels it when its rule is disabled.
 *
 * **[execute] bounds its own waiting. The engine does not.**
 * `TriggerEngine.run` calls this with no timeout, and that is deliberate: a
 * generic bound cannot tell a call that is stuck from `delay`, which waits on
 * purpose and can legitimately wait a long time, and `withTimeout` cannot
 * interrupt a blocking platform call anyway, since a blocked thread does not
 * notice a cancelled coroutine. `docs/todo.md`'s Rejected section has the
 * finding and why it was turned down. So a call into a platform API that can
 * hang, such as `MediaPlayer.prepare()`, is this action's own problem to
 * solve, the way `PlaySoundAction` and `PlayAlertAction` in `:actions` solve
 * it: move the wait behind a suspension a timeout can actually cancel,
 * rather than behind a blocking call one cannot.
 */
interface Action {
    suspend fun execute(event: TriggerEvent): ActionResult
}

/**
 * Builds an [Action] of one type from stored configuration. The plugin seam
 * for actions (see [TriggerFactory] for the rule it follows).
 */
interface ActionFactory : ComponentFactory {
    /**
     * **Must not block, and must not do I/O.** `TriggerEngine.startRule` calls
     * this once per action while it holds its own monitor, for the same
     * reason [TriggerFactory.create] must not block: a reader on Android's
     * main thread can be waiting on that same monitor through
     * `TriggerEngine.runningRuleIds`, and a slow [create] here is a slow main
     * thread on that path. This is also called again whenever a templated
     * field resolves to a changed value; see `TriggerEngine.ActionSlot`. Read
     * a value already held in memory, or in a field passed to the factory's
     * own constructor; do not open a file, a socket or a system-service call
     * to answer this.
     */
    fun create(config: Map<String, String>): Action

    /**
     * Every action can be run on demand, so every action offers [ComponentTool.Test]
     * without saying so.
     *
     * A trigger cannot: "run this trigger" means waiting for the world to change.
     * That asymmetry is why the default lives here rather than on
     * [ComponentFactory]. The editor used to hardcode a Test button for actions
     * and nothing for triggers, which was the same knowledge in a place that could
     * not be overridden.
     *
     * An action with more to offer overrides this and includes [ComponentTool.Test]
     * itself, so losing the button is a visible choice rather than an accident.
     */
    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        listOf(ComponentTool.Test)

    /**
     * Whether the intent this config would build finds something to run it,
     * without running it. Null for every action but `fire_intent`, meaning
     * "this action has no such question to answer". The editor draws nothing
     * for it. See `app.phueber.trigly.actions.FireIntentActionFactory` for the
     * one override, `ComponentTool.CheckIntentTarget` for the button that reads
     * it, and `docs/actions.md`'s "Firing a predefined intent" section for the
     * whole design.
     *
     * Declared here, on every [ActionFactory], rather than only on the one
     * factory that answers it, for the same reason [toolsFor] is declared on
     * [ComponentFactory] rather than hardcoded per action name in the editor:
     * `Registry.checkIntentTarget` reaches this by type string, so the editor
     * never has to know `fire_intent`'s name to ask the question. It stops at
     * [ActionFactory] rather than reaching all the way up to [ComponentFactory]
     * the way [toolsFor] does, because a trigger has no intent to check at all.
     *
     * **Must never send anything.** Unlike [ComponentTool.Test], which really
     * runs the action, this answers a question about the *configured* intent
     * by asking `PackageManager`, and nothing else. Pass the config with every
     * `{{...}}` reference already resolved to a sample value, the same way the
     * generic Test flow resolves one before calling `create()`. This reads
     * config, not live rule state, and has no other way to see a variable's
     * value.
     */
    fun checkIntentTarget(config: Map<String, String>): IntentTargetCheck? = null
}

/**
 * What checking a `fire_intent` action's configured intent found, without
 * sending it. See [ActionFactory.checkIntentTarget].
 *
 * Three of the four match the capture concept's own "Test" button exactly:
 * an app will accept this, no app will accept this, or Trigly cannot see the
 * app because Android hides it. The fourth, [REFUSED_SELF_TARGET], is not
 * one Android would ever report on its own. It is Trigly's own refusal, and
 * it is reported here rather than folded into [WOULD_NOT_RESOLVE] because
 * some app, Trigly itself, genuinely would answer this; saying "no app will
 * accept this" would be false. See `docs/actions.md` for why sending back
 * into Trigly's own package is refused unconditionally rather than only when
 * the target happens to be an exported component.
 */
enum class IntentTargetCheck {
    /** At least one app Trigly can see would answer this. */
    WOULD_RESOLVE,

    /**
     * Trigly can see every app that could possibly answer this, and asked, and
     * none does. Reported only when that claim is actually earned (see
     * `decideIntentTargetCheck` in `:actions` for when it is, versus when the
     * honest answer is [HIDDEN_BY_VISIBILITY] instead).
     */
    WOULD_NOT_RESOLVE,

    /**
     * Android's package visibility rules (API 30+) hide the app that might
     * answer this from Trigly's own queries. This is a limit on *asking*, not
     * on *sending*: `docs/actions.md` records what was verified about the
     * difference, and a real firing is not refused for this reason alone.
     */
    HIDDEN_BY_VISIBILITY,

    /** The configured intent would reach Trigly's own package. Refused outright. */
    REFUSED_SELF_TARGET,
}
