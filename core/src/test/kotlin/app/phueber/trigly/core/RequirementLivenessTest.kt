package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The liveness axis this exercises exists for one bug: after an app update the
 * notification listener can be unbound while the secure setting that grants it
 * is still on, so `RequirementChecker.isSatisfied` alone cannot tell a dead
 * listener from a healthy one. [livenessOf] is the pure decision behind that
 * axis, kept free of `Context` on purpose so it can be tested here rather than
 * only on a device.
 *
 * A fake [LivenessProbe] stands in for the real one [ControllerLivenessProbe]
 * builds from the notification and accessibility ports.
 */
class RequirementLivenessTest {

    private class FakeProbe(private val bound: Boolean?) : LivenessProbe {
        override fun isBound(kind: SpecialAccessKind): Boolean? = bound
    }

    @Test
    fun `granted and live reports LIVE`() {
        // The healthy case: the setting is on and the service answered "yes,
        // I am bound". Nothing should be reported as broken.
        val liveness = livenessOf(
            kind = SpecialAccessKind.NOTIFICATION_LISTENER,
            granted = true,
            probe = FakeProbe(bound = true),
        )

        assertEquals(Liveness.LIVE, liveness)
    }

    @Test
    fun `granted and not live reports NOT_LIVE`() {
        // The exact bug this axis exists for: notification access is granted,
        // but the listener the setting is supposed to control is not bound.
        // Before this axis existed, this state was indistinguishable from a
        // healthy listener.
        val liveness = livenessOf(
            kind = SpecialAccessKind.NOTIFICATION_LISTENER,
            granted = true,
            probe = FakeProbe(bound = false),
        )

        assertEquals(Liveness.NOT_LIVE, liveness)
    }

    @Test
    fun `not granted reports LIVE, never NOT_LIVE, however the probe answers`() {
        // "Never granted" is `unmet`'s fact to report, not this axis's. A rule
        // that never asked for notification access must not also collect a
        // "not bound" accusation for a service it was never entitled to use,
        // even when the probe genuinely sees nothing bound.
        val neverAsked = livenessOf(
            kind = SpecialAccessKind.NOTIFICATION_LISTENER,
            granted = false,
            probe = FakeProbe(bound = false),
        )
        val boundAnyway = livenessOf(
            kind = SpecialAccessKind.NOTIFICATION_LISTENER,
            granted = false,
            probe = FakeProbe(bound = true),
        )

        assertEquals(Liveness.LIVE, neverAsked)
        assertEquals(Liveness.LIVE, boundAnyway)
    }

    @Test
    fun `no answer from the probe reports UNKNOWN, not NOT_LIVE`() {
        // The false-accusation guard: a probe that has not been asked yet, or
        // cannot tell, must never be read as "dead". `null` is the probe's way
        // of saying "no information", and this must not round that up to a
        // fault.
        val liveness = livenessOf(
            kind = SpecialAccessKind.NOTIFICATION_LISTENER,
            granted = true,
            probe = FakeProbe(bound = null),
        )

        assertEquals(Liveness.UNKNOWN, liveness)
    }

    @Test
    fun `a kind with no bindable service is always LIVE, even if the probe would say otherwise`() {
        // Usage stats, Do Not Disturb access and drawing over other apps have
        // no service that can go quietly missing behind a still-on setting -
        // `isSatisfied` already reads their live state on every call. A probe
        // is never even worth asking for one of these.
        val liveness = livenessOf(
            kind = SpecialAccessKind.USAGE_STATS,
            granted = true,
            probe = FakeProbe(bound = false),
        )

        assertEquals(Liveness.LIVE, liveness)
    }

    @Test
    fun `the default probe answers nothing for every kind`() {
        // LivenessProbe.Unknown is what every existing RequirementChecker
        // construction site gets today, unchanged. It must stay the safe
        // choice: no information, ever, for any kind.
        SpecialAccessKind.entries.forEach { kind ->
            assertNull(LivenessProbe.Unknown.isBound(kind))
        }
    }

    private class FakeNotificationController(override val isConnected: Boolean) :
        NotificationController {
        override fun activeNotifications(): List<ActiveNotification> = emptyList()
        override fun dismiss(key: String): ActionResult = ActionResult.Failure("not used in this test")
        override fun triggerActionButton(key: String, actionIndex: Int): ActionResult =
            ActionResult.Failure("not used in this test")
    }

    private class FakeUiController(override val isConnected: Boolean) : UiController {
        override suspend fun pressNotificationButton(
            packageName: String?,
            label: String,
        ): ActionResult = ActionResult.Failure("not used in this test")
    }

    @Test
    fun `ControllerLivenessProbe reads the notification listener from the notification controller`() {
        val bound = ControllerLivenessProbe(
            notifications = FakeNotificationController(isConnected = true),
            ui = FakeUiController(isConnected = false),
        )
        val unbound = ControllerLivenessProbe(
            notifications = FakeNotificationController(isConnected = false),
            ui = FakeUiController(isConnected = true),
        )

        assertEquals(true, bound.isBound(SpecialAccessKind.NOTIFICATION_LISTENER))
        assertEquals(false, unbound.isBound(SpecialAccessKind.NOTIFICATION_LISTENER))
    }

    @Test
    fun `ControllerLivenessProbe reads the accessibility service from the ui controller`() {
        val probe = ControllerLivenessProbe(
            notifications = FakeNotificationController(isConnected = false),
            ui = FakeUiController(isConnected = true),
        )

        assertEquals(true, probe.isBound(SpecialAccessKind.ACCESSIBILITY_SERVICE))
    }

    @Test
    fun `ControllerLivenessProbe has no answer for the three kinds with no bindable service`() {
        val probe = ControllerLivenessProbe(
            notifications = FakeNotificationController(isConnected = true),
            ui = FakeUiController(isConnected = true),
        )

        assertNull(probe.isBound(SpecialAccessKind.USAGE_STATS))
        assertNull(probe.isBound(SpecialAccessKind.NOTIFICATION_POLICY))
        assertNull(probe.isBound(SpecialAccessKind.OVERLAY))
    }
}
