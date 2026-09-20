package app.phueber.trigly.ui

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Rule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of the colour scheme that no JVM test can see: whether the colour
 * the engine chose is the colour the platform is actually holding.
 *
 * `SettingsViewModelTest` proves the view model pokes the engine when the
 * scheme changes. This proves the poke arrives somewhere useful, by reading
 * the live notification back out of `NotificationManager` and comparing its
 * colour with the preset that was chosen. That is the part that broke: the
 * tint was correct every time it was computed, and nothing recomputed it.
 *
 * Note what is *not* asserted: how the shade renders that colour. The
 * platform puts it through a contrast adjustment against its own background
 * before painting the small icon, so the pixels on screen are a darker or
 * lighter relative of this value and are the system's business. What belongs
 * to this app is the value it hands over, and that is what this reads.
 *
 * The colour choice is real device state, so [tearDown] puts back whatever
 * was there before. The alias is deliberately left alone: this writes the
 * setting through `ColorSchemeSettings` rather than through the view model,
 * so no launcher icon is switched and [ApplicationIconOnDeviceTest] and
 * [LauncherIconAliasOnDeviceTest] keep the device state they expect.
 */
@RunWith(AndroidJUnit4::class)
class EngineNotificationColorTest {

    private val app = ApplicationProvider.getApplicationContext<TriglyApp>()
    private val repository get() = app.container.ruleRepository
    private val colorSettings get() = app.container.colorSchemeSettings
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    private lateinit var originalChoice: ColorSchemeChoice

    @Before
    fun setUp() {
        grantNotifications()
        originalChoice = colorSettings.colorSchemeChoice()
        removeTestRules()
        // Same reasoning as EngineServiceTest: a rule the device's owner built
        // would keep the engine alive on its own, and deleting it to make a
        // test pass is not an option.
        assumeTrue(
            "device has rules of its own enabled",
            currentRules().none { it.enabled },
        )
        awaitService(running = false)
    }

    @After
    fun tearDown() {
        colorSettings.setColorSchemeChoice(originalChoice)
        removeTestRules()
        awaitService(running = false)
    }

    /**
     * The reported fault, end to end. The scheme changes while the engine is
     * already running and the notification follows, which before the poke it
     * did not do until something unrelated re-posted it.
     *
     * Both halves run while the activity is on screen, because that is both
     * what a person doing this is actually doing and what makes a foreground
     * service start legal from API 31.
     */
    @Test
    fun the_ongoing_notification_follows_a_colour_scheme_change() {
        colorSettings.setColorSchemeChoice(ColorSchemeChoice.Preset(FIRST))
        runBlocking { repository.upsert(testRule()) }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitService(running = true)
            awaitNotificationColor(expected = colorFor(FIRST))

            colorSettings.setColorSchemeChoice(ColorSchemeChoice.Preset(SECOND))
            // Exactly what SettingsViewModel.setColorSchemeChoice does after
            // storing the choice.
            EngineService.start(app)

            awaitNotificationColor(expected = colorFor(SECOND))
        }
    }

    /**
     * The colour is read at build time, not once per process. A service that
     * starts *after* the choice was made has to come up in the new colour
     * too, which is the path a reboot takes.
     */
    @Test
    fun an_engine_started_after_the_change_comes_up_in_the_new_colour() {
        colorSettings.setColorSchemeChoice(ColorSchemeChoice.Preset(SECOND))
        runBlocking { repository.upsert(testRule()) }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitService(running = true)
            awaitNotificationColor(expected = colorFor(SECOND))
        }
    }

    // --- helpers ---

    /**
     * What the engine should be handing the platform: the chosen preset's
     * `primary`, in whichever mode this device is in. Derived through
     * [resolvedColors], the same function `EngineService.notificationColor`
     * calls, rather than from a literal - a second copy of the hex here would
     * turn a preset edit into a failing test with nothing wrong.
     */
    private fun colorFor(presetId: String): Int {
        val darkTheme = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return resolvedColors(app, ColorSchemeChoice.Preset(presetId), darkTheme)
            .colorScheme.primary.toArgb()
    }

    private fun engineNotificationColor(): Int? = notifications.activeNotifications
        .firstOrNull {
            it.id == EngineService.NOTIFICATION_ID &&
                it.notification.channelId == EngineService.CHANNEL_ID
        }
        ?.notification
        ?.color

    /**
     * Polled rather than read once. A start request and the re-post it causes
     * are two hops through the main looper, and the previous notification is
     * still up in between.
     */
    private fun awaitNotificationColor(expected: Int, timeoutMillis: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var seen: Int? = null
        while (SystemClock.uptimeMillis() < deadline) {
            seen = engineNotificationColor()
            if (seen == expected) return
            SystemClock.sleep(POLL_MILLIS)
        }
        assertEquals("the ongoing notification never took the chosen colour", expected, seen)
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun testRule() = Rule(
        id = TEST_RULE_ID,
        name = "engine notification colour test",
        trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
        actions = listOf(ComponentSpec("toast", mapOf("text" to "engine notification colour test"))),
        enabled = true,
    )

    private fun currentRules(): List<Rule> = runBlocking { repository.rules().first() }

    private fun removeTestRules() = runBlocking {
        currentRules()
            .filter { it.id.startsWith(TEST_RULE_PREFIX) }
            .forEach { repository.delete(it.id) }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val manager = app.getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == EngineService::class.java.name }
    }

    private fun awaitService(running: Boolean, timeoutMillis: Long = 15_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isServiceRunning() == running) return
            SystemClock.sleep(POLL_MILLIS)
        }
        assertEquals(
            "EngineService never reached the expected state",
            running,
            isServiceRunning(),
        )
    }

    private companion object {
        const val TEST_RULE_PREFIX = "engine-notification-colour-test-"
        const val TEST_RULE_ID = TEST_RULE_PREFIX + "1"
        const val POLL_MILLIS = 100L

        /**
         * Two presets whose `primary` differs in hue rather than only in
         * lightness, so a wrong answer cannot be mistaken for a right one.
         * Stone and Slate would be the wrong pair for exactly that reason.
         */
        const val FIRST = "lime"
        const val SECOND = "azure"
    }
}
