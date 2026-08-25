package app.phueber.trigly.core.storage

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.normalizeFolder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Roles a component plays in a rule. Stored as text so the table stays readable.
 *
 * [TRIGGER] is legacy and read-only: since database version 3 the trigger side
 * lives entirely in [RuleEntity.triggerJson], and a rule is never again written
 * with `TRIGGER` rows. They are only read — from a row a pre-version-3 install
 * wrote and that has not been saved since — by [toRuleOrNull]'s legacy path. A
 * legacy row heals itself the next time the user saves that rule: [Rule.toEntity]
 * always fills [RuleEntity.triggerJson], so the row is written the new way from
 * then on. Do not add a write path for `TRIGGER` rows back in; do not delete this
 * read path either, or every rule saved before version 3 loses its trigger.
 */
object ComponentRole {
    const val TRIGGER = "TRIGGER"
    const val ACTION = "ACTION"
}

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    /** User-visible ordering of the rule list. */
    val position: Int,
    /**
     * The trigger tree as JSON, via [RuleJson.encodeNode] — a rule's whole
     * [TriggerNode], however deeply it nests. Null only for a row a
     * pre-version-3 install wrote and that has not been saved since; every row
     * this version writes fills it. See `MIGRATION_2_3`.
     */
    val triggerJson: String? = null,
    /**
     * Legacy and read-only since database version 3. Before the trigger tree
     * existed, a rule's trigger side was several `TRIGGER` component rows (an
     * implicit OR) plus this separate condition tree (`check`/`all`/`any`
     * nodes), ANDed onto them. [toRuleOrNull] still reads this column — see its
     * legacy path — to reconstruct that meaning for a row nothing has written
     * since. Nothing writes this column any more: [Rule.toEntity] always fills
     * [triggerJson] instead, so a legacy row heals itself (this column goes
     * back to null) the next time the user saves that rule. Do not resume
     * writing it, and do not remove the read path while a row like this can
     * still exist on a device that has not opened this rule since upgrading.
     */
    val conditionsJson: String? = null,
    /**
     * The user-typed folder the rule list groups by, or null for "no folder" —
     * every row before database version 4 reads back as null here, which is
     * exactly right since nothing before this version had the concept. See
     * `MIGRATION_3_4` and [Rule.folder]'s kdoc.
     */
    val folder: String? = null,
)

/**
 * A trigger or action belonging to a rule.
 *
 * One table for both, because they are the same shape — a type string and a
 * config map — and because `:core` deliberately knows nothing about which types
 * exist. [ordinal] is what makes action *order* durable, which matters: a rule
 * runs its actions in sequence. `TRIGGER` rows are legacy — see
 * [ComponentRole] — and are never written by this version; only [ordinal] on
 * `ACTION` rows is still live.
 *
 * [configJson] is written by [RuleJson.encodeConfig], the same codec used for
 * export, so there is one format to get right rather than two.
 */
