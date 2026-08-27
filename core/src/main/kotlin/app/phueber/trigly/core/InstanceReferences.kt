package app.phueber.trigly.core

/**
 * Keeping `{{...}}` references pointing at the component they were written for
 * when the numbering underneath them moves.
 *
 * [componentInstanceNames] numbers a component by its position among the
 * components of its own type, which makes the namespace readable and needs no
 * stored identity, and has one consequence that has to be handled rather than
 * accepted: a position is not stable. Delete the first of three
 * `notification_posted` leaves and the old third one becomes the second, so a
 * saved `{{notification_posted_2.title}}` silently starts reading a different
 * trigger. The grammar cannot catch it, because the reference still resolves
 * against a real leaf. Save-time validation cannot catch it either, for the
 * same reason.
 *
 * So the editor rewrites instead. It is the only place that knows both the
 * before and the after of a delete or a reorder, which is exactly what turning
 * an old namespace into a new one needs. Everything here is pure and takes that
 * before-and-after explicitly, so the rule is tested rather than trusted.
 *
 * The rewrite is a *repair*, not a feature a person asks for. It never changes
 * what a rule means: it changes the text so the meaning survives an edit to
 * something else.
 */

/**
 * Old namespace to new namespace, for the components that survive an edit.
 *
 * [oldTypes] is the component list as it was, in order. [survivors] holds the
 * indices into [oldTypes] that still exist afterwards, in their new order. So a
 * delete of index 1 out of four is `listOf(0, 2, 3)`, and swapping the first
 * two of three is `listOf(1, 0, 2)`.
 *
 * Only entries whose name actually changed are returned, so an edit that moves
 * nothing produces an empty map and the caller can skip the rewrite entirely.
 * That is the common case: most components are the only one of their type in
 * the rule, and their namespace is their bare type whatever happens around them.
 *
 * A deleted component's namespace is deliberately **not** in the result. There
 * is nothing to point it at, and inventing a target would silently repoint a
 * reference at a component the person never named. It is left to dangle, which
 * is the one case save-time validation does see: a name nobody offers.
 */
fun instanceRenames(oldTypes: List<String>, survivors: List<Int>): Map<String, String> {
    val oldNames = componentInstanceNames(oldTypes)
    val newNames = componentInstanceNames(survivors.map { oldTypes[it] })

    return survivors.withIndex()
        .mapNotNull { (newIndex, oldIndex) ->
            val from = oldNames[oldIndex]
            val to = newNames[newIndex]
            if (from == to) null else from to to
        }
        .toMap()
}

/**
 * [value] with every reference's namespace remapped through [renames].
 *
 * **One pass, and never a chain.** Applying the renames one after another would
 * be wrong in exactly the case they are needed for: `{A_2 to A, A_3 to A_2}`
 * applied in sequence takes `A_3` to `A_2` and then on to `A`, so two
 * references end up naming one component. Each namespace is therefore looked up
 * once, against the original text.
 *
 * Only the namespace is touched. The name, the pipe, the fallback text and
 * every character outside a reference are copied through exactly as written, so
 * this cannot damage a field it does not understand. An unbalanced `{{` stays
 * unbalanced, which is what [parseTemplate] already promises about it.
 */
fun rewriteInstanceReferences(value: String, renames: Map<String, String>): String {
    if (renames.isEmpty() || value.isEmpty()) return value

    return REFERENCE_HEAD.replace(value) { match ->
        val (open, scope, close) = match.destructured
        val target = renames[scope] ?: scope
        "{{$open$target$close."
    }
}

/**
 * The renames that keep a rule working when it gains a second trigger leaf.
 *
 * With one leaf the picker offers `{{trigger.title}}` and nothing else, because
 * with one leaf that is unambiguous. With two it offers the numbered forms and
 * *not* the short one, because the short one can no longer say which payload
 * arrives. That is the intended behaviour, and on its own it would mean adding
 * a second trigger to a working rule turns its saved `{{trigger.title}}` into a
 * name nobody offers, so the next save is refused with nothing the person did
 * wrong to point at.
 *
 * [existingLeafType] is the type of the leaf that was already there. Its
 * namespace is its bare type, because it is the first of its type in the rule
 * whatever is added after it. Rewriting `trigger` to that name preserves the
 * reference's meaning exactly: it named the only leaf that could fire, and now
 * it names that same leaf by name.
 *
 * Empty when there was no leaf to preserve, so the caller can skip the rewrite.
 */
fun shortFormRenames(existingLeafType: String?): Map<String, String> =
    existingLeafType?.let { mapOf(VariableScope.TRIGGER to it) }.orEmpty()

/**
 * The opening of a reference, up to and including the dot: `{{`, optional
 * space, the namespace, optional space, `.`.
 *
 * Matched rather than parsed because a rewrite has to put back everything it
 * did not come to change, and re-rendering a parsed [Template] would have to
 * reproduce the fallback text and the exact spacing to do that. Matching the
 * head leaves the rest of the reference untouched by construction.
 */
private val REFERENCE_HEAD = Regex("""\{\{(\s*)([A-Za-z0-9_]+)(\s*)\.""")
