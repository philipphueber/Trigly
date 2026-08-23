package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two newest picker fields: a sound and a paired Bluetooth device.
 *
 * Both are supplied their lists rather than reading the device, for the reason
 * [AppPickerTest] gives — the sounds and pairings on an emulator vary by image,
 * and asserting against them would test the image. What is worth asserting is the
 * behaviour the pickers exist for: the friendly name is what you see, the stored
 * value is what gets reported, and a value the device does not know still shows.
 */
@RunWith(AndroidJUnit4::class)
class SoundAndDevicePickerTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val sounds = listOf(
        DeviceSound("content://media/internal/audio/media/7", "Argon"),
        DeviceSound("content://media/internal/audio/media/9", "Krypton"),
    )

    private val devices = listOf(
        PairedDevice("00:11:22:33:44:55", "Car"),
        PairedDevice("AA:BB:CC:DD:EE:FF", "Headphones"),
    )

    private val picked = mutableListOf<String?>()

    @Composable
    private fun SoundField(field: ConfigField.SoundUri, value: String?) {
        CompositionLocalProvider(LocalDeviceSounds provides sounds) {
            ConfigFieldEditor(field = field, value = value, onValueChange = { picked += it })
        }
    }

    @Composable
    private fun DeviceField(field: ConfigField.BluetoothAddress, value: String?) {
        CompositionLocalProvider(LocalPairedDevices provides devices) {
            ConfigFieldEditor(field = field, value = value, onValueChange = { picked += it })
        }
    }

    private val soundField = ConfigField.SoundUri(
        key = "soundUri",
        label = "Custom sound",
        blankMeaning = "Use the tone above",
    )

    private val deviceField = ConfigField.BluetoothAddress(
        key = "address",
        label = "Device",
        blankMeaning = "Any device",
    )

    // --- sounds ---

    @Test
    fun an_unset_sound_says_what_blank_means() {
        composeRule.setContent { SoundField(soundField, value = null) }

        composeRule.onNodeWithText("USE THE TONE ABOVE").assertIsDisplayed()
    }

    @Test
    fun picking_a_sound_reports_its_uri_not_its_title() {
        composeRule.setContent { SoundField(soundField, value = null) }

        composeRule.onNodeWithText("USE THE TONE ABOVE").performClick()
        composeRule.onNodeWithText("KRYPTON").performClick()

        assertEquals(listOf("content://media/internal/audio/media/9"), picked)
    }

    @Test
    fun a_stored_sound_shows_its_title_rather_than_its_uri() {
        composeRule.setContent {
            SoundField(soundField, value = "content://media/internal/audio/media/7")
        }

        composeRule.onNodeWithText("ARGON").assertIsDisplayed()
        // The URI is the whole reason this is a picker; showing it as well would
        // put the unreadable string back on screen.
        composeRule.onNodeWithText("content://media/internal/audio/media/7")
            .assertDoesNotExist()
    }

    /** An imported rule, or a sound since deleted, must not render as nothing. */
    @Test
    fun a_sound_this_device_does_not_have_still_shows_itself() {
        composeRule.setContent { SoundField(soundField, value = "content://gone/1") }

        composeRule.onNodeWithText("CONTENT://GONE/1").assertIsDisplayed()
    }

    @Test
    fun the_sound_list_can_be_searched() {
        composeRule.setContent { SoundField(soundField, value = null) }
        composeRule.onNodeWithText("USE THE TONE ABOVE").performClick()

        composeRule.onNodeWithText("SEARCH SOUNDS").performTextReplacement("kry")

        composeRule.onNodeWithText("KRYPTON").assertIsDisplayed()
        composeRule.onNodeWithText("ARGON").assertDoesNotExist()
    }

    // --- paired devices ---

    @Test
    fun picking_a_device_reports_its_address_not_its_name() {
        composeRule.setContent { DeviceField(deviceField, value = null) }

        composeRule.onNodeWithText("ANY DEVICE").performClick()
        composeRule.onNodeWithText("HEADPHONES").performClick()

        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), picked)
    }

    @Test
    fun a_stored_address_shows_the_device_name_and_the_address() {
        composeRule.setContent { DeviceField(deviceField, value = "00:11:22:33:44:55") }

        composeRule.onNodeWithText("CAR").assertIsDisplayed()
        // Unlike a sound URI, the address is worth showing: it is short, and it is
        // what distinguishes two devices with the same name.
        composeRule.onNodeWithText("00:11:22:33:44:55").assertIsDisplayed()
    }

    /**
     * Paired devices are a convenience, not the set of devices that can connect —
     * so an address that is not in the list has to remain reachable.
     */
    @Test
    fun an_address_that_is_not_paired_can_still_be_typed() {
        composeRule.setContent { DeviceField(deviceField, value = null) }
        composeRule.onNodeWithText("ANY DEVICE").performClick()

        composeRule.onNodeWithText("SEARCH OR TYPE AN ADDRESS")
            .performTextReplacement("de:ad:be:ef:00:01")
        composeRule.onNodeWithText("USE \"DE:AD:BE:EF:00:01\"").performClick()

        assertEquals(listOf("DE:AD:BE:EF:00:01"), picked)
    }

    @Test
    fun the_blank_row_is_offered_so_the_picker_is_not_a_one_way_door() {
        composeRule.setContent { DeviceField(deviceField, value = "00:11:22:33:44:55") }

        composeRule.onNodeWithText("CAR").performClick()
        composeRule.onNodeWithText("ANY DEVICE").performClick()

        assertEquals(listOf<String?>(null), picked)
    }

    // --- the typed-address guard, which has to reject a search term ---

    @Test
    fun a_plausible_address_is_accepted_in_any_case() {
        assertTrue(looksLikeABluetoothAddress("00:11:22:33:44:55"))
        assertTrue(looksLikeABluetoothAddress("aa:bb:cc:dd:ee:ff"))
        assertTrue(looksLikeABluetoothAddress("  AA:BB:CC:DD:EE:FF  "))
    }

    @Test
    fun something_typed_to_search_is_not_offered_as_an_address() {
        // One field is both the search box and the manual entry, so anything a
        // person would type to filter must not look like a value.
        assertFalse(looksLikeABluetoothAddress("head"))
        assertFalse(looksLikeABluetoothAddress("Headphones"))
        assertFalse(looksLikeABluetoothAddress(""))
        // Right shape, wrong length or characters.
        assertFalse(looksLikeABluetoothAddress("00:11:22:33:44"))
        assertFalse(looksLikeABluetoothAddress("00:11:22:33:44:5"))
        assertFalse(looksLikeABluetoothAddress("ZZ:11:22:33:44:55"))
    }
}
