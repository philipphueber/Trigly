package app.phueber.trigly.core

/**
 * Where in a rule a field sits. The question [VariableReach] is asked.
 *
 * Two points and not one, because the answer genuinely differs down the rule:
 * an action can read what the actions above it wrote, and a trigger cannot read
 * anything an action did, because no action has run when a trigger is built.
 */
sealed interface ReadPoint {

    /**
     * A field of a trigger leaf, anywhere in the tree.
     *
     * One point for the whole tree, not one per leaf. A leaf's own fields are
     * read when the trigger is built, before any event, so where the leaf sits
     * in the tree changes nothing about what it could read.
     */
    data object Trigger : ReadPoint

    /** The action at [index], counting from zero, in the rule's action list. */
    data class Action(val index: Int) : ReadPoint
}

/**
 * The one answer to "which variables can be read here", for one rule as it is
 * written right now.
 *
 * **Why this exists as one object.** The editor used to assemble this answer in
 * three places: the picker added the trigger tree to whatever the two stores
 * held, save-time validation added the trigger tree to the action outputs, and
 * neither of them could see a name the draft itself declares. The engine
 * resolves something else again, so a name could be offered and never resolve,
 * or resolve and never be offered. The answer is one thing, so it is one object,
 * and the picker and the validation both ask it.
 *
 * ### What is readable where, and why
 *
 * | Scope | Readable at | Because |
 * |---|---|---|
 * | `trigger`, `<type>`, `event`, `rule` | every point | the engine fills these in from the event that started the run, before the first action |
 * | `action`, `<action_type>` | an action, from what is above it | [ActionOutputs] grows as each action returns, so a later action's output does not exist yet |
 * | `local` | an action, from what is above it | a run value lives on the coroutine running this firing and is written by an earlier action |
 * | `mine` | every point | the value survives the run, so an earlier action legitimately reads what a later one wrote, on the next run |
 * | `app` | every point | the store is shared, so any rule may have written it, including this one, and including later |
 *
 * The three writable scopes are the reason this takes the whole draft rather
 * than a trigger tree. A name in one of them exists because a person typed it
 * into a field, so the only place to find it is the rule being edited, and the
 * only honest way to find it is [VariableWriteSpec], which is a declaration and
 * not a test for one action's type string.
 *
 * ### What it will not claim
 *
 * A rule that another rule runs shares the caller's run values, and a value can
 * be put in the shared store by hand. So "nothing writes this" is a warning
 * ([warnings]) and never a refusal: [problems] stays exactly as strict as it
 * was, which is strict about the scopes the engine fills in and lenient about
 * the three a person writes.
 */
