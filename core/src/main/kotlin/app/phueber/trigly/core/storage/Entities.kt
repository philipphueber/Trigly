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
    val trigger = components.firstOrNull { it.role == ComponentRole.TRIGGER } ?: return null

    val actions = components
        .filter { it.role == ComponentRole.ACTION }
        .sortedBy { it.ordinal }
        .map { it.toSpec() }

    return Rule(
        id = rule.id,
        name = rule.name,
        trigger = trigger.toSpec(),
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
)

fun Rule.toComponentEntities(): List<ComponentEntity> = buildList {
    add(
        ComponentEntity(
            ruleId = id,
            role = ComponentRole.TRIGGER,
            ordinal = 0,
            type = trigger.type,
            configJson = RuleJson.encodeConfig(trigger.config),
        )
    )
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