@Entity(
    tableName = "components",
    foreignKeys = [
        ForeignKey(
            entity = RuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            // Deleting a rule must not leave its components behind as orphans.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ruleId")],
)
data class ComponentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val role: String,
    val ordinal: Int,
    val type: String,
    val configJson: String,
)

/** A rule with its components, as Room returns it from a single query. */
data class RuleWithComponents(
    @Embedded val rule: RuleEntity,
    @Relation(parentColumn = "id", entityColumn = "ruleId")
    val components: List<ComponentEntity>,
)

/**
 * Maps storage back to the domain, or null if the row cannot make a valid rule.
 *
 * Null rather than an exception on purpose: this runs inside a `Flow` feeding the
 * rule list, and one corrupt row should cost the user that rule, not the whole
 * screen. A rule with no trigger cannot happen through the repository, but it can
 * happen through a partial restore or a hand-edited database.
 */
fun RuleWithComponents.toRuleOrNull(): Rule? {
    // Every row written by this version onward. Only a row nothing has saved
    // since before version 3 falls through to the legacy path below.
    val trigger = rule.triggerJson
        ?.let { json -> runCatching { RuleJson.decodeNode(json) }.getOrNull() }
        ?: legacyTrigger()
        ?: return null

    val actions = components
        .filter { it.role == ComponentRole.ACTION }
        .sortedBy { it.ordinal }
        .map { it.toSpec() }

    return Rule(
        id = rule.id,
        name = rule.name,
        trigger = trigger,
        actions = actions,
        enabled = rule.enabled,
        // Normalized again on the way out — see `Rule`'s kdoc — rather than
        // trusting that every writer already stored a clean value.
        folder = normalizeFolder(rule.folder),
    )
}

/**
 * Rebuilds the trigger tree the pre-version-3 shape meant, for a row that has
 * not been saved since the migration added [RuleEntity.triggerJson].
 *
 * The old model kept two things where there is now one: the `TRIGGER` component
 * rows, in [ComponentEntity.ordinal] order, were an implicit OR of edges; the
 * separate `conditionsJson` tree was ANDed onto that OR. So: one edge alone is
 * [TriggerNode.One]; several are [TriggerNode.Group] with [TriggerNode.Op.ANY];
 * and if there were conditions too, the result is
 * `Group(ALL, [edges, conditions])` — exactly what the old evaluator required
 * before a rule could fire.
 */
private fun RuleWithComponents.legacyTrigger(): TriggerNode? {
    val edgeNodes = components
        .filter { it.role == ComponentRole.TRIGGER }
        .sortedBy { it.ordinal }
        .map { TriggerNode.One(it.toSpec()) }
    if (edgeNodes.isEmpty()) return null

    val edges: TriggerNode =
        if (edgeNodes.size == 1) edgeNodes.single() else TriggerNode.Group(TriggerNode.Op.ANY, edgeNodes)

    // Unreadable conditions degrade to no conditions rather than losing the
    // rule — the same trade `toSpec` makes for a component's config.
    val conditions = rule.conditionsJson?.let { json ->
        runCatching { legacyConditionsFromJson(JSONObject(json)) }.getOrNull()
    }

    return if (conditions == null) edges else TriggerNode.Group(TriggerNode.Op.ALL, listOf(edges, conditions))
}

// --- the pre-version-3 conditions shape, read-only ------------------------
//
// This mirrors the JSON `RuleJson` used to write `conditionsJson` back when a
// gate's conditions were a `ConditionNode` tree separate from its triggers.
// That type is gone from the domain model, so the shape is reproduced here,
// by the literal key strings, rather than shared: these bytes are already on
// real devices and must keep parsing the same way forever, independent of
// whatever the current condition/trigger vocabulary happens to be.

private const val LEGACY_KEY_NODE = "node"
private const val LEGACY_KEY_CHILDREN = "children"
private const val LEGACY_KEY_TYPE = "type"
private const val LEGACY_KEY_CONFIG = "config"
private const val LEGACY_NODE_CHECK = "check"
private const val LEGACY_NODE_ALL = "all"
private const val LEGACY_NODE_ANY = "any"

private fun legacyConditionsFromJson(json: JSONObject): TriggerNode = when (val node = json.optString(LEGACY_KEY_NODE)) {
    LEGACY_NODE_CHECK -> TriggerNode.One(
        ComponentSpec(
            type = json.optString(LEGACY_KEY_TYPE).takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("A legacy 'check' condition has no type."),
            config = legacyConfigFromJson(json.optJSONObject(LEGACY_KEY_CONFIG)),
        ),
    )

    LEGACY_NODE_ALL -> TriggerNode.Group(TriggerNode.Op.ALL, legacyChildrenFromJson(json))
    LEGACY_NODE_ANY -> TriggerNode.Group(TriggerNode.Op.ANY, legacyChildrenFromJson(json))
    else -> throw IllegalArgumentException("Unknown legacy condition kind '$node'.")
}

private fun legacyChildrenFromJson(json: JSONObject): List<TriggerNode> {
    val array: JSONArray = json.optJSONArray(LEGACY_KEY_CHILDREN)
        ?: throw IllegalArgumentException("A legacy condition group has no children.")
    return (0 until array.length()).map { index ->
        legacyConditionsFromJson(
            array.optJSONObject(index)
                ?: throw IllegalArgumentException("Child $index of a legacy condition group is not an object."),
        )
    }
}

private fun legacyConfigFromJson(config: JSONObject?): Map<String, String> =
    runCatching { RuleJson.decodeConfig(config?.toString() ?: "{}") }.getOrDefault(emptyMap())

private fun ComponentEntity.toSpec() = ComponentSpec(
    type = type,
    // Tolerates unreadable config rather than losing the whole rule: an empty
    // map surfaces as a validation error in the editor, which is fixable.
    config = runCatching { RuleJson.decodeConfig(configJson) }.getOrDefault(emptyMap()),
)

fun Rule.toEntity(position: Int) = RuleEntity(
    id = id,
    name = name,
    enabled = enabled,
    position = position,
    triggerJson = RuleJson.encodeNode(trigger),
    // Deliberately left null: this column is legacy and read-only (see its
    // kdoc). Writing it again would resurrect a second source of truth for
    // the trigger side that `triggerJson` already fills.
    // Normalized on the way in — see `Rule`'s kdoc — rather than trusting the
    // caller already did it.
    folder = normalizeFolder(folder),
)

fun Rule.toComponentEntities(): List<ComponentEntity> =
    // ACTION rows only — the trigger side lives entirely in `triggerJson` now.
    // See `ComponentRole.TRIGGER`'s kdoc for why no `TRIGGER` row is written
    // here any more.
    actions.mapIndexed { index, spec ->
        ComponentEntity(
            ruleId = id,
            role = ComponentRole.ACTION,
            ordinal = index,
            type = spec.type,
            configJson = RuleJson.encodeConfig(spec.config),
        )
    }
