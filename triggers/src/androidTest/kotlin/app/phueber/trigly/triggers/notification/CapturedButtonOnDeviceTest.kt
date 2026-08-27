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
import app.phueber.trigly.core.ActionResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keeping a notification button and pressing it after the notification is gone,
 * through the production path rather than through raw framework calls.
 *
 * `CapturedButtonOutlivesDismissalTest` established the platform fact this
 * depends on: a `PendingIntent` outlives the notification that carried it. This
 * asserts that [ListenerNotificationController] and [CapturedButtons] actually
 * deliver that, which is a different claim. The one that would go wrong quietly
 * is capturing the token at press time instead of at capture time: everything
 * would pass while the notification was still up, and fail exactly when the
 * feature is meant to work.
 *
 * The sequence is the real one a pair of rules performs. Post a notification with
 * a button; keep it by name; dismiss the notification the way a swipe does;
 * press what was kept.
 */
@RunWith(AndroidJUnit4::class)
class CapturedButtonOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private val fired = CountDownLatch(1)
    private var receiver: BroadcastReceiver? = null

    private val channelId = "captured-button-on-device"
    private val notificationId = 4712
    private val buttonAction = "app.phueber.trigly.triggers.test.KEPT_BUTTON_PRESSED"

    private val controller = ListenerNotificationController()

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
        // Waited for rather than assumed: the bind is asynchronous, so reading
        // the service straight away fails on a cold device and passes on a warm
        // one, which is the worst of both.
        val listening = waitFor(seconds = 20) { NotificationEvents.service != null }
        assertTrue("The listener service never bound, so nothing can be kept.", listening)

        CapturedButtons.clear()

        val filter = IntentFilter(buttonAction)
        val target = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = fired.countDown()
        }
        receiver = target
        // NOT_EXPORTED: the button's intent is sent by this app to itself, so
        // nothing outside needs to reach this receiver.
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(target, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(target, filter)
        }

        manager().createNotificationChannel(
            NotificationChannel(channelId, "Kept buttons", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @After
    fun tearDown() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        manager().cancel(notificationId)
        manager().deleteNotificationChannel(channelId)
        CapturedButtons.clear()
        shell(
            "cmd notification disallow_listener ${context.packageName}/" +
                TriglyNotificationListenerService::class.java.name
        )
    }

    /**
     * The claim the feature rests on, end to end through the controller.
     */
    @Test
    fun a_kept_button_is_pressed_after_its_notification_is_gone() {
        post()
        val posted = waitForNotification()
        assertNotNull("The test notification was never posted.", posted)
        val key = posted!!.key

        val kept = controller.captureActionButton(key, actionIndex = 0, `as` = "bedtime_off")
        assertTrue("Keeping the button failed: $kept", kept is ActionResult.Success)

        // The swipe path, the same call the shade makes.
        NotificationEvents.service!!.cancelNotification(key)
        val gone = waitFor(seconds = 10) {
            NotificationEvents.service?.activeNotifications.orEmpty().none { it.key == key }
        }
        assertTrue("The notification was still listed after being dismissed.", gone)

        // Pressing by key cannot work now, which is the gap this feature fills.
        val byKey = controller.triggerActionButton(key, 0)
        assertTrue("Pressing by key should fail once it is gone, got $byKey", byKey is ActionResult.Failure)

        val pressed = controller.pressCaptured("bedtime_off")
        assertTrue("Pressing the kept button failed: $pressed", pressed is ActionResult.Success)
        assertTrue(
            "The kept button did not actually fire.",
            fired.await(10, TimeUnit.SECONDS),
        )
    }

    /**
     * A name nothing was kept under must say so, and must say the thing that is
     * actually likely: either the keeping rule has not run, or Trigly restarted.
     * A message about the notification would send somebody looking in the wrong
     * place.
     */
    @Test
    fun an_unknown_name_says_nothing_is_kept_and_why() {
        val pressed = controller.pressCaptured("never_kept")

        assertTrue(pressed is ActionResult.Failure)
        val reason = (pressed as ActionResult.Failure).reason
        assertTrue("was: $reason", reason.contains("Nothing is captured"))
        assertTrue("was: $reason", reason.contains("restarted"))
    }

    /**
     * Keeping the same name twice replaces the first token rather than being
     * refused, because a rule that keeps on every appearance of a notification is
     * the ordinary case.
     */
    @Test
    fun keeping_the_same_name_twice_keeps_the_newer_button() {
        post()
        val posted = waitForNotification()
        assertNotNull(posted)
        val key = posted!!.key

        controller.captureActionButton(key, actionIndex = 0, `as` = "same")
        controller.captureActionButton(key, actionIndex = 1, `as` = "same")

        assertEquals(listOf("same"), CapturedButtons.names())

        val pressed = controller.pressCaptured("same")
        assertTrue("Pressing the replaced button failed: $pressed", pressed is ActionResult.Success)
    }

    @Test
    fun a_button_index_that_does_not_exist_is_reported() {
        post()
        val posted = waitForNotification()
        assertNotNull(posted)

        val kept = controller.captureActionButton(posted!!.key, actionIndex = 9, `as` = "nope")

        assertTrue(kept is ActionResult.Failure)
        assertTrue((kept as ActionResult.Failure).reason.contains("does not exist"))
        assertTrue("nothing should be kept", CapturedButtons.names().isEmpty())
    }

    private fun post() {
        val first = PendingIntent.getBroadcast(
            context,
            1,
            Intent(buttonAction).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val second = PendingIntent.getBroadcast(
            context,
            2,
            Intent(buttonAction).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, channelId)
            .setContentTitle("Bedtime mode is on")
            .setContentText("A stand-in for the notification a pair of rules would use.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(Notification.Action.Builder(null, "Turn off for now", first).build())
            .addAction(Notification.Action.Builder(null, "Pause for 30 min", second).build())
            .build()

        manager().notify(notificationId, notification)
    }

    private fun manager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun waitForNotification(): android.service.notification.StatusBarNotification? {
        var found: android.service.notification.StatusBarNotification? = null
        waitFor(seconds = 10) {
            found = NotificationEvents.service?.activeNotifications
                ?.firstOrNull { it.id == notificationId }
            found != null
        }
        return found
    }

    private fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ByteArrayOutputStream().use { out ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                input.copyTo(out)
            }
            out.toString()
        }
    }
}
