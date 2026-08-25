package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.TriggerNode

/**
 * A copy of this rule, ready to be edited into the one the person actually
 * wants.
 *
 * Three decisions are worth stating, because a copy is not a memcpy of a rule.
 *
 * **A generated id is minted again, not copied.** Some config values identify
 * the rule to something outside it. A home screen shortcut carries the
 * `shortcutId` its trigger listens for, and that trigger fires on any tap whose
 * id matches. Copy the value and one shortcut starts two rules, which is a
 * fault nobody would look for in a duplicate. [ConfigField.GeneratedId] is
 * already the declared marker for "the editor mints this, a person never types
 * it", so this walks every component and mints each one again. Declared rather
 * than a list of known keys, for the reason the field type exists: a new
 * component with an id of its own must not have to edit this function.
 *
 * **The copy arrives switched off.** Duplicating an enabled rule that acts on
 * the world means two rules doing the same thing from the moment of the tap,
 * before anyone has changed the part they meant to change. Off is the state a
 * person can correct with one tap, in the direction they choose. On is a state
 * the app chose for them and may already have acted on.
 *
 * **The name says it is a copy.** Two rules with one name in a list is the
 * problem the list exists to solve. The folder is kept, because a copy belongs
 * where the original does until told otherwise.
 */
internal fun Rule.duplicated(registry: Registry): Rule = Rule(
    id = RuleJson.newId(),
    name = "$name copy",
    trigger = trigger.withFreshGeneratedIds(registry),
    actions = actions.map { it.withFreshGeneratedIds(registry.actionDescriptor(it.type)) },
    enabled = false,
    folder = folder,
)

private fun TriggerNode.withFreshGeneratedIds(registry: Registry): TriggerNode = when (this) {
    is TriggerNode.One ->
        TriggerNode.One(spec.withFreshGeneratedIds(registry.triggerDescriptor(spec.type)))

    is TriggerNode.Group ->
        copy(children = children.map { it.withFreshGeneratedIds(registry) })
}

/**
 * The same config, with every [ConfigField.GeneratedId] value replaced.
 *
 * A component the registry does not know keeps its config unchanged. That is
 * the honest answer rather than the safe-looking one: with no schema there is no
 * way to tell which of its keys is an identity, and inventing a value for a key
 * this build does not understand would corrupt a rule that a build with the
 * component installed could still run.
 */
private fun ComponentSpec.withFreshGeneratedIds(
    descriptor: ComponentDescriptor?,
): ComponentSpec {
    val generated = descriptor?.configFields
        ?.filterIsInstance<ConfigField.GeneratedId>()
        ?.map { it.key }
        .orEmpty()
    if (generated.isEmpty()) return this

    return copy(
        config = config.mapValues { (key, value) ->
            if (key in generated) RuleJson.newId() else value
        }
    )
}
