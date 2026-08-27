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
 */
interface Action {
    suspend fun execute(event: TriggerEvent): ActionResult
}

/**
 * Builds an [Action] of one type from stored configuration. The plugin seam
 * for actions — see [TriggerFactory] for the rule it follows.
 */
interface ActionFactory : ComponentFactory {
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
