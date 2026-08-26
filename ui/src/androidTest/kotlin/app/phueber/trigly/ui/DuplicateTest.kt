package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.triggerFactories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a duplicated rule carries, and what it must not.
 *
 * An instrumented test rather than a JVM one for the same reason the editor's
 * own tests are: the answer depends on the real component schemas, and the
 * factories need a `Context`. A stub registry would let the interesting case
 * through, since the interesting case is a real component that owns an
 * identifier.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val registry = Registry(
        triggerFactories = triggerFactories(context, AlarmManagerScheduler(context)),
        actionFactories = actionFactories(context, NotificationController.Unavailable),
    )

    private fun rule(trigger: TriggerNode) = Rule(
        id = "original",
        name = "Morning",
        trigger = trigger,
        actions = listOf(ComponentSpec("toast", mapOf("text" to "up"))),
        enabled = true,
        folder = "Car",
    )

    @Test
    fun a_copy_has_a_new_id_and_a_name_that_says_it_is_a_copy() {
        val copy = rule(TriggerNode.One(ComponentSpec("screen_state"))).duplicated(registry)

        assertNotEquals("original", copy.id)
        assertTrue(copy.id.isNotBlank())
        assertEquals("Morning copy", copy.name)
    }

    /**
     * Off, whatever the original was. A copy of an enabled rule that acts on the
     * world would otherwise start doing so before anyone changed the part they
     * duplicated it to change.
     */
    @Test
    fun a_copy_is_switched_off() {
        val copy = rule(TriggerNode.One(ComponentSpec("screen_state"))).duplicated(registry)

        assertFalse(copy.enabled)
    }

    @Test
    fun a_copy_keeps_the_folder_and_the_actions() {
        val copy = rule(TriggerNode.One(ComponentSpec("screen_state"))).duplicated(registry)

        assertEquals("Car", copy.folder)
        assertEquals(1, copy.actions.size)
        assertEquals("toast", copy.actions.single().type)
        assertEquals("up", copy.actions.single().config["text"])
    }

    @Test
    fun a_copy_keeps_the_whole_trigger_tree_including_nested_groups() {
        val original = rule(
            TriggerNode.Group(
                TriggerNode.Op.ALL,
                listOf(
                    TriggerNode.One(ComponentSpec("screen_state")),
                    TriggerNode.Group(
                        TriggerNode.Op.ANY,
                        listOf(
                            TriggerNode.One(ComponentSpec("power_connection")),
                            TriggerNode.One(ComponentSpec("battery_level")),
                        ),
                    ),
                ),
            )
        )

        val copy = original.duplicated(registry)

        val root = copy.trigger as TriggerNode.Group
        assertEquals(TriggerNode.Op.ALL, root.op)
        val inner = root.children[1] as TriggerNode.Group
        assertEquals(TriggerNode.Op.ANY, inner.op)
        assertEquals(
            listOf("power_connection", "battery_level"),
            inner.children.map { (it as TriggerNode.One).spec.type },
        )
    }

    /**
     * The one value a copy must not share. The shortcut trigger fires on any tap
     * whose id matches its own, so two rules holding one id means one home screen
     * shortcut starts both of them, which is not a thing anyone would look for in
     * a duplicate.
     */
    @Test
    fun a_copy_gets_a_new_shortcut_id() {
        val original = rule(
            TriggerNode.One(
                ComponentSpec(
                    "shortcut",
                    mapOf("shortcutId" to "the-original-id", "label" to "Go", "icon" to "🚗"),
                )
            )
        )

        val copy = original.duplicated(registry)

        val spec = (copy.trigger as TriggerNode.One).spec
        assertNotEquals("the-original-id", spec.config["shortcutId"])
        assertTrue(spec.config["shortcutId"]!!.isNotBlank())
        // Everything a person chose is still there. Only the identity moved.
        assertEquals("Go", spec.config["label"])
        assertEquals("🚗", spec.config["icon"])
    }

    /** The same, for a shortcut nested inside a group rather than at the root. */
    @Test
    fun a_new_shortcut_id_is_minted_at_any_depth() {
        val original = rule(
            TriggerNode.Group(
                TriggerNode.Op.ANY,
                listOf(
                    TriggerNode.One(ComponentSpec("screen_state")),
                    TriggerNode.One(
                        ComponentSpec("shortcut", mapOf("shortcutId" to "deep", "label" to "Go"))
                    ),
                ),
            )
        )

        val copy = original.duplicated(registry)

        val nested = ((copy.trigger as TriggerNode.Group).children[1] as TriggerNode.One).spec
        assertNotEquals("deep", nested.config["shortcutId"])
    }

    /**
     * Two copies of one rule must not collide with each other either, which is
     * the case a single-copy test would pass while still sharing one id.
     */
    @Test
    fun two_copies_do_not_share_a_shortcut_id() {
        val original = rule(
            TriggerNode.One(ComponentSpec("shortcut", mapOf("shortcutId" to "same", "label" to "Go")))
        )

        val first = original.duplicated(registry)
        val second = original.duplicated(registry)

        assertNotEquals(
            (first.trigger as TriggerNode.One).spec.config["shortcutId"],
            (second.trigger as TriggerNode.One).spec.config["shortcutId"],
        )
        assertNotEquals(first.id, second.id)
    }

    /**
     * A component this build does not know keeps its config untouched. With no
     * schema there is no way to tell which key is an identity, and inventing one
     * would corrupt a rule that a build with the component installed could run.
     */
    @Test
    fun an_unknown_component_keeps_its_config() {
        val original = rule(
            TriggerNode.One(ComponentSpec("from_a_newer_build", mapOf("someId" to "keep-me")))
        )

        val copy = original.duplicated(registry)

        assertEquals(
            "keep-me",
            (copy.trigger as TriggerNode.One).spec.config["someId"],
        )
    }
}
