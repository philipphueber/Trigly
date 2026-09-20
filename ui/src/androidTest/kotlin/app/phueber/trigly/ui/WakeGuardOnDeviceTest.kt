package app.phueber.trigly.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.DelayAction
import app.phueber.trigly.actions.wakeTimeoutMillis
import app.phueber.trigly.triggers.PowerManagerWakeGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real wake lock, on a device, because nothing about `PowerManager` can be
 * checked on the JVM.
 *
 * **What these tests cannot prove, stated first so the passing green is not
 * read as more than it is.** An emulator does not suspend its host CPU when
 * the screen goes off, so a plain coroutine `delay` completes on time there
 * with or without a lock. That means these tests cannot fail the code this
 * change replaced, and they cannot show that the change fixes the reported
 * fault. What they prove is that the mechanism is wired: the permission
 * merged, the lock is real, it is held while the work runs, and it is gone
 * afterwards. The fix itself is checked by hand on a locked phone off charge;
 * `docs/releasing.md` and `DelayAction`'s KDoc say what that looks like.
 *
 * The leak is the part worth catching automatically. A partial wake lock that
 * is never released holds the CPU until the process dies, costs a user their
 * battery, and produces no crash, no log line and no visible symptom.
 */
@RunWith(AndroidJUnit4::class)
class WakeGuardOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val power = context.getSystemService(PowerManager::class.java)

    private val guard = PowerManagerWakeGuard(context)

    /**
     * Without this the lock throws `SecurityException` at the moment a rule is
     * running, which is both the worst place to find out and a place no unit
     * test looks. The permission is declared in `:triggers`, so this also
     * checks that the manifest merger carried it into the app.
     */
    @Test
    fun the_wake_lock_permission_reached_the_merged_manifest() {
        assertEquals(
            "WAKE_LOCK is not granted, so every short wait would throw",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.WAKE_LOCK),
        )
    }

    @Test
    fun the_guard_holds_a_real_lock_for_the_length_of_the_block_and_no_longer() = runTest {
        // `isHeld` is asked of the platform's own lock rather than of a
        // counter this test keeps, so a guard that acquired nothing would fail
        // here rather than pass by bookkeeping.
        var heldInside = false

        guard.keepingAwake("test", timeoutMillis = 5_000) {
            heldInside = power?.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK) == true &&
                currentTriglyLocksAreHeld()
            delay(50)
        }

        assertTrue("the CPU was not held while the block ran", heldInside)
        assertTrue("the lock outlived its block", !currentTriglyLocksAreHeld())
    }

    @Test
    fun a_cancelled_block_still_gives_the_lock_back() = runTest {
        // The shape TriggerEngine.stopRule produces when a rule is disabled
        // mid-wait. A lock left held here is the battery fault.
        val thrown = runCatching {
            guard.keepingAwake("test", timeoutMillis = 5_000) {
                error("the block failed the way a cancelled or throwing action would")
            }
        }

        assertTrue("the failure should propagate", thrown.isFailure)
        assertTrue("a failed block must not keep the CPU", !currentTriglyLocksAreHeld())
    }

    /**
     * The timeout the action asks for, checked against the real constant
     * rather than a literal, so a change to either is a change to both.
     */
    @Test
    fun the_backstop_outlasts_the_longest_wait_that_uses_it() {
        val worst = wakeTimeoutMillis(DelayAction.AWAKE_WAIT_MAX_MILLIS)

        assertTrue("the backstop must clear the wait it backs", worst > DelayAction.AWAKE_WAIT_MAX_MILLIS)
        assertEquals(35_000L, worst)
    }

    /**
     * Whether this process holds any partial lock tagged for this app.
     *
     * `dumpsys power` is the only public way to ask, and an instrumented test
     * may run it. The tag is the one `PowerManagerWakeGuard` sets, which is
     * also what a person reads when they want to know why a phone stayed
     * awake.
     */
    private fun currentTriglyLocksAreHeld(): Boolean {
        val dump = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("dumpsys power")
            .let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    stream.readBytes().decodeToString()
                }
            }

        return dump.lineSequence()
            .filter { it.contains("PARTIAL_WAKE_LOCK") }
            .any { it.contains("trigly:") }
    }
}
