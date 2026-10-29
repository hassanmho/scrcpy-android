package tech.devline.scropy_ui.link

/** Call state of the remote device, as reported by `dumpsys telephony.registry`. */
sealed class RemoteCallState {
    object Idle : RemoteCallState()
    data class Ringing(val number: String?) : RemoteCallState()
    object OffHook : RemoteCallState()
}

/**
 * Parses the `mCallState=` / `mCallIncomingNumber=` lines of one
 * `dumpsys telephony.registry` snapshot. Dual-SIM devices print one pair per
 * SIM slot; a ringing slot wins over an off-hook one, which wins over idle.
 */
object CallStateParser {

    // TelephonyManager.CALL_STATE_*
    private const val CALL_STATE_RINGING = 1
    private const val CALL_STATE_OFFHOOK = 2

    private val stateRe  = Regex("""mCallState=(\d+)""")
    private val numberRe = Regex("""mCallIncomingNumber=(\S*)""")

    fun parse(lines: List<String>): RemoteCallState {
        val states  = lines.mapNotNull { stateRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val numbers = lines.mapNotNull { numberRe.find(it)?.groupValues?.get(1) }

        val ringingSlot = states.indexOf(CALL_STATE_RINGING)
        if (ringingSlot >= 0) {
            val number = numbers.getOrNull(ringingSlot)?.takeIf { it.isNotBlank() }
                ?: numbers.firstOrNull { it.isNotBlank() }
            return RemoteCallState.Ringing(number)
        }
        if (CALL_STATE_OFFHOOK in states) return RemoteCallState.OffHook
        return RemoteCallState.Idle
    }
}
