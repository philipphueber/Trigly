package app.phueber.trigly.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one part of the engine that no JVM test can see: whether the process
 * survives being left alone.
 *
 * This drives the *real* app — the real `TriglyApp`, the real Room database, the
 * real service — because everything worth checking here is a system decision.
 * Whether a foreground service may be started, whether the declared service type
 * is accepted, and whether the service outlives the activity that started it are
 * all answered by the platform, and answered differently by different OEM
 * builds. A test double would only prove the double behaves as written.
 *
 * It therefore writes to the device's actual rule store. Every rule it creates
 * carries [TEST_RULE_PREFIX] and is removed again in [tearDown], because a
 * leftover enabled rule would keep the service alive and make the *second* run
 * of this test fail where the first passed.
 */
@RunWith(AndroidJUnit4::class)
class EngineServiceTest {

    private val app = ApplicationProvider.getApplicationContext<TriglyApp>()
    private val repository get() = app.container.ruleRepository

    @Before
    fun setUp() {
        grantNotifications()
        removeTestRules()
        // A rule the device's owner built would legitimately keep the engine
        // running, which the "stops itself" half of this test reads as a
        // failure. Skipping is honest; deleting someone's rules is not.
        assumeTrue(
            "device has rules of its own enabled",
            currentRules().none { it.enabled },
        )
        awaitService(running = false)
    }

    @After
    fun tearDown() {
        removeTestRules()
        awaitService(running = false)
    }

    @Test
    fun the_engine_starts_for_an_enabled_rule_and_outlives_the_screen() {
        runBlocking { repository.upsert(testRule()) }

        // Started through the app's own path rather than by calling
        // EngineService.start directly: a visible activity is what makes the
        // foreground-service start legal on API 31 and up, and that is exactly
        // the path a user takes.
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitService(running = true)
        }

        // The whole point of the service. The activity is gone; the engine is not.
        assertTrue("the engine stopped with the activity", isServiceRunning())
    }

    /**
     * The engine still starts when it claims the `location` type, which is the
     * one path the rest of this class cannot reach.
     *
     * Every other test here runs with location ungranted, so the service claims
     * `specialUse` alone and the interesting branch never executes. The moment a
     * user grants location the claim becomes `specialUse|location`, and from API
     * 34 a bad claim does not degrade: `startForeground` throws, the service
     * dies, and *every* rule stops. Granting location would take the whole app
     * down, which is a far worse bug than the one this change fixes and would
     * reach only the users who did what the app asked them to do.
     *
     * Granted through `uiAutomation` rather than a dialog, and the permission is
     * left granted afterwards: revoking a runtime permission kills the process,
     * which would take the rest of the suite with it.
     */
    @Test
    fun the_engine_starts_while_claiming_the_location_type() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_FINE_LOCATION)

        runBlocking { repository.upsert(testRule()) }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitService(running = true)
        }

        assertTrue("the engine did not survive claiming the location type", isServiceRunning())
    }

    @Test
    fun the_engine_stops_itself_when_no_rule_is_enabled() {
        runBlocking { repository.upsert(testRule()) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitService(running = true)
        }

        runBlocking { repository.upsert(testRule(enabled = false)) }

        // An ongoing notification for a service watching nothing is a cost with
        // no benefit, so the service ends itself.
        awaitService(running = false)
    }

    /**
     * A reboot is the case that cannot be rehearsed from inside a test —
     * `BOOT_COMPLETED` is a protected broadcast, so the test app cannot send one.
     * What *can* be checked is the half that actually breaks: that the receiver
     * survived the manifest merge and is registered for it.
     */
    @Test
    fun the_boot_receiver_is_registered() {
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName)
        val replaced = Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName)

        listOf(boot, replaced).forEach { intent ->
            val receivers = app.packageManager.queryBroadcastReceivers(intent, 0)
            assertTrue(
                "no receiver registered for ${intent.action}",
                receivers.any { it.activityInfo.name == BootReceiver::class.java.name },
            )
        }
    }

    /**
     * From API 34 a foreground service without a declared type throws at
     * `startForeground`, and a type the app has no permission for throws too.
     * Asserted directly rather than left to the tests above, because those only
     * cover it on a device new enough to enforce it *and* only report it as "the
     * service never started".
     */
    @Test
    fun the_service_declares_the_special_use_type() {
        assumeTrue("foreground service types arrived in API 34", Build.VERSION.SDK_INT >= 34)

        val info = app.packageManager.getServiceInfo(
            ComponentName(app, EngineService::class.java),
            0,
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /**
     * The declaration the area check depends on, and the one nothing else would
     * catch.
     *
     * A position read answers only while the app counts as in use, and off
     * screen the `location` service type is what makes it count. Without the
     * type the engine keeps running and every rule that ANDs a trigger with an
     * area check simply stops firing, which is the failure this test exists to
     * make loud: it looks exactly like being outside the area.
     *
     * Asserts the manifest, not the claimed types. What the running service
     * claims depends on whether this device granted location, so asserting that
     * would make the test's meaning depend on the device's permission state.
     * The manifest is what the app promises, and it is the half that can be
     * broken by an edit.
     */
    @Test
    fun the_service_declares_the_location_type() {
        assumeTrue("foreground service types arrived in API 34", Build.VERSION.SDK_INT >= 34)

        val info = app.packageManager.getServiceInfo(
            ComponentName(app, EngineService::class.java),
            0,
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    // --- helpers ---

    /**
     * `MainActivity` asks for `POST_NOTIFICATIONS` on a fresh launch, and a
     * permission dialog on top of it would leave `ActivityScenario` waiting for
     * a RESUMED state that never arrives. Granting it up front removes the
     * dialog, and makes the ongoing notification actually get posted, which is
     * the case worth exercising anyway.
     */
    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun testRule(enabled: Boolean = true) = Rule(
        id = TEST_RULE_ID,
        name = "engine service test",
        // A trigger and an action that need no permission and no special access,
        // so the rule builds on any device: what is under test is the service,
        // not the components.
        trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
        actions = listOf(ComponentSpec("toast", mapOf("text" to "engine service test"))),
        enabled = enabled,
    )

    private fun currentRules(): List<Rule> = runBlocking { repository.rules().first() }

    private fun removeTestRules() = runBlocking {
        currentRules()
            .filter { it.id.startsWith(TEST_RULE_PREFIX) }
            .forEach { repository.delete(it.id) }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        // Deprecated, and since Android 8 it returns only the caller's own
        // services — which is all this needs, and is the only way to ask the
        // system (rather than a static flag we set ourselves) whether the
        // service is alive.
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
        fail(
            "EngineService was ${if (running) "not running" else "still running"} " +
                "after ${timeoutMillis}ms"
        )
    }

    private companion object {
        const val TEST_RULE_PREFIX = "engine-service-test-"
        const val TEST_RULE_ID = TEST_RULE_PREFIX + "1"
        const val POLL_MILLIS = 100L
    }
}
