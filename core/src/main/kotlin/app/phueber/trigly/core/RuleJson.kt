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
 *  - **Export and import**, which is how rules survive a new phone. Auto Backup
 *    needs a Google account and a backup transport, and neither exists on a
 *    de-Googled device, which is exactly the audience the rest of this project
 *    bends over backwards for. An explicit file the user owns is the only
 *    mechanism that always works there, whatever the app's own backup setting
 *    says. See `TriglyBackupAgent` in `:ui` for that setting. It doubles as a
 *    way to share a rule with someone else.
 *  - **The `config` column** in the local database, via [encodeConfig], and the
 *    trigger-tree column, via [encodeNode].
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
 *
 * ### Three formats, one file
 *
 * - **v1** (0.0.1–0.0.3): `"trigger"` holds a single component spec.
 * - **v2** (0.0.4, alongside v1): `"triggers"` (a list) or `"trigger"` (one spec)
 *   plus an optional `"conditions"` tree, whose nodes were
 *   `{"node": "check"|"all"|"any", ...}`.
 * - **v3** (this build): [Rule.trigger] is a single [TriggerNode], and `"trigger"`
 *   holds it directly. A leaf is exactly a component spec — `{"type", "config"}`
 *   — so it is byte-identical to what v1 wrote; a group is
 *   `{"op": "all"|"any", "children": [...]}`. The reader tells them apart by
 *   whether `"op"` is present, which is also why a leaf must never gain a
 *   discriminator of its own.
 *
 * Every file a released version wrote must still import, forever. v2's
 * `triggers` + `conditions` split is read by folding it into the equivalent
 * [TriggerNode] tree — see [legacyTriggerNode].
 *
 * `"folder"` (this build) is additive on top of whichever of the three shapes
 * above a rule is written in — see [VERSION]'s kdoc for why it does not earn
 * a v4.
 */
object RuleJson {

    /**
     * Bumped when the shape changes incompatibly. A file from a *newer* version
     * is refused rather than half-read — losing a rule silently is worse than
     * failing to import.
     *
     * `"folder"` does not bump this. No released build has ever written v4 —
     * 0.0.4 was pulled before it shipped — so there is no file on a real device
     * today that a bump would even be protecting against. And an unbumped
     * version is the *more* correct choice here regardless: `"folder"` is read
     * with [org.json.JSONObject.optString], not required, so a build that has
     * never heard of it just sees a rule with no folder — it loses the
     * grouping and keeps the rule, which is the right failure. Bumping VERSION
     * would instead make an older build refuse the *whole file* over one key
     * it does not need in order to read everything else correctly.
     */
    const val VERSION = 3

    /**
     * The version a rule set is *written* as, which is not always [VERSION].
     *
     * A rule set of plain single-trigger rules is written as version 1 in the
     * version-1 shape, so an export stays importable by an older build for as
     * long as no rule in it uses a group. Any rule that does forces the whole
     * file to version 3.
     *
     * This deliberately never writes version 2, even for a tree that a v2 file
     * could have expressed (one level of edges, optionally ANDed with a
     * condition tree). Detecting "is this [TriggerNode] shaped like the old
     * edges-plus-conditions split" is real logic — a second grouping operator,
     * ALL vs ANY at the top, nesting depth — and it is logic a v2-shaped tree
     * built by hand or by re-import could get subtly wrong in a way that would
     * only surface as a corrupted re-export. v2 shipped for exactly one release.
     * The promise this codec has to keep is that it *reads* every file an old
     * build wrote, not that a fresh export from today opens in a build from
     * last week. So: v1 if it fits byte-for-byte, v3 otherwise, nothing in
     * between.
     */
    private fun versionFor(rules: List<Rule>): Int =
        if (rules.all { it.trigger is TriggerNode.One }) 1 else 3

    private const val KEY_VERSION = "version"
    private const val KEY_RULES = "rules"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_TRIGGERS = "triggers"
    private const val KEY_CONDITIONS = "conditions"
    private const val KEY_NODE = "node"
    private const val KEY_OP = "op"
    private const val KEY_CHILDREN = "children"
    private const val NODE_CHECK = "check"
    private const val VAL_ALL = "all"
    private const val VAL_ANY = "any"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_TYPE = "type"
    private const val KEY_CONFIG = "config"
    private const val KEY_FOLDER = "folder"

    // --- export -----------------------------------------------------------

    /** One rule, for sharing a single automation. */
    fun encode(rule: Rule): String = encode(listOf(rule))

