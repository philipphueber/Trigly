package app.phueber.trigly.core

/**
 * Which app-scope variables [this] rule reads, and where.
 *
 * The question a person needs answered before they delete a saved value: is
 * anything relying on this. Deleting a value that three rules read is a change
 * to three rules, and none of them would say so afterwards. They would simply
 * start failing on a reference that no longer resolves.
 *
 * **Reads only. [variableWrites] is the other half.** This said for a while that
 * finding what *writes* a variable was not possible here, because it would mean
 * knowing which action type does it and which of its config keys holds the name,
 * which is one component's identity in a shared file and the coupling
 * `CLAUDE.md` forbids in as many words. That reasoning was right about the
 * coupling and wrong about the conclusion: a component can *declare* the keys,
 * and [VariableWriteSpec] is that declaration. So writes are findable now, by
 * asking the registry rather than by naming an action, and they are found
 * beside this rather than in it because the two questions have different
 * readers. A read is spelled `{{app.name}}` in a field that declared it accepts
 * a reference, which is a property of the grammar; a write is a key a component
 * declared.
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
 * Every variable [this] rule writes, whatever scope it writes it to.
 *
 * The mirror of [appVariablesRead], and the reason it can exist at all is
 * [VariableWriteSpec]: the question asked here is "which components declare that
 * they write a variable", answered by the registry through [writesOf], and never
 * "is this action a `set_variable`".
 *
 * Every component is asked, trigger leaves and actions alike, for the reason
 * [appVariablesRead] asks them all: no trigger declares a write today, and
 * asking anyway is what keeps this correct the day one does.
 *
 * The result keeps [WrittenVariable.Unknowable] rather than dropping it. A name
 * built from a template is a real write to a name nobody can predict, and a
 * caller that silently lost it would go on to call every name in that scope
 * wrong. See [VariableReach.warnings].
 */
fun Rule.variableWrites(
    writesOf: (String) -> List<VariableWriteSpec>,
): List<WrittenVariable> = (trigger.leaves() + actions)
    .flatMap { spec -> writesOf(spec.type).mapNotNull { it.writes(spec.config) } }

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
