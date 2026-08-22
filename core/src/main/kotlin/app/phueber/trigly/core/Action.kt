package app.phueber.trigly.core

/** Outcome of one [Action] run. Failure is expected traffic, not exceptional. */
sealed interface ActionResult {
    data object Success : ActionResult

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
}
