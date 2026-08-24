package app.phueber.trigly.core.storage

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Gate
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson

/** Roles a component plays in a rule. Stored as text so the table stays readable. */
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
     * The gate's condition tree as JSON, or null when the rule has none — which
     * is every rule written before gates existed. See `MIGRATION_1_2` for why a
     * tree is stored as JSON rather than in the flat components table.
     */
    val conditionsJson: String? = null,
)

/**
 * A trigger or action belonging to a rule.
 *
 * One table for both, because they are the same shape — a type string and a
 * config map — and because `:core` deliberately knows nothing about which types
 * exist. [ordinal] is what makes action *order* durable, which matters: a rule
 * runs its actions in sequence.
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
 * screen. A rule with no trigger row cannot happen through the repository, but
 * it can happen through a partial restore or a hand-edited database.
 */
fun RuleWithComponents.toRuleOrNull(): Rule? {
    // Ordered, because the first level is an ordered list the editor can
    // rearrange — and because a rule that came back with its edges shuffled
    // would read as a different rule.
    val triggers = components
        .filter { it.role == ComponentRole.TRIGGER }
        .sortedBy { it.ordinal }
        .map { it.toSpec() }
        .ifEmpty { return null }

    val actions = components
        .filter { it.role == ComponentRole.ACTION }
        .sortedBy { it.ordinal }
        .map { it.toSpec() }

    return Rule(
        id = rule.id,
        name = rule.name,
        gate = Gate(
            triggers = triggers,
            // Unreadable conditions drop to null rather than losing the rule, the
            // same trade `toSpec` makes for config. The rule then fires
            // unconditionally, which is worth being uneasy about — but the
            // alternative is a rule that vanishes from the list, and a rule the
            // user can see and fix beats one they cannot.
            conditions = rule.conditionsJson?.let { json ->
                runCatching { RuleJson.decodeConditions(json) }.getOrNull()
            },
        ),
        actions = actions,
        enabled = rule.enabled,
    )
}

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
    conditionsJson = gate.conditions?.let(RuleJson::encodeConditions),
)

fun Rule.toComponentEntities(): List<ComponentEntity> = buildList {
    // One row per edge. The ordinal is what makes the first level's order durable,
    // exactly as it already does for actions.
    gate.triggers.forEachIndexed { index, spec ->
        add(
            ComponentEntity(
                ruleId = id,
                role = ComponentRole.TRIGGER,
                ordinal = index,
                type = spec.type,
                configJson = RuleJson.encodeConfig(spec.config),
            )
        )
    }
    actions.forEachIndexed { index, spec ->
        add(
            ComponentEntity(
                ruleId = id,
                role = ComponentRole.ACTION,
                ordinal = index,
                type = spec.type,
                configJson = RuleJson.encodeConfig(spec.config),
            )
        )
    }
}
