package tech.devline.scropy_ui.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import tech.devline.scropy_ui.R
import tech.devline.scropy_ui.StreamActivity
import tech.devline.scropy_ui.adb.AdbConnection
import tech.devline.scropy_ui.resolveAdbPortViaMdns
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "RemoteLinkService"

/**
 * Keeps an ADB connection to one remote device open in the background,
 * reconnecting whenever it drops, and mirrors the remote device's phone calls
 * as notifications on this device (answer / decline / silence / hang up).
 *
 * Call audio stays on the remote device: ADB has no way to route a call's
 * audio (or this device's microphone) across.
 */
class RemoteLinkService : Service() {

    /** What the UI shows about the background link. */
    data class LinkState(
        val host: String? = null,
        val connected: Boolean = false,
        val status: String = "Off",
    )

    companion object {
        private const val ACTION_START   = "tech.devline.scropy_ui.link.START"
        private const val ACTION_STOP    = "tech.devline.scropy_ui.link.STOP"
        private const val ACTION_ANSWER  = "tech.devline.scropy_ui.link.ANSWER"
        private const val ACTION_DECLINE = "tech.devline.scropy_ui.link.DECLINE"
        private const val ACTION_SILENCE = "tech.devline.scropy_ui.link.SILENCE"
        private const val ACTION_HANGUP  = "tech.devline.scropy_ui.link.HANGUP"

        private const val EXTRA_HOST  = "host"
        private const val EXTRA_PORT  = "port"
        private const val EXTRA_LABEL = "label"

        private const val PREFS = "remote_link"
        private const val PREF_ENABLED = "enabled"
        private const val PREF_HOST    = "host"
        private const val PREF_PORT    = "port"
        private const val PREF_LABEL   = "label"

        private const val CHANNEL_LINK  = "remote_link"
        private const val CHANNEL_CALLS = "remote_calls"
        private const val NOTIF_LINK   = 1001
        private const val NOTIF_CALL   = 1002
        private const val NOTIF_MISSED = 1003

        private const val MIN_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L
        /** No call-state output for this long means the link is dead. */
        private const val WATCHDOG_MS = 15_000L

        // TelephonyManager-agnostic key codes handled by PhoneWindowManager
        // while a call is ringing / active.
        private const val KEYCODE_CALL        = 5    // answer a ringing call
        private const val KEYCODE_ENDCALL     = 6    // decline / hang up
        private const val KEYCODE_VOLUME_MUTE = 164  // silence the ringer

        /** Remote command that prints the call state once a second, forever. */
        private const val MONITOR_CMD =
            "shell:while true; do dumpsys telephony.registry | " +
            "grep -E 'mCallState=|mCallIncomingNumber='; echo __END__; sleep 1; done"

        private val _state = MutableStateFlow(LinkState())
        val state: StateFlow<LinkState> = _state.asStateFlow()

        fun start(context: Context, host: String, port: Int, label: String) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, true)
                .putString(PREF_HOST, host).putInt(PREF_PORT, port).putString(PREF_LABEL, label)
                .apply()
            val intent = Intent(context, RemoteLinkService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host).putExtra(EXTRA_PORT, port).putExtra(EXTRA_LABEL, label)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, false).apply()
            context.startService(Intent(context, RemoteLinkService::class.java).setAction(ACTION_STOP))
        }

        /** Restart the link after a reboot if the user had it switched on. */
        fun restoreIfEnabled(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_ENABLED, false)) return
            val host = prefs.getString(PREF_HOST, null) ?: return
            start(context, host, prefs.getInt(PREF_PORT, 5555), prefs.getString(PREF_LABEL, null) ?: host)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var linkJob: Job? = null

    @Volatile private var conn: AdbConnection? = null
    private var host = ""
    private var port = 5555
    private var label = ""

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Call tracking (touched only from the monitor coroutine)
    private var lastCall: RemoteCallState = RemoteCallState.Idle
    private var ringingNumber: String? = null
    private var ringingName: String? = null
    private var answeredOrDeclined = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_ANSWER  -> { answeredOrDeclined = true; sendKey(KEYCODE_CALL) }
            ACTION_DECLINE -> { answeredOrDeclined = true; sendKey(KEYCODE_ENDCALL) }
            ACTION_SILENCE -> sendKey(KEYCODE_VOLUME_MUTE)
            ACTION_HANGUP  -> sendKey(KEYCODE_ENDCALL)
            else -> {
                // ACTION_START, or a null intent when the system restarts us (START_STICKY)
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val newHost = intent?.getStringExtra(EXTRA_HOST) ?: prefs.getString(PREF_HOST, null)
                if (newHost == null) { stopSelf(); return START_NOT_STICKY }
                val newPort = intent?.getIntExtra(EXTRA_PORT, 5555) ?: prefs.getInt(PREF_PORT, 5555)
                val newLabel = intent?.getStringExtra(EXTRA_LABEL) ?: prefs.getString(PREF_LABEL, null) ?: newHost

                goForeground("Connecting to $newLabel…")
                if (linkJob?.isActive != true || newHost != host || newPort != port) {
                    host = newHost; port = newPort; label = newLabel
                    restartLink()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { conn?.close() }
        releaseLocks()
        _state.value = LinkState()
        super.onDestroy()
    }

    private fun shutdown() {
        linkJob?.cancel()
        runCatching { conn?.close() }
        conn = null
        notificationManager().cancel(NOTIF_CALL)
        releaseLocks()
        _state.value = LinkState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── Connection loop ────────────────────────────────────────────────────

    private fun restartLink() {
        linkJob?.cancel()
        runCatching { conn?.close() }
        acquireLocks()
        linkJob = scope.launch {
            var retryMs = MIN_RETRY_MS
            while (isActive) {
                setStatus(false, "Connecting to $label…")
                try {
                    // The Wireless-debugging port drifts; `adb tcpip` ports don't.
                    val livePort = resolveAdbPortViaMdns(this@RemoteLinkService, host, port)
                    val c = AdbConnection.connectTcp(this@RemoteLinkService, host, livePort)
                    conn = c
                    port = livePort
                    retryMs = MIN_RETRY_MS
                    setStatus(true, "Connected to $label")
                    monitorCalls(c)
                    setStatus(false, "Connection to $label lost — reconnecting…")
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "Link failed", e)
                    setStatus(false, "Can't reach $label (${e.message}) — retrying in ${retryMs / 1000}s")
                } finally {
                    runCatching { conn?.close() }
                    conn = null
                    // We can no longer see or control the call; don't report it as missed.
                    notificationManager().cancel(NOTIF_CALL)
                    lastCall = RemoteCallState.Idle
                }
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    /**
     * Stream the remote call state until the connection dies. A watchdog
     * closes the connection if the remote loop goes quiet, which catches
     * half-open TCP connections (Wi-Fi dropped without a reset).
     */
    private suspend fun monitorCalls(c: AdbConnection) {
        val stream = c.open(MONITOR_CMD)
        val lastOutputAt = AtomicLong(System.currentTimeMillis())
        val watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_MS / 3)
                if (System.currentTimeMillis() - lastOutputAt.get() > WATCHDOG_MS) {
                    Log.w(TAG, "No output for ${WATCHDOG_MS}ms — dropping link")
                    runCatching { c.close() }
                    break
                }
            }
        }
        try {
            runInterruptible {
                val reader = stream.inputStream.bufferedReader()
                val snapshot = mutableListOf<String>()
                while (true) {
                    val line = try { reader.readLine() } catch (_: IOException) { null } ?: break
                    lastOutputAt.set(System.currentTimeMillis())
                    if (line.trim() == "__END__") {
                        onCallStateChanged(CallStateParser.parse(snapshot))
                        snapshot.clear()
                    } else {
                        snapshot += line
                    }
                }
            }
        } finally {
            watchdog.cancel()
            runCatching { stream.close() }
        }
    }

    // ─── Calls ──────────────────────────────────────────────────────────────

    private fun onCallStateChanged(call: RemoteCallState) {
        val previous = lastCall
        if (call == previous) return
        lastCall = call

        when (call) {
            is RemoteCallState.Ringing -> {
                if (previous !is RemoteCallState.Ringing || call.number != ringingNumber) {
                    answeredOrDeclined = false
                    ringingNumber = call.number
                    ringingName = call.number?.let(::lookupContactName)
                }
                showRingingNotification()
            }
            RemoteCallState.OffHook -> showInCallNotification()
            RemoteCallState.Idle -> {
                notificationManager().cancel(NOTIF_CALL)
                if (previous is RemoteCallState.Ringing && !answeredOrDeclined) showMissedNotification()
                ringingNumber = null
                ringingName = null
            }
        }
    }

    private fun callerText(): String = when {
        ringingName != null && ringingNumber != null -> "$ringingName ($ringingNumber)"
        ringingName != null   -> ringingName!!
        ringingNumber != null -> ringingNumber!!
        else                  -> "Unknown number"
    }

    /** Look the number up in the remote device's contacts; null if not found or not allowed. */
    private fun lookupContactName(number: String): String? = runCatching {
        val uri = "content://com.android.contacts/phone_lookup/" + Uri.encode(number)
        val out = runShell("content query --uri '$uri' --projection display_name")
        Regex("""display_name=(.+?)(?:,\s*\w+=|$)""", RegexOption.MULTILINE)
            .find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "NULL" }
    }.getOrNull()

    private fun sendKey(keycode: Int) {
        scope.launch {
            runCatching { runShell("input keyevent $keycode") }
                .onFailure { Log.w(TAG, "Key $keycode failed", it) }
        }
    }

    /** Run a one-off shell command on the remote device and return its output. */
    private fun runShell(cmd: String): String {
        val c = conn ?: throw IOException("Not connected")
        val stream = c.open("shell:$cmd")
        return try {
            stream.inputStream.bufferedReader().readText()
        } finally {
            runCatching { stream.close() }
        }
    }

    // ─── Notifications ──────────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Background connection", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the app stays connected to a device" })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls on the remote device", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Incoming, ongoing and missed calls on the connected device" })
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_LINK, linkNotification(text), type)
    }

    private fun linkNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Scropy link")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(streamIntent())
            .addAction(0, "Disconnect", serviceIntent(ACTION_STOP))
            .build()

    private fun showRingingNotification() {
        val n = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Incoming call on $label")
            .setContentText(callerText())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(streamIntent())
            .addAction(0, "Answer", serviceIntent(ACTION_ANSWER))
            .addAction(0, "Decline", serviceIntent(ACTION_DECLINE))
            .addAction(0, "Silence", serviceIntent(ACTION_SILENCE))
            .build()
        notificationManager().notify(NOTIF_CALL, n)
    }

    private fun showInCallNotification() {
        val n = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Call in progress on $label")
            .setContentText(if (ringingNumber != null || ringingName != null) callerText()
                            else "Audio is on the remote device")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setUsesChronometer(true)
            .setContentIntent(streamIntent())
            .addAction(0, "Hang up", serviceIntent(ACTION_HANGUP))
            .build()
        notificationManager().notify(NOTIF_CALL, n)
    }

    private fun showMissedNotification() {
        val n = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Missed call on $label")
            .setContentText(callerText())
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(streamIntent())
            .build()
        notificationManager().notify(NOTIF_MISSED, n)
    }

    private fun setStatus(connected: Boolean, text: String) {
        _state.value = LinkState(host, connected, text)
        runCatching { notificationManager().notify(NOTIF_LINK, linkNotification(text)) }
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, RemoteLinkService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Tapping a notification opens the live screen of the remote device. */
    private fun streamIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, StreamActivity::class.java)
                .putExtra(StreamActivity.EXTRA_HOST, host)
                .putExtra(StreamActivity.EXTRA_PORT, port)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationManager() =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    // ─── Keep Wi-Fi and CPU awake while linked ──────────────────────────────

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "scropy:link")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scropy:link")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }; wifiLock = null
        runCatching { wakeLock?.release() }; wakeLock = null
    }
}
