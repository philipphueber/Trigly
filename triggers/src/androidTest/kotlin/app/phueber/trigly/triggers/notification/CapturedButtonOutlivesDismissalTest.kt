package app.phueber.trigly.triggers.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Whether a button captured off a notification can still be pressed after the
 * notification is gone.
 *
 * The question decides if "press the Pause button on the bedtime notification"
 * can work at all, because that notification gets swiped away like any other.
 * Today [ListenerNotificationController.triggerActionButton] looks the
 * notification up by key in `activeNotifications`, so a dismissed one cannot be
 * pressed. A `PendingIntent`, though, is a token the system holds for the app
 * that made it, and its life is not obviously tied to the notification that
 * carried it.
 *
 * Instrumented and not a unit test, because only the real framework answers it.
 */
@RunWith(AndroidJUnit4::class)
class CapturedButtonOutlivesDismissalTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private val fired = CountDownLatch(1)
    private var receiver: BroadcastReceiver? = null

    private val channelId = "captured-button-test"
    private val notificationId = 4711
    private val buttonAction = "app.phueber.trigly.triggers.test.BUTTON_PRESSED"

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                "android.permission.POST_NOTIFICATIONS",
            )
        }

        shell(
            "cmd notification allow_listener ${context.packageName}/" +
                TriglyNotificationListenerService::class.java.name
        )

        val listening = waitFor(seconds = 20) { NotificationEvents.service != null }
        assertTrue("The listener service never bound, so nothing can be captured.", listening)

        val filter = IntentFilter(buttonAction)
        val target = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = fired.countDown()
        }
        receiver = target
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(target, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(target, filter)
        }

        manager().createNotificationChannel(
            NotificationChannel(channelId, "Captured button", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @After
    fun tearDown() {
        receiver?.let { context.unregisterReceiver(it) }
        manager().cancel(notificationId)
        manager().deleteNotificationChannel(channelId)
        shell(
            "cmd notification disallow_listener ${context.packageName}/" +
                TriglyNotificationListenerService::class.java.name
        )
    }

    /**
     * The finding this test exists for: capture the button, dismiss the
     * notification the way a swipe does, and the captured button still works.
     */
    @Test
    fun a_captured_button_still_fires_after_the_notification_is_dismissed() {
        post(buttonIntent(PendingIntent.FLAG_UPDATE_CURRENT))

        val posted = waitForNotification()
        assertNotNull("The test notification was never posted.", posted)

        // What a rule would keep: the button's own intent, read back out of the
        // posted notification rather than the one this test built.
        val captured = posted!!.notification.actions.first().actionIntent
        assertNotNull("The posted button carried no intent.", captured)

        // The swipe path. `cancelNotification` is the same call the shade makes.
        val key = posted.key
        NotificationEvents.service!!.cancelNotification(key)
        val gone = waitFor(seconds = 10) {
            NotificationEvents.service?.activeNotifications.orEmpty().none { it.key == key }
        }
        assertTrue("The notification was still listed after being dismissed.", gone)

        // Today's behaviour, for contrast: pressing by key cannot work any more.
        val byKey = ListenerNotificationController().triggerActionButton(key, 0)
        assertTrue(
            "Pressing by key should fail once the notification is gone, but got $byKey",
            byKey.toString().contains("Failure"),
        )

        // The claim under test.
        captured!!.send()
        assertTrue(
            "The captured button did not fire after the notification was dismissed.",
            fired.await(10, TimeUnit.SECONDS),
        )
    }

    /**
     * The boundary of the same finding. A captured button is only as good as the
     * owning app lets it be: an app that rebuilds its intent with
     * `FLAG_CANCEL_CURRENT` invalidates every copy anyone holds.
     *
     * Worth pinning down because the failure is loud. `send` throws, so a rule
     * can report "the app withdrew that button" instead of doing nothing and
     * claiming success.
     */
    @Test
    fun a_captured_button_dies_loudly_if_the_owner_rebuilds_it() {
        val captured = buttonIntent(PendingIntent.FLAG_UPDATE_CURRENT)

        // The owning app posts again and cancels what it had before.
        buttonIntent(PendingIntent.FLAG_CANCEL_CURRENT)

        try {
            captured.send()
            fail("Sending a cancelled button should throw, but it returned normally.")
        } catch (expected: PendingIntent.CanceledException) {
            // The outcome a rule can report.
        }
    }

    private fun buttonIntent(flags: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(buttonAction).setPackage(context.packageName),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(button: PendingIntent) {
        val notification = Notification.Builder(context, channelId)
            .setContentTitle("Bedtime mode is on")
            .setContentText("A stand-in for the notification a rule would press.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(Notification.Action.Builder(null, "Turn off for now", button).build())
            .build()
        manager().notify(notificationId, notification)
    }

    private fun waitForNotification() = waitForValue(seconds = 15) {
        NotificationEvents.service?.activeNotifications.orEmpty().firstOrNull {
            it.packageName == context.packageName && it.id == notificationId
        }
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    private fun shell(command: String) {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        // Reading to the end is what lets the command finish.
        java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
    }

    private fun waitFor(seconds: Int, condition: () -> Boolean): Boolean =
        waitForValue(seconds) { if (condition()) true else null } == true

    private fun <T> waitForValue(seconds: Int, read: () -> T?): T? {
        repeat(seconds * 10) {
            read()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }
}
