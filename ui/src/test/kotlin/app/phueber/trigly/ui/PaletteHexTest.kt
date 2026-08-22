package app.phueber.trigly.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The palette's one piece of logic.
 *
 * Every colour in the app goes through [hex] at startup, so a bug here is not a
 * wrong shade in one place — it is the whole theme. The cases that matter are
 * the ones where a plausible string could silently produce a *valid but wrong*
 * colour, because that is what the helper exists to prevent.
 */
class PaletteHexTest {

    @Test
    fun `a six digit colour is opaque`() {
        // The trap this helper exists for: the equivalent Compose literal
        // written without an alpha pair, Color(0x9F3D00), is transparent black
        // and compiles fine.
        assertEquals(Color(0xFF9F3D00), hex("#9F3D00"))
        assertEquals(1f, hex("#9F3D00").alpha, 0.001f)
    }

    @Test
    fun `shorthand expands the way CSS does`() {
        assertEquals(hex("#FFFFFF"), hex("#FFF"))
        assertEquals(hex("#AABBCC"), hex("#ABC"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals(hex("#FFB68F"), hex("#ffb68f"))
    }

    @Test
    fun `the eight digit form puts alpha last, as the web does`() {
        // Half-transparent orange. Android's packed form would be 0x809F3D00 —
        // alpha first — so getting this backwards is the failure that would make
        // a copied web colour mean something else here.
        val translucent = hex("#9F3D0080")
        assertEquals(0x80 / 255f, translucent.alpha, 0.005f)
        assertEquals(hex("#9F3D00").red, translucent.red, 0.001f)
        assertEquals(hex("#9F3D00").green, translucent.green, 0.001f)
        assertEquals(hex("#9F3D00").blue, translucent.blue, 0.001f)
    }

    @Test
    fun `fully transparent and fully opaque both survive the round trip`() {
        assertEquals(0f, hex("#00000000").alpha, 0.001f)
        assertEquals(1f, hex("#000000FF").alpha, 0.001f)
    }

    @Test
    fun `black and white are exact`() {
        assertEquals(Color.Black, hex("#000000"))
        assertEquals(Color.White, hex("#FFFFFF"))
    }

    @Test
    fun `a missing hash is rejected`() {
        // Rejected rather than tolerated: accepting both spellings invites a
        // file where half the colours have the prefix and half do not.
        assertThrows(IllegalArgumentException::class.java) { hex("9F3D00") }
    }

    @Test
    fun `a wrong length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { hex("#9F3D0") }
        assertThrows(IllegalArgumentException::class.java) { hex("#9F3D000") }
        assertThrows(IllegalArgumentException::class.java) { hex("#") }
    }

    @Test
    fun `a non hex digit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { hex("#GGGGGG") }
        assertThrows(IllegalArgumentException::class.java) { hex("#9F3D0 ") }
    }

    /**
     * Touching [Tone] parses every constant in the palette. If any of them were
     * malformed the app would die on its first frame, so this is worth one cheap
     * test rather than a crash report.
     */
    @Test
    fun `every declared tone parses and is opaque`() {
        val tones = listOf(
            Tone.Orange10, Tone.Orange20, Tone.Orange30, Tone.Orange40, Tone.Orange50,
            Tone.Orange60, Tone.Orange80, Tone.Orange90, Tone.Orange95,
            Tone.Warm10, Tone.Warm20, Tone.Warm30, Tone.Warm40, Tone.Warm80, Tone.Warm90,
            Tone.Olive10, Tone.Olive20, Tone.Olive30, Tone.Olive40, Tone.Olive80, Tone.Olive90,
            Tone.Paper, Tone.Ink, Tone.InkDeep,
            Tone.Neutral10, Tone.Neutral14, Tone.Neutral20, Tone.Neutral90, Tone.Neutral96,
            Tone.White,
            Tone.BlockLight, Tone.BlockLightAlt, Tone.BlockDark, Tone.BlockDarkAlt,
            Tone.Red10, Tone.Red20, Tone.Red30, Tone.Red40, Tone.Red80, Tone.Red90,
            Tone.Amber20, Tone.Amber30, Tone.Amber40, Tone.Amber80, Tone.Amber90, Tone.Amber95,
        )

        tones.forEach { assertEquals("a tone was left translucent: $it", 1f, it.alpha, 0.001f) }
    }

    /**
     * The window background is declared twice — once here and once in the
     * `colors.xml` resources — because the framework paints it before Compose
     * exists. Nothing enforces that the two agree, so this states the values the
     * XML is expected to carry; if you change a tone, this fails and tells you
     * the XML needs the same edit.
     */
    @Test
    fun `the two window background tones are the ones the XML mirrors`() {
        assertEquals(hex("#FFF8F5"), Tone.Paper)
        assertEquals(hex("#17100C"), Tone.Ink)
    }
}