    /** A whole rule set, for moving to a new phone. Indented so it is diffable. */
    fun encode(rules: List<Rule>): String = JSONObject()
        .put(KEY_VERSION, versionFor(rules))
        .put(KEY_RULES, JSONArray(rules.map(::ruleToJson)))
        .toString(2)

    /**
     * A plain trigger (`One`, no group) writes as exactly the spec object v1
     * wrote — no wrapper, no discriminator — which is what keeps an ordinary
     * export byte-comparable with a pre-gate build's output. A [TriggerNode.Group]
     * writes as `{"op", "children"}`; see [nodeToJson].
     */
    private fun ruleToJson(rule: Rule): JSONObject = JSONObject()
        .put(KEY_ID, rule.id)
        .put(KEY_NAME, rule.name)
        .put(KEY_ENABLED, rule.enabled)
        .put(KEY_TRIGGER, nodeToJson(rule.trigger))
        .put(KEY_ACTIONS, JSONArray(rule.actions.map(::specToJson)))
        .let { json ->
            // Normalized again here rather than trusting `rule.folder` was
            // already clean — see `Rule`'s kdoc. A rule with no folder writes
            // no key at all, not `"folder": null`, so an export of ungrouped
            // rules stays byte-identical to what a build before this one wrote.
            normalizeFolder(rule.folder)?.let { folder -> json.put(KEY_FOLDER, folder) }
            json
        }