class VariableReach(
    private val trigger: TriggerNode?,
    private val actions: List<ComponentSpec>,
    /** What a component type declares its events or its results carry. */
    private val variablesOf: (String) -> List<VariableSpec>,
    /** What a component type declares it writes. See [VariableWriteSpec]. */
    private val writesOf: (String) -> List<VariableWriteSpec>,
    /** `{{app.*}}` as the store holds it now, per [VariableStore.scoped]. */
    private val savedAppVariables: List<ScopedVariable> = emptyList(),
    /** `{{mine.*}}` as the store holds it now for this rule, per [RuleVariableStore.scopedFor]. */
    private val savedRuleVariables: List<ScopedVariable> = emptyList(),
    /**
     * Every other saved rule, for the app scope only.
     *
     * A rule reads `{{app.trip_count}}` that another rule writes, and until that
     * other rule has actually run there is nothing in the store to offer. This
     * is what lets the picker offer the name anyway, and what stops a warning
     * about a name that is spelled perfectly well.
     *
     * Only the app scope crosses a rule boundary. `{{mine.*}}` is keyed by rule
     * id and `{{local.*}}` never leaves the firing, so another rule's writes in
     * those scopes are nothing to do with this one.
     */
    private val otherRules: List<Rule> = emptyList(),
    /** Names a component the way the rest of the app names it, for the picker. */
    private val displayNameOf: (String) -> String = { it },
) {

    /**
     * One write this rule or another rule declares, flattened to what the
     * answer needs: where it lands, what it is called, and how far it reaches.
     */
    private data class Write(
        val namespace: String,
        /** Null for [WrittenVariable.Unknowable]: a name built from a template. */
        val name: String?,
        val sample: String,
        /** The writing action's position in this rule, or null for another rule's. */
        val position: Int?,
        /** What the picker calls the writer. */
        val writer: String,
    )

    /**
     * Computed once and only when something asks. The editor builds one of
     * these per field it draws, so construction has to stay free; every list
     * below is the same for the whole rule, and only [at] depends on the point.
     */
    private val fromTrigger: List<ScopedVariable> by lazy {
        availableVariables(trigger, variablesOf)
    }

    private val actionTypes: List<String> by lazy { actions.map { it.type } }

    private val writes: List<Write> by lazy { collectWrites() }

    private fun collectWrites(): List<Write> {
        val here = actions.flatMapIndexed { index, spec ->
            writesOf(spec.type).mapNotNull { declaration ->
                declaration.writes(spec.config)?.toWrite(
                    position = index,
                    writer = "${displayNameOf(spec.type)} (action ${index + 1})",
                )
            }
        }
        val elsewhere = otherRules.flatMap { rule ->
            rule.variableWrites(writesOf)
                .filter { it.namespace == VariableScope.APP }
                .map { it.toWrite(position = null, writer = "the rule '${rule.name}'") }
        }
        return here + elsewhere
    }

    private fun WrittenVariable.toWrite(position: Int?, writer: String): Write = when (this) {
        is WrittenVariable.Named -> Write(namespace, name, sample, position, writer)
        is WrittenVariable.Unknowable -> Write(namespace, null, "", position, writer)
    }

    /**
     * Everything readable at [point], in the order the picker draws it: what
     * the engine supplies first, then what an earlier action produced, then the
     * three scopes a rule writes for itself.
     */
    fun at(point: ReadPoint): List<ScopedVariable> =
        fromTrigger +
            outputsAt(point) +
            offered(VariableScope.APP, savedAppVariables, point) +
            offered(VariableScope.MINE, savedRuleVariables, point) +
            offered(VariableScope.LOCAL, emptyList(), point)

    /**
     * What is wrong with the references in [value] badly enough to refuse the
     * save. See [variableProblems], which this only supplies the list to.
     */
    fun problems(value: String, point: ReadPoint): List<String> =
        variableProblems(value, at(point))

    /**
     * What a person should be told about [value] without being stopped.
     *
     * A reference to a name nothing writes and nothing holds is almost always a
     * typo, and a rule that silently does nothing is the failure this project is
     * built against. It is still not a refusal: the rule that writes the value
     * may not be written yet, the value may be set by hand on the saved values
     * screen, and a rule run by another rule reads that rule's run values. So
     * this says what it sees, next to the field, and the save goes through.
     *
     * Silent for a scope this rule writes under a name built from a template.
     * Nobody can know that name before the rule fires, so no name in that scope
     * can be called wrong, and a warning on every one of them would be noise
     * that teaches a person to ignore the warning that matters.
     */
    fun warnings(value: String, point: ReadPoint): List<String> {
        val references = parseTemplate(value).references
            .filter { it.scope in VariableScope.writable }
        if (references.isEmpty()) return emptyList()

        val offered = at(point)
        fun isOffered(scope: String, name: String) =
            offered.any { it.scope == scope && it.spec.key == name }

        return references
            .filterNot { isOffered(it.scope, it.name) }
            .filterNot { unknowable(it.scope, point) }
            .distinctBy { it.reference }
            .map { reference ->
                // The same name in a scope this rule does write is the mistake
                // worth naming, because it is the one a person makes by
                // choosing "this run only" in the writing action and reading
                // {{app.x}} in the next one. Both halves look right on their own.
                val elsewhere = VariableScope.writable
                    .filterNot { it == reference.scope }
                    .firstOrNull { isOffered(it, reference.name) }
                val hint = elsewhere
                    ?.let { " This rule has {{$it.${reference.name}}}. Did you mean that one?" }
                    .orEmpty()
                missing(reference, point) + hint
            }
    }

    private fun missing(reference: VariableRef, point: ReadPoint): String =
        when (reference.scope) {
            VariableScope.LOCAL -> if (point is ReadPoint.Action) {
                "No action above this one sets ${reference.reference}. A 'this run only' " +
                    "value must be set earlier in the same run."
            } else {
                "A trigger cannot read ${reference.reference}. A 'this run only' value " +
                    "exists only while the actions run."
            }

            VariableScope.MINE ->
                "Nothing in this rule sets ${reference.reference}, and this rule has no " +
                    "saved value of that name. Check the name, or add an action that sets it."

            else ->
                "Nothing sets ${reference.reference}, and no saved value has that name. " +
                    "Check the name, or set the value in Saved values."
        }

    /**
     * `{{action.*}}` and the per-action namespaces, which only an action can
     * read and only from above itself. See [availableActionOutputs].
     */
    private fun outputsAt(point: ReadPoint): List<ScopedVariable> = when (point) {
        is ReadPoint.Trigger -> emptyList()
        is ReadPoint.Action -> availableActionOutputs(actionTypes, point.index, variablesOf)
    }

    /**
     * One writable scope, as the store holds it and as this rule writes it,
     * merged into one entry per name.
     *
     * Merged rather than concatenated because the picker lists a reference
     * once. A name that is both stored and written keeps the store's sample,
     * which is a real value, and gains the sentence saying this rule writes it,
     * which is what a person cannot see anywhere else.
     */
    private fun offered(
        namespace: String,
        saved: List<ScopedVariable>,
        point: ReadPoint,
    ): List<ScopedVariable> {
        val merged = LinkedHashMap<String, ScopedVariable>()
        saved.forEach { merged[it.spec.key] = it }
        val described = mutableSetOf<String>()

        for (write in writes) {
            val name = write.name ?: continue
            if (write.namespace != namespace) continue
            if (!reaches(write, point)) continue
            // The first write of a name is the one named, and the writes are in
            // the order they run. Naming the last would point a person at the
            // action furthest from where the value first appears.
            if (!described.add(name)) continue
            val stored = merged[name]
            val spec = stored?.spec ?: VariableSpec(
                key = name,
                label = name,
                kind = VariableKind.TEXT,
                sample = write.sample,
            )
            merged[name] = ScopedVariable(
                namespace,
                spec.copy(
                    // Never always present, for the reason an action output is
                    // never always present: the writing action can fail before
                    // it writes, and a mode that clears stores nothing at all.
                    alwaysPresent = false,
                    help = help(write, stored = stored != null, namespace = namespace),
                ),
            )
        }

        return merged.values.sortedBy { it.spec.key }
    }

    /**
     * How far one write reaches, which is the whole substance of this file.
     *
     * A run value is gone when the run ends, so only an action after the writing
     * one can read it. A rule value and an app value both survive the run, so an
     * action *before* the writing one reads it too: not on the first run, where
     * it is unset, but on every run after that. Refusing that would refuse the
     * ordinary shape of a counter, which reads the total it wrote last time.
     */
    private fun reaches(write: Write, point: ReadPoint): Boolean = when (write.namespace) {
        VariableScope.LOCAL ->
            write.position != null &&
                point is ReadPoint.Action &&
                write.position < point.index

        // This rule's own writes only. Another rule's are filtered out when the
        // writes are collected, because the scope is keyed by rule id.
        VariableScope.MINE -> write.position != null

        else -> true
    }

    /**
     * Whether this rule writes [namespace] under a name that cannot be known
     * before it fires, within reach of [point]. See [warnings].
     */
    private fun unknowable(namespace: String, point: ReadPoint): Boolean =
        writes.any { it.namespace == namespace && it.name == null && reaches(it, point) }

    private fun help(write: Write, stored: Boolean, namespace: String): String {
        val set = "Set by ${write.writer}."
        if (!stored) return set
        val already = if (namespace == VariableScope.MINE) {
            "This rule already has a value of this name."
        } else {
            "A saved value of this name already exists."
        }
        return "$set $already"
    }
}
