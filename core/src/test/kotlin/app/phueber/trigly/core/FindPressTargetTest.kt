package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "The button is visible and does nothing when pressed."
 *
 * That is not a scan that failed to find the button — it is a press that landed
 * on the wrong node. A notification's action button is a non-clickable
 * `TextView` inside a clickable container, so clicking the node carrying the
 * words returns false and changes nothing, with no error anywhere. These tests
 * pin the walk from the label to the thing that will actually take the click,
 * and pin it *not* guessing when there is nothing above it.
 *
 * Worth having on the JVM: on a device this is only observable by watching a
 * shade open and nothing happen.
 */
class FindPressTargetTest {

    @Test
    fun `a label inside a clickable container presses the container`() {
        val container = node(id = "container", clickable = true)
        val label = node(id = "label", text = "BEENDEN")
        container.add(label)

        assertEquals("container", findPressTarget(container, "BEENDEN")?.id())
    }

    @Test
    fun `a clickable label presses itself`() {
        val root = node(id = "root")
        val button = node(id = "button", text = "MELDEN", clickable = true)
        root.add(button)

        assertEquals("button", findPressTarget(root, "MELDEN")?.id())
    }

    @Test
    fun `it walks past intermediate non-clickable wrappers`() {
        // Real shades nest several layers between the row and the text.
        val row = node(id = "row", clickable = true)
        val wrapper = node(id = "wrapper")
        val inner = node(id = "inner")
        val label = node(id = "label", text = "BEENDEN")
        row.add(wrapper); wrapper.add(inner); inner.add(label)

        assertEquals("row", findPressTarget(row, "BEENDEN")?.id())
    }

    @Test
    fun `it stops at the nearest clickable ancestor, not the outermost`() {
        // The outermost clickable node in a shade is usually the notification
        // itself, and clicking that opens the app instead of pressing a button.
        val notification = node(id = "notification", clickable = true)
        val button = node(id = "button", clickable = true)
        val label = node(id = "label", text = "BEENDEN")
        notification.add(button); button.add(label)

        assertEquals("button", findPressTarget(notification, "BEENDEN")?.id())
    }

    @Test
    fun `an icon-only button is found by its content description`() {
        val container = node(id = "container", clickable = true)
        container.add(node(id = "icon", description = "Dismiss"))

        assertEquals("container", findPressTarget(container, "Dismiss")?.id())
    }

    @Test
    fun `matching ignores case and surrounding space`() {
        // OEM layouts upper-case button text in the view, not the string, and
        // padding sneaks into content descriptions.
        val container = node(id = "container", clickable = true)
        container.add(node(id = "label", text = "  beenden "))

        assertEquals("container", findPressTarget(container, "BEENDEN")?.id())
    }

    @Test
    fun `a label with nothing clickable above it is refused`() {
        // Cause 3 in full: a custom RemoteViews layout that never marks anything
        // clickable. Returning the label would produce a press that silently
        // does nothing and a rule that reports success.
        val root = node(id = "root")
        root.add(node(id = "label", text = "BEENDEN"))

        assertNull(findPressTarget(root, "BEENDEN"))
    }

    @Test
    fun `a label that is not there is refused rather than approximated`() {
        val root = node(id = "root", clickable = true)
        root.add(node(id = "label", text = "MELDEN"))

        assertNull(findPressTarget(root, "BEENDEN"))
    }

    @Test
    fun `a partial match is not a match`() {
        // "BEENDEN" must not match "BEENDEN UND LÖSCHEN": pressing a different
        // button than the one named is the failure this whole path avoids.
        val root = node(id = "root", clickable = true)
        root.add(node(id = "label", text = "BEENDEN UND LÖSCHEN"))

        assertNull(findPressTarget(root, "BEENDEN"))
    }

    @Test
    fun `an empty label matches nothing`() {
        val root = node(id = "root", clickable = true)
        root.add(node(id = "label", text = ""))

        assertNull(findPressTarget(root, "   "))
    }

    @Test
    fun `the first matching subtree wins in a shade holding several rows`() {
        val shade = node(id = "shade")
        val first = node(id = "first-row", clickable = true)
        first.add(node(id = "first-label", text = "BEENDEN"))
        val second = node(id = "second-row", clickable = true)
        second.add(node(id = "second-label", text = "BEENDEN"))
        shade.add(first); shade.add(second)

        assertEquals("first-row", findPressTarget(shade, "BEENDEN")?.id())
    }
}

// --- a tree that can be built in a line -------------------------------------

private class FakeNode(
    val id: String,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val isClickable: Boolean = false,
) : UiNode {
    private val children = mutableListOf<FakeNode>()
    private var parentNode: FakeNode? = null

    fun add(child: FakeNode) {
        child.parentNode = this
        children += child
    }

    override val childCount: Int get() = children.size
    override fun child(index: Int): UiNode? = children.getOrNull(index)
    override val parent: UiNode? get() = parentNode
}

private fun node(
    id: String,
    text: String? = null,
    description: String? = null,
    clickable: Boolean = false,
) = FakeNode(id = id, text = text, contentDescription = description, isClickable = clickable)

private fun UiNode.id(): String = (this as FakeNode).id
