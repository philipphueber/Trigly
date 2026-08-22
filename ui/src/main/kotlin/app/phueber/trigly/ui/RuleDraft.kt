package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.defaultValue

/** Which half of a rule a component belongs to. */
enum class Slot { TRIGGER, ACTION }

/**
 * A rule as it exists mid-edit.
 *
 * Separate from [Rule] because a half-built rule is not a valid one: the trigger
 * may be unchosen and config may be incomplete or unparseable. Keeping the
 * in-progress shape distinct means [Rule] never has to represent nonsense, and
 * the editor never has to construct a `Rule` it knows is invalid just to hold
 * the user's typing.
 */
data class ComponentDraft(
    val type: String,
    val config: Map<String, String> = emptyMap(),
)

data class RuleDraft(
    /** Null for a rule that has not been saved yet. */
    val id: String?,
    val name: String = "",
    val trigger: ComponentDraft? = null,
    val actions: List<ComponentDraft> = emptyList(),
    val enabled: Boolean = true,
) {
    val isNew: Boolean get() = id == null
}

fun Rule.toDraft() = RuleDraft(
    id = id,
    name = name,
    trigger = ComponentDraft(trigger.type, trigger.config),
    actions = actions.map { ComponentDraft(it.type, it.config) },
    enabled = enabled,
)

/**
 * Builds a [Rule] from the draft. Structural completeness only — whether the
 * *config* is valid is decided by asking the factories, in
 * [RuleEditorViewModel.save].
 */
fun RuleDraft.toRuleOrNull(): Rule? {
    val trigger = trigger ?: return null
    if (name.isBlank()) return null

    return Rule(
        id = id ?: RuleJson.newId(),
        name = name.trim(),
        trigger = ComponentSpec(trigger.type, trigger.config),
        actions = actions.map { ComponentSpec(it.type, it.config) },
        enabled = enabled,
    )
}

/**
 * Seeds a newly chosen component with the defaults its schema declares.
 *
 * Fields whose blankness is meaningful get no value at all — see
 * [ConfigField.Text.blankMeaning]. Supplying "" for those would look identical
 * to a deliberate choice while actually being an accident of the editor.
 */
fun defaultConfigFor(fields: List<ConfigField>): Map<String, String> =
    fields.mapNotNull { field -> field.defaultValue()?.let { field.key to it } }.toMap()

/**
 * Carries config across a type change, keeping only keys the new type knows.
 *
 * Switching `wifi_state` to `bluetooth_adapter_state` should keep the
 * `enabled`/`disabled` choice rather than blanking the form — the two share the
 * key and the vocabulary. Switching to something unrelated correctly drops
 * everything.
 */
fun migrateConfig(
    existing: Map<String, String>,
    newFields: List<ConfigField>,
): Map<String, String> {
    val allowedKeys = newFields.map { it.key }.toSet()
    val kept = existing.filterKeys { it in allowedKeys }
    // Defaults fill only the gaps, so a carried-over value always wins.
    return defaultConfigFor(newFields) + kept
}
