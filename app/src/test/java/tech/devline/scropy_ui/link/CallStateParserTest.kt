package tech.devline.scropy_ui.link

import org.junit.Assert.assertEquals
import org.junit.Test

class CallStateParserTest {

    @Test
    fun idleWhenAllSlotsIdle() {
        val lines = listOf("  mCallState=0", "  mCallIncomingNumber=", "  mCallState=0", "  mCallIncomingNumber=")
        assertEquals(RemoteCallState.Idle, CallStateParser.parse(lines))
    }

    @Test
    fun ringingTakesNumberOfRingingSlot() {
        val lines = listOf(
            "  mCallState=0", "  mCallIncomingNumber=",
            "  mCallState=1", "  mCallIncomingNumber=+962790000000",
        )
        assertEquals(RemoteCallState.Ringing("+962790000000"), CallStateParser.parse(lines))
    }

    @Test
    fun ringingWithHiddenNumber() {
        val lines = listOf("mCallState=1", "mCallIncomingNumber=")
        assertEquals(RemoteCallState.Ringing(null), CallStateParser.parse(lines))
    }

    @Test
    fun offHookWhenInCall() {
        val lines = listOf("mCallState=2", "mCallIncomingNumber=0790000000")
        assertEquals(RemoteCallState.OffHook, CallStateParser.parse(lines))
    }

    @Test
    fun ringingWinsOverOffHook() {
        val lines = listOf("mCallState=2", "mCallIncomingNumber=", "mCallState=1", "mCallIncomingNumber=123")
        assertEquals(RemoteCallState.Ringing("123"), CallStateParser.parse(lines))
    }
}
