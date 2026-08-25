package app.phueber.trigly.core

import kotlinx.coroutines.flow.Flow

/**
 * Something that happened on the device and that a [Rule] can react to.
 *
 * [payload] carries trigger-specific detail (the address of the Bluetooth
 * device that connected, the package that posted a notification). Keys are
 * defined by the trigger that emits them and are documented on that trigger.
 */
data class TriggerEvent(
    val triggerType: String,
    val firedAtMillis: Long,
    val payload: Map<String, String> = emptyMap(),
)

/**
 * A configured source of [TriggerEvent]s.
 *
 * Implementations live in `:triggers`, never here. [events] is cold: it should
 * register its listener or receiver on collection and tear it down on
 * cancellation, so that a disabled rule holds no system resources.
 */
interface Trigger {
    fun events(): Flow<TriggerEvent>

    /**
     * Whether this holds **right now**, or null when the question does not apply.
     *
     * The seam that lets a trigger be used as a condition — see
     * `docs/conditions.md`. A trigger is an edge ("the screen turned on"); a
     * condition is a level ("the screen is on"). Most triggers backed by a sticky
     * broadcast or a queryable manager can answer both; a pure event such as
     * `sms_received` cannot, and says so by leaving this defaulted.
     *
     * **Null is not false.** It means "this cannot be asked", which a caller must
     * treat as not holding rather than as denial — see [ConditionNode.holds].
     * Returning false from a trigger that has no state would be a lie the
     * evaluator cannot see through.
     *
     * Defaulted so that adding conditions did not touch thirty-one existing
     * triggers, and so each can opt in on its own. Suspending because answering
     * may mean a one-shot location read or a binder call, and blocking the
     * engine's dispatcher for it would stall every other rule.
     */
    suspend fun currentlyHolds(): Boolean? = null
}

/**
 * Builds a [Trigger] of one type from stored configuration.
 *
 * This is the plugin seam. A new trigger type ships a new [TriggerFactory] and
 * adds it to its own module's factory list — adding one must not require
 * editing `:core` or any sibling trigger.
 */
interface TriggerFactory : ComponentFactory {
    fun create(config: Map<String, String>): Trigger

    /**
     * Whether this component can also be used as a condition.
     *
     * Declared on the factory rather than discovered by instantiating one, for
     * the same reason [configFields] and requirements are: the editor needs to
     * know which slots to offer a component in *before* anything is built.
     *
     * The pair to keep honest: a factory saying true must produce a [Trigger]
     * whose [Trigger.currentlyHolds] actually answers. Saying true and returning
     * null yields a condition that never holds — a rule that cannot fire, with
     * nothing on screen to say why.
     */
    val supportsCondition: Boolean
        get() = false

    /**
     * Whether this component can ever *start* a rule.
     *
     * The honest counterpart to [supportsCondition], and declared for the same
     * reason: the editor has to know before it builds anything. Almost every
     * component says true. A component that answers only a state question —
     * `time_window`, whose `events()` is `emptyFlow()` — says false, and the
     * editor then knows not to offer it as the thing that starts a rule.
     *
     * Without this the emptiness is discoverable only by instantiating a trigger
     * and watching a flow that never emits, which is exactly the shape of a rule
     * that silently never runs.
     *
     * The pair to keep honest, in the other direction from [supportsCondition]: a
     * factory saying false must produce a [Trigger] with no events, and a factory
     * saying true must produce one that can emit. A false with events is a
     * trigger the picker hides for no reason; a true without them is a rule that
     * waits forever.
     */
    val producesEvents: Boolean
        get() = true

    /**
     * The same question as [producesEvents], asked of one configuration.
     *
     * Some components can be told to stop watching. The location component is
     * the case this exists for: watching an area means holding an open request
     * for position updates for as long as the rule is on, which is the most
     * expensive thing this app can do to a battery, while answering "am I in
     * the area now" costs one fix when something else asks. A rule that only
     * needs the second should not pay for the first, so the component offers a
     * switch, and with that switch on it produces no events at all.
     *
     * The default ignores the config and answers with the flat [producesEvents],
     * so a component with no such switch says nothing about config and needs no
     * change here. That default is the reason this is an added function rather
     * than a replacement: adding a component must not mean editing the others.
     *
     * The same honesty requirement as [producesEvents] applies, per
     * configuration: a config this answers false for must produce a [Trigger]
     * whose [Trigger.events] is empty. Otherwise the editor hides the component
     * from a slot it would in fact have filled, and the engine collects a flow
     * the tree was told to ignore.
     */
    fun producesEvents(config: Map<String, String>): Boolean = producesEvents
}
