package app.phueber.trigly.triggers

import android.telephony.TelephonyManager.CALL_STATE_IDLE
import android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
import android.telephony.TelephonyManager.CALL_STATE_RINGING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {

    @Test
    fun `an answered incoming call reports incoming then answered then ended`() {
        val machine = CallStateMachine()

        assertEquals(listOf(CallEvent.INCOMING), machine.onState(CALL_STATE_RINGING))
        assertEquals(listOf(CallEvent.ANSWERED), machine.onState(CALL_STATE_OFFHOOK))
        assertEquals(listOf(CallEvent.ENDED), machine.onState(CALL_STATE_IDLE))
    }

    @Test
    fun `ringing that returns to idle is a missed call, not an ended one`() {
        val machine = CallStateMachine()

        assertEquals(listOf(CallEvent.INCOMING), machine.onState(CALL_STATE_RINGING))
        assertEquals(listOf(CallEvent.MISSED), machine.onState(CALL_STATE_IDLE))
    }

    @Test
    fun `off-hook without ringing first is an outgoing call`() {
        val machine = CallStateMachine()

        assertEquals(listOf(CallEvent.OUTGOING), machine.onState(CALL_STATE_OFFHOOK))
        assertEquals(listOf(CallEvent.ENDED), machine.onState(CALL_STATE_IDLE))
    }

    @Test
    fun `a repeated state reports nothing`() {
        val machine = CallStateMachine()

        assertEquals(listOf(CallEvent.INCOMING), machine.onState(CALL_STATE_RINGING))
        assertTrue(machine.onState(CALL_STATE_RINGING).isEmpty())
        assertTrue(machine.onState(CALL_STATE_RINGING).isEmpty())
    }

    @Test
    fun `starting idle reports nothing for idle`() {
        assertTrue(CallStateMachine().onState(CALL_STATE_IDLE).isEmpty())
    }

    @Test
    fun `a second call after the first is tracked independently`() {
        val machine = CallStateMachine()

        machine.onState(CALL_STATE_RINGING)
        machine.onState(CALL_STATE_IDLE)

        assertEquals(listOf(CallEvent.INCOMING), machine.onState(CALL_STATE_RINGING))
        assertEquals(listOf(CallEvent.ANSWERED), machine.onState(CALL_STATE_OFFHOOK))
    }

    @Test
    fun `event names parse case insensitively`() {
        assertEquals(CallEvent.MISSED, CallEvent.parse("missed"))
        assertEquals(CallEvent.INCOMING, CallEvent.parse("INCOMING"))
    }

    @Test
    fun `an unknown event name lists the valid ones`() {
        val error = assertThrows(IllegalStateException::class.java) {
            CallEvent.parse("hung_up")
        }
        assertTrue(error.message!!.contains("missed"))
    }
}
