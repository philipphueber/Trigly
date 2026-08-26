package app.phueber.trigly.core

/**
 * Which app-scope variables [this] rule reads, and where.
 *
 * The question a person needs answered before they delete a saved value: is
 * anything relying on this. Deleting a value that three rules read is a change
 * to three rules, and none of them would say so afterwards. They would simply
 * start failing on a reference that no longer resolves.
 *
 * **Reads only, deliberately, and not writes.** Finding what *writes* a variable
 * would mean knowing that `set_variable` is the action that does it, and which of
 * its config keys holds the name. That is one component's identity, and putting
 * it here would mean this file has to be edited every time another component
 * learns to write a variable, which is the coupling `CLAUDE.md` forbids in as
 * many words. A read is different: it is spelled `{{app.name}}` in a field that
 * declared it accepts a reference, which is a property of the grammar rather
 * than of any component.
 *
 * [substitutionsFor] comes from the registry, for the same reason
 * [availableVariables] takes its declarations as a parameter: `:core`'s model
 * must not need the registry to describe itself.
 *
 * Every component of the rule is asked, trigger leaves and actions alike. No
 * trigger declares a substitutable field today, so the trigger half finds
 * nothing, and asking anyway is what keeps this correct the day one does.
 */
fun Rule.appVariablesRead(
    substitutionsFor: (ComponentSpec) -> Map<String, Substitution>,
): Set<String> = (trigger.leaves() + actions)
    .flatMap { spec ->
        substitutionsFor(spec).keys.mapNotNull { key -> spec.config[key] }
    }
    .flatMap { stored -> parseTemplate(stored).references }
    .filter { it.scope == VariableScope.APP }
    .mapTo(mutableSetOf()) { it.name }

/**
 * The rules that read [name], by rule, for a screen that has to name them.
 *
 * A count alone would answer "is anything using this" and not "what will I
 * break", and the second is the question somebody about to press delete is
 * actually asking.
 */
fun List<Rule>.rulesReading(
    name: String,
    substitutionsFor: (ComponentSpec) -> Map<String, Substitution>,
): List<Rule> = filter { name in it.appVariablesRead(substitutionsFor) }