    private fun nodeToJson(node: TriggerNode): JSONObject = when (node) {
        is TriggerNode.One -> specToJson(node.spec)
        is TriggerNode.Group -> JSONObject()
            .put(KEY_OP, if (node.op == TriggerNode.Op.ALL) VAL_ALL else VAL_ANY)
            .put(KEY_CHILDREN, JSONArray(node.children.map(::nodeToJson)))
    }

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
            ruleFromJson(obj, index + 1, version)
        }
    }

    private fun ruleFromJson(json: JSONObject, position: Int, version: Int): Rule {
        val name = json.optString(KEY_NAME).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Rule $position has no name.")

        val actionsJson = json.optJSONArray(KEY_ACTIONS)
            ?: throw IllegalArgumentException("Rule $position ('$name') has no actions list.")

        val actions = (0 until actionsJson.length()).map { i ->
            val obj = actionsJson.optJSONObject(i)
                ?: throw IllegalArgumentException(
                    "Action ${i + 1} of rule $position ('$name') is not an object."
                )
            specFromJson(obj, "action ${i + 1} of rule $position ('$name')")
        }

        // v1 and v2 share a reader: v1 is simply the case where there is a
        // single `trigger` spec and no `conditions`, which the same mapping
        // handles without a special case.
        val trigger = if (version <= 2) {
            legacyTriggerNode(json, position, name)
        } else {
            val triggerJson = json.optJSONObject(KEY_TRIGGER)
                ?: throw IllegalArgumentException("Rule $position ('$name') has no trigger.")
            nodeFromJson(triggerJson, "the trigger of rule $position ('$name')")
        }

        return Rule(
            // A missing id is tolerated — a hand-written or hand-edited file is a
            // legitimate way to author rules, and the id carries no meaning.
            id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: newId(),
            name = name,
            trigger = trigger,
            actions = actions,
            // Absent means enabled: a rule someone chose to export is one they use.
            enabled = json.optBoolean(KEY_ENABLED, true),
            // Missing key (an older export, or v1/v2), present-but-blank (a
            // hand-edited file), and a real name all funnel through the same
            // normalization — see `Rule`'s kdoc for why blank must not survive
            // as a second spelling of "no folder".
            folder = normalizeFolder(json.optString(KEY_FOLDER)),
        )
    }

    /**
     * Folds the pre-gate two-part shape — a list of edges plus a separate
     * condition tree — into the single [TriggerNode] it meant.
     *
     * This mapping *is* the definition of what the old shape meant, which is
     * why it is tested against hand-written v2 documents rather than only a
     * round trip: a round trip through this file's own encoder would never
     * exercise the shape it is meant to translate.
     *
     * - The edges become `One(spec)` if there was exactly one, else
     *   `Group(ANY, ones)` — the old model ran a rule when any edge fired.
     * - Each condition node becomes `check` → `One`, `all` → `Group(ALL, …)`,
     *   `any` → `Group(ANY, …)`.
     * - If there were no conditions, the edges node is the whole trigger. If
     *   there were, the trigger is `Group(ALL, [edgesNode, conditionsNode])` —
     *   the old model required every condition to hold on top of an edge
     *   firing, which is exactly ALL of the two halves.
     */
    private fun legacyTriggerNode(json: JSONObject, position: Int, name: String): TriggerNode {
        val triggersJson = json.optJSONArray(KEY_TRIGGERS)
        val triggerJson = json.optJSONObject(KEY_TRIGGER)
        if (triggersJson == null && triggerJson == null) {
            throw IllegalArgumentException("Rule $position ('$name') has no trigger.")
        }

        val edges = when {
            triggersJson != null -> (0 until triggersJson.length()).map { i ->
                val obj = triggersJson.optJSONObject(i)
                    ?: throw IllegalArgumentException(
                        "Trigger ${i + 1} of rule $position ('$name') is not an object."
                    )
                specFromJson(obj, "trigger ${i + 1} of rule $position ('$name')")
            }
            else -> listOf(
                specFromJson(triggerJson!!, "the trigger of rule $position ('$name')")
            )
        }
        require(edges.isNotEmpty()) {
            "Rule $position ('$name') has an empty trigger list."
        }

        val edgesNode: TriggerNode = if (edges.size == 1) {
            TriggerNode.One(edges.single())
        } else {
            TriggerNode.Group(TriggerNode.Op.ANY, edges.map(TriggerNode::One))
        }

        val conditionsNode = json.optJSONObject(KEY_CONDITIONS)?.let { obj ->
            legacyConditionNode(obj, "rule $position ('$name')")
        }

        return conditionsNode?.let { TriggerNode.Group(TriggerNode.Op.ALL, listOf(edgesNode, it)) }
            ?: edgesNode
    }

    /**
     * Reads one v2 condition node into the [TriggerNode] it means.
     *
     * An unknown node kind is an error rather than a skip. Dropping a node would
     * change what the rule *means* — losing an `all` branch makes a rule fire in
     * cases its author excluded — and a rule that silently does more than it was
     * told is worse than an import that stops and says why.
     */
    private fun legacyConditionNode(json: JSONObject, where: String): TriggerNode {
        fun children(): List<TriggerNode> {
            val array = json.optJSONArray(KEY_CHILDREN)
                ?: throw IllegalArgumentException("A group in $where has no children.")
            return (0 until array.length()).map { i ->
                val obj = array.optJSONObject(i)
                    ?: throw IllegalArgumentException(
                        "Child ${i + 1} of a group in $where is not an object."
                    )
                legacyConditionNode(obj, where)
            }
        }

        return when (val node = json.optString(KEY_NODE)) {
            NODE_CHECK -> TriggerNode.One(specFromJson(json, "a condition of $where"))
            VAL_ALL -> TriggerNode.Group(TriggerNode.Op.ALL, children())
            VAL_ANY -> TriggerNode.Group(TriggerNode.Op.ANY, children())
            else -> throw IllegalArgumentException(
                "Unknown condition kind '$node' in $where."
            )
        }
    }

    /**
     * Reads one v3 trigger node. A group has an `"op"` key; a leaf does not — see
     * the class doc for why a leaf must never grow a discriminator that would
     * make v1 files unreadable as leaves.
     *
     * An empty `"children"` array is accepted rather than refused. It used to be
     * refused here: an editor that could only ever build a group with something
     * in it made an empty one look like proof of a hand-edited or downgraded
     * file. That stopped being true once a rule could be saved before its
     * trigger was finished. See [NO_TRIGGER] and `RuleDraft.toRuleOrNull` in
     * `:ui`: an empty group is exactly how that state is spelled, and this same
     * function is what [decodeNode] calls to read it back out of the database
     * column, not only what [decode] calls to read a file. Refusing it here
     * would stop a deliberately unfinished rule from surviving the read it is
     * written to survive. [TriggerNode.canStart] still reads an empty group as
     * unable to start a rule, exactly as before, so nothing downstream needs a
     * second opinion about what "empty" means.
     */
    private fun nodeFromJson(json: JSONObject, where: String): TriggerNode {
        if (!json.has(KEY_OP)) {
            return TriggerNode.One(specFromJson(json, where))
        }

        val op = when (val opValue = json.optString(KEY_OP)) {
            VAL_ALL -> TriggerNode.Op.ALL
            VAL_ANY -> TriggerNode.Op.ANY
            else -> throw IllegalArgumentException("Unknown group operator '$opValue' in $where.")
        }
        val childrenJson = json.optJSONArray(KEY_CHILDREN)
            ?: throw IllegalArgumentException("A group in $where has no children.")
        val children = (0 until childrenJson.length()).map { i ->
            val obj = childrenJson.optJSONObject(i)
                ?: throw IllegalArgumentException(
                    "Child ${i + 1} of the group in $where is not an object."
                )
            nodeFromJson(obj, "child ${i + 1} of the group in $where")
        }
        return TriggerNode.Group(op, children)
    }

    /** The trigger tree on its own, for the database column. */
    fun encodeNode(node: TriggerNode): String = nodeToJson(node).toString()

    /** @throws IllegalArgumentException on anything unreadable. */
    fun decodeNode(json: String): TriggerNode {
        val obj = try {
            JSONObject(json)
        } catch (malformed: JSONException) {
            throw IllegalArgumentException("Stored trigger is not valid JSON.", malformed)
        }
        return nodeFromJson(obj, "the stored trigger")
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
 * The same rule, with every [ConfigField.GeneratedId] value in its trigger tree
 * and its actions replaced by a freshly minted id.
 *
 * Lives here, next to [Registry] rather than in `:ui` where it started, because
 * a second caller needs the exact same walk: `ui`'s `duplicated()` mints fresh
 * ids for a copy of a rule the user already owns, and importing a rule file
 * mints fresh ids for a rule that came from outside the app. A rule is a
 * program either way, and the reasoning that follows applies to both callers,
 * not only to the one it was written for.
 *
 * A generated id identifies the rule to something outside it. A home screen
 * shortcut carries the `shortcutId` its trigger listens for, and that trigger
 * fires on any tap whose id matches. A shortcut's own id can be read out of an
 * exported file by anyone who has that file: the file is plain JSON with no
 * secret in it. Keeping the id on import would let anyone who has read the
 * file fire the rule, not only the person who imported it.
 * [ConfigField.GeneratedId] is already the declared marker for "the editor
 * mints this, a person never types it", so this walks every component and
 * mints each one again. Declared rather than a list of known keys, for the
 * reason the field type exists: a new component with an id of its own must not
 * have to edit this function.
 *
 * A component the registry does not know keeps its config unchanged. That is
 * the honest answer rather than the safe-looking one: with no schema there is
 * no way to tell which of its keys is an identity, and inventing a value for a
 * key this build does not understand would corrupt a rule that a build with
 * the component installed could still run.
 */
fun Rule.withFreshGeneratedIds(registry: Registry): Rule = copy(
    trigger = trigger.withFreshGeneratedIds(registry),
    actions = actions.map { it.withFreshGeneratedIds(registry.actionDescriptor(it.type)) },
)

private fun TriggerNode.withFreshGeneratedIds(registry: Registry): TriggerNode = when (this) {
    is TriggerNode.One ->
        TriggerNode.One(spec.withFreshGeneratedIds(registry.triggerDescriptor(spec.type)))

    is TriggerNode.Group ->
        copy(children = children.map { it.withFreshGeneratedIds(registry) })
}

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

/**
 * This rule as it should exist on this device once accepted from a rule file.
 *
 * A rule file is a program: an enabled rule with notification access and the
 * internet permission can act on arrival with no new prompt, since those are
 * either an install-time permission this app already holds or a grant its user
 * has likely already given some other rule. So this is stricter than a plain
 * duplicate, in the two ways that matter for a file written by somebody else
 * rather than a copy of a rule the user already had running:
 *
 * **Always switched off, whatever the file says.** A duplicate is already
 * switched off for the same reason. See `duplicated()` in `:ui`. But here the
 * choice is not even the exporter's to make. `RuleJson.decode` reads a missing
 * `enabled` key as true, on the theory that "a rule someone chose to export is
 * one they use". That is a fair reading of *their* intent. It says nothing
 * about whether *this* device's owner has looked at the rule yet. This
 * function is the one place that turns that reading back off before the rule
 * ever reaches storage.
 *
 * **A fresh rule id, on top of the fresh generated ids [withFreshGeneratedIds]
 * already mints.** Fresh ids mean an import can never silently overwrite
 * something the user already has. Merging by id is a deliberate feature, not
 * a default: a rule set from another phone should arrive alongside, not on
 * top.
 *
 * **The name is left exactly as it came.** This is not a copy, and a name
 * somebody else chose is not this function's to edit.
 */
fun Rule.imported(registry: Registry): Rule = withFreshGeneratedIds(registry).copy(
    id = RuleJson.newId(),
    enabled = false,
)
