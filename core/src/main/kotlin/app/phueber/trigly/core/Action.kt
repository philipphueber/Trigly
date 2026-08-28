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
 * solve, the way `PlaySoundAction` in `:actions` solves it: move the wait
 * behind a suspension a timeout can actually cancel, rather than behind a
 * blocking call one cannot.
 */
interface Action {
    suspend fun execute(event: TriggerEvent): ActionResult
}

/**
 * Builds an [Action] of one type from stored configuration. The plugin seam
 * for actions — see [TriggerFactory] for the rule it follows.
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
     * [ComponentFactory] — the editor used to hardcode a Test button for actions
     * and nothing for triggers, which was the same knowledge in a place that could
     * not be overridden.
     *
     * An action with more to offer overrides this and includes [ComponentTool.Test]
     * itself, so losing the button is a visible choice rather than an accident.
     */
    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        listOf(ComponentTool.Test)
}
