package app.phueber.trigly.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * The portable representation of rules.
 *
 * Serves two jobs deliberately, so there is one format to get right rather than
 * two that can disagree:
 *
 *  - **Export and import**, which is how rules survive a new phone. Android's
 *    Auto Backup cannot be relied on for that — it needs a Google account and
 *    does not run on de-Googled devices, which is exactly the audience the rest
 *    of this project bends over backwards for. An explicit file the user owns is
 *    the only mechanism that always works, and it doubles as a way to share a
 *    rule with someone else.
 *  - **The `config` column** in the local database, via [encodeConfig].
 *
 * JSON via `org.json` rather than a serialization library: config maps are flat
 * `String` to `String`, `JSONObject` is in `android.jar`, and it handles the
 * escaping that hand-rolling would get wrong. If the shape ever stops being flat,
 * kotlinx-serialization is the upgrade.
 *
 * Every failure is an [IllegalArgumentException] naming what was wrong and where.
 * Import is the one place where a user hands this app a file written by something
 * else, possibly an older or newer version of itself, so "invalid" has to be a
 * sentence rather than a stack trace.
 */
object RuleJson {

    /**
     * Bumped when the shape changes incompatibly. A file from a *newer* version
     * is refused rather than half-read — losing a rule silently is worse than
     * failing to import.
     */
    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_RULES = "rules"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_TYPE = "type"
    private const val KEY_CONFIG = "config"

    // --- export -----------------------------------------------------------

    /** One rule, for sharing a single automation. */
    fun encode(rule: Rule): String = encode(listOf(rule))

    /** A whole rule set, for moving to a new phone. Indented so it is diffable. */
    fun encode(rules: List<Rule>): String = JSONObject()
        .put(KEY_VERSION, VERSION)
        .put(KEY_RULES, JSONArray(rules.map(::ruleToJson)))
        .toString(2)

    private fun ruleToJson(rule: Rule): JSONObject = JSONObject()
        .put(KEY_ID, rule.id)
        .put(KEY_NAME, rule.name)
        .put(KEY_ENABLED, rule.enabled)
        .put(KEY_TRIGGER, specToJson(rule.trigger))
        .put(KEY_ACTIONS, JSONArray(rule.actions.map(::specToJson)))

    private fun specToJson(spec: ComponentSpec): JSONObject = JSONObject()
        .put(KEY_TYPE, spec.type)
        .put(KEY_CONFIG, JSONObject(spec.config.toMap()))

    // --- import -----------------------------------------------------------

    /**
     * @throws IllegalArgumentException with a message fit to show the user.
     */
    fun decode(text: String): List<Rule> {
        val root = try {
            JSONObject(text)
        } catch (malformed: JSONException) {
            throw IllegalArgumentException("That file is not valid JSON.", malformed)
        }

        val version = root.optInt(KEY_VERSION, -1)
        require(version != -1) { "That file has no version field — it was not exported by Trigly." }
        require(version <= VERSION) {
            "That file was exported by a newer version of Trigly (format $version, " +
                "this build understands $VERSION). Update the app first."
        }

        val array = root.optJSONArray(KEY_RULES)
            ?: throw IllegalArgumentException("That file contains no 'rules' list.")

        return (0 until array.length()).map { index ->
            val obj = array.optJSONObject(index)
                ?: throw IllegalArgumentException("Rule ${index + 1} is not an object.")
            ruleFromJson(obj, index + 1)
        }
    }

    private fun ruleFromJson(json: JSONObject, position: Int): Rule {
        val name = json.optString(KEY_NAME).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Rule $position has no name.")

        val triggerJson = json.optJSONObject(KEY_TRIGGER)
            ?: throw IllegalArgumentException("Rule $position ('$name') has no trigger.")

        val actionsJson = json.optJSONArray(KEY_ACTIONS)
            ?: throw IllegalArgumentException("Rule $position ('$name') has no actions list.")

        val actions = (0 until actionsJson.length()).map { i ->
            val obj = actionsJson.optJSONObject(i)
                ?: throw IllegalArgumentException(
                    "Action ${i + 1} of rule $position ('$name') is not an object."
                )
            specFromJson(obj, "action ${i + 1} of rule $position ('$name')")
        }

        return Rule(
            // A missing id is tolerated — a hand-written or hand-edited file is a
            // legitimate way to author rules, and the id carries no meaning.
            id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: newId(),
            name = name,
            trigger = specFromJson(triggerJson, "the trigger of rule $position ('$name')"),
            actions = actions,
            // Absent means enabled: a rule someone chose to export is one they use.
            enabled = json.optBoolean(KEY_ENABLED, true),
        )
    }

    private fun specFromJson(json: JSONObject, where: String): ComponentSpec {
        val type = json.optString(KEY_TYPE).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("No type given for $where.")

        return ComponentSpec(type = type, config = configFromJson(json.optJSONObject(KEY_CONFIG)))
    }

    // --- config, also used by the database --------------------------------

    fun encodeConfig(config: Map<String, String>): String =
        JSONObject(config.toMap()).toString()

    fun decodeConfig(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val obj = try {
            JSONObject(json)
        } catch (malformed: JSONException) {
            throw IllegalArgumentException("Stored config is not valid JSON.", malformed)
        }
        return configFromJson(obj)
    }

    private fun configFromJson(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()

        return buildMap {
            obj.keys().forEach { key ->
                // Values are read as strings whatever they were written as, so a
                // hand-written file with `"threshold": 20` works as well as "20".
                put(key, obj.optString(key))
            }
        }
    }

    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Fresh ids for imported rules, so importing never overwrites something the user
 * already has. Merging by id is a deliberate feature, not a default — a rule set
 * from another phone should arrive alongside, not on top.
 */
fun List<Rule>.withFreshIds(): List<Rule> = map { it.copy(id = RuleJson.newId()) }
