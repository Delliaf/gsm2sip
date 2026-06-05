package com.callagent.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.GatewayApp
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.callagent.gateway.RootShell
import com.callagent.gateway.bridge.CallOrchestrator
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.net.StunClient
import com.callagent.gateway.sip.SipClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service: keeps the SIP client registered 24/7.
 *
 * Holds a WiFi lock and wake lock to prevent the device from
 * sleeping and dropping the SIP registration.
 *
 * OxygenOS compatibility: runs with foregroundServiceType="phoneCall"
 * only (no microphone type — OxygenOS kills services with that type).
 * RECORD_AUDIO is granted via root appops instead.
 */
class GatewayService : Service() {

    private var sipClient: SipClient? = null
    private var orchestrator: CallOrchestrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Saved config for reconnect */
    private var cfgServer = ""
    private var cfgPort = 5060
    private var cfgUser = ""
    private var cfgPass = ""
    private var currentLocalIp = ""

    // ── Call tracking ───────────────────────────────────
    private var onlineSince = 0L
    private var incomingCalls = 0
    private var incomingDurationSec = 0L
    private var outgoingCalls = 0
    private var outgoingDurationSec = 0L
    private var currentCallStart = 0L
    private var currentCallIncoming = true
    private var currentCallNumber = ""

    /** Prevents concurrent startGateway / reconnect threads */
    private val initializing = AtomicBoolean(false)

    // ── Heartbeat ───────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatCount = 0L

    // ── Network change detection ────────────────────────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                checkNetworkChanged()
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                val busy = orchestrator?.bridgeState?.let {
                    it != CallOrchestrator.BridgeState.IDLE
                } ?: false
                if (busy) {
                    Log.i(TAG, "Skipping reconnect — call in progress (${orchestrator?.bridgeState})")
                    broadcastLog("Network lost (ignored — call active)")
                    return
                }
                broadcastLog("Network lost, reconnecting...")
                broadcastStatus("ERROR", "Network lost")
                updateNotification(NotifState.ERROR, "Offline")
                reconnect()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                checkNetworkChanged()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun checkNetworkChanged() {
        if (currentLocalIp.isEmpty()) return
        if (cfgServer.isEmpty()) return
        val busy = orchestrator?.bridgeState?.let {
            it != CallOrchestrator.BridgeState.IDLE
        } ?: false
        if (busy) return
        val newIp = getLocalIp()
        if (newIp == "0.0.0.0") return
        val ipChanged = newIp != currentLocalIp
        val notRegistered = sipClient?.registered != true
        if (ipChanged || notRegistered) {
            if (ipChanged) broadcastLog("Network changed: $currentLocalIp → $newIp")
            else broadcastLog("Network recovered, reconnecting...")
            reconnect()
        }
    }

    private fun reconnect() {
        if (stopped || cfgServer.isEmpty()) return
        if (!initializing.compareAndSet(false, true)) {
            Log.i(TAG, "Reconnect skipped — already initializing")
            return
        }
        onlineSince = 0L
        broadcastLog("Reconnecting...")
        broadcastStatus("STARTING", "Reconnecting...")
        updateNotification(NotifState.WARN, "Connecting")

        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null

        thread(name = "gateway-reconnect") {
            try {
                initSipClient()
            } finally {
                initializing.set(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GatewayService created")
        createNotificationChannel()
        registerNetworkCallback()
        RootShell.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action ?: "null"} flags=$flags startId=$startId")

        // ALWAYS start foreground immediately — before any logic.
        // This prevents ANR / ServiceNotStartedException on Android 10+,
        // and ensures OxygenOS sees us as a persistent foreground service
        // as early as possible.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(NotifState.WARN, "Starting..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed (already started?): ${e.message}")
            }
        } else {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(NotifState.WARN, "Starting...")
                )
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed (already started?): ${e.message}")
            }
        }

        when (intent?.action) {
            ACTION_START -> startGateway(intent)
            ACTION_STOP -> stopGateway()
            ACTION_RELOAD_STATS -> reloadStats()
            ACTION_DIAL -> dialFromDialler(intent)
            else -> {
                // START_STICKY restart with null intent — restore from prefs
                if (intent == null && !stopped && sipClient == null) {
                    Log.i(TAG, "START_STICKY restart detected, restoring gateway")
                    val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
                    val server = prefs.getString("server", "") ?: ""
                    val user = prefs.getString("user", "") ?: ""
                    if (server.isNotEmpty() && user.isNotEmpty()) {
                        broadcastLog("Restoring gateway after restart...")
                        startGateway(null)
                    } else {
                        Log.w(TAG, "START_STICKY: no saved config, staying alive but idle")
                    }
                }
                // else: already running, heartbeat will tell us we're alive
            }
        }
        return START_STICKY
    }

    /**
     * Called when the user swipes the app from recents.
     * OxygenOS also calls this aggressively.
     * Log it for diagnostics.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "⚡ onTaskRemoved — user swiped app from recents!")
        broadcastLog("⚡ ALERT: onTaskRemoved — app removed from recents")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called when Android is low on memory or trimming our process.
     * Logs TRIM_LEVEL so we can see what OxygenOS is doing.
     */
    override fun onTrimMemory(level: Int) {
        val levelName = when (level) {
            TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
            TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
            TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
            TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
            TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
            TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
            TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
            else -> "TRIM_LEVEL_$level"
        }
        Log.w(TAG, "⚡ onTrimMemory: $levelName")
        broadcastLog("⚡ Mem: $levelName")
        super.onTrimMemory(level)
    }

    private fun startHeartbeat() {
        heartbeatCount = 0L
        mainHandler.post(object : Runnable {
            override fun run() {
                if (stopped) return
                heartbeatCount++
                val sipOk = sipClient?.registered == true
                val alive = sipClient != null && orchestrator != null
                val threadAlive = Thread.activeCount()
                Log.i(TAG, "♥ Heartbeat #$heartbeatCount: alive=$alive sip=$sipOk threads=$threadAlive mem=${
                    Runtime.getRuntime().totalMemory() / 1024 / 1024}M")
                if (heartbeatCount % 4 == 0L) { // every 60s
                    broadcastLog("♥ Heartbeat #$heartbeatCount: sip=$sipOk threads=$threadAlive")
                }
                mainHandler.postDelayed(this, 15_000L)
            }
        })
    }

    private fun dialFromDialler(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: return
        orchestrator?.initiateDiallerCall(number)
            ?: broadcastLog("ERROR: Gateway not running — cannot bridge to SIP")
    }

    private fun reloadStats() {
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        broadcastStatus(orchestrator?.bridgeState?.name ?: "IDLE", "Stats reloaded")
    }

    private fun startGateway(intent: Intent?) {
        // Guard: if already running, skip
        if (!stopped && sipClient != null) {
            Log.i(TAG, "startGateway: already running, skipping restart")
            val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
            val registered = sipClient?.registered == true
            val info = when (state) {
                CallOrchestrator.BridgeState.IDLE ->
                    if (registered) "SIP registered" else "Connected"
                else -> state.name
            }
            broadcastStatus(state.name, info)
            return
        }

        // Clean up any existing client before starting a new one
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null
        stopped = false

        // Load call stats
        onlineSince = 0L
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        currentCallStart = 0L

        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: prefs.getString("server", "sip.callagent.pro") ?: ""
        val port = intent?.getIntExtra(EXTRA_PORT, 5060) ?: prefs.getInt("port", 5060)
        val username = intent?.getStringExtra(EXTRA_USER) ?: prefs.getString("user", "") ?: ""
        val password = intent?.getStringExtra(EXTRA_PASS) ?: prefs.getString("pass", "") ?: ""

        if (server.isEmpty() || username.isEmpty()) {
            Log.e(TAG, "Missing SIP configuration")
            broadcastLog("ERROR: Missing server or username")
            broadcastStatus("ERROR", "Missing SIP configuration")
            // Don't stopSelf — stay alive as foreground service
            // so user can configure via UI
            return
        }

        // Save for restart
        prefs.edit()
            .putString("server", server)
            .putInt("port", port)
            .putString("user", username)
            .putString("pass", password)
            .apply()

        cfgServer = server
        cfgPort = port
        cfgUser = username
        cfgPass = password

        notifStatusText = "Connecting"
        // startForeground already called in onStartCommand, just update the notification
        updateNotification(NotifState.WARN, "Connecting")
        acquireLocks()

        // Start heartbeat after locks are acquired
        startHeartbeat()

        initializing.set(true)

        thread(name = "gateway-init") {
            try {
                forceAllowRecordAudio()
                initSipClient()
            } finally {
                initializing.set(false)
            }
        }
    }

    /** Shared SIP init — called from both startGateway and reconnect threads. */
    private fun initSipClient() {
        val localIp = getLocalIp()
        currentLocalIp = localIp
        broadcastLog("Local IP: $localIp")

        val stunResult = try { StunClient.discover() } catch (e: Exception) {
            Log.e(TAG, "STUN exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val publicIp = stunResult?.publicIp ?: localIp
        if (stunResult != null) {
            broadcastLog("STUN public IP: ${stunResult.publicIp}:${stunResult.publicPort}")
        } else {
            broadcastLog("STUN failed, using local IP for SDP")
        }

        if (stopped) return

        val sip = SipClient(
            username = cfgUser,
            password = cfgPass,
            serverDomain = cfgServer,
            serverPort = cfgPort,
            localIp = localIp,
            localPort = 5060,
            publicIp = publicIp
        )
        sipClient = sip

        val orch = CallOrchestrator(this, sip)
        orch.listener = object : CallOrchestrator.OrchestratorListener {
            override fun onStateChanged(state: CallOrchestrator.BridgeState, info: String) {
                Log.i(TAG, "Bridge: $state - $info")
                val registered = sip.registered

                if (registered && onlineSince == 0L) {
                    onlineSince = System.currentTimeMillis()
                } else if (!registered && state == CallOrchestrator.BridgeState.IDLE) {
                    onlineSince = 0L
                }

                when (state) {
                    CallOrchestrator.BridgeState.GSM_RINGING -> {
                        currentCallIncoming = true
                        currentCallNumber = info.removePrefix("GSM call from ")
                    }
                    CallOrchestrator.BridgeState.GSM_DIALING -> {
                        currentCallIncoming = false
                        currentCallNumber = info.removePrefix("Dialing ")
                    }
                    else -> {}
                }

                if (state == CallOrchestrator.BridgeState.BRIDGED && currentCallStart == 0L) {
                    currentCallStart = System.currentTimeMillis()
                    if (currentCallIncoming) incomingCalls++ else outgoingCalls++
                }
                if (state == CallOrchestrator.BridgeState.IDLE && currentCallStart != 0L) {
                    val dur = (System.currentTimeMillis() - currentCallStart) / 1000
                    if (currentCallIncoming) incomingDurationSec += dur
                    else outgoingDurationSec += dur
                    CallLogStore.addEntry(this@GatewayService, CallLogEntry(
                        direction = if (currentCallIncoming) "IN" else "OUT",
                        number = currentCallNumber,
                        timestamp = currentCallStart,
                        durationSec = dur
                    ))
                    currentCallStart = 0L
                    currentCallNumber = ""
                }

                val (notifState, statusText) = when (state) {
                    CallOrchestrator.BridgeState.IDLE ->
                        if (registered) NotifState.OK to "Connected"
                        else NotifState.WARN to "Connecting"
                    CallOrchestrator.BridgeState.GSM_DIALING ->
                        NotifState.OK to "Dialing"
                    CallOrchestrator.BridgeState.BRIDGED ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.GSM_RINGING,
                    CallOrchestrator.BridgeState.GSM_ANSWERED,
                    CallOrchestrator.BridgeState.SIP_CALLING,
                    CallOrchestrator.BridgeState.SIP_RINGING ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.TEARING_DOWN ->
                        NotifState.OK to "In-Call"
                }
                updateNotification(notifState, statusText)
                broadcastStatus(state.name, info)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Orchestrator error: $error")
                broadcastLog("ERROR: $error")
                broadcastStatus("ERROR", error)
            }

            override fun onRtpStats(stats: String) {
                broadcastLog("RTP: $stats")
            }
        }
        orchestrator = orch
        orch.start()

        sip.logListener = { msg -> broadcastLog("SIP: $msg") }
        GsmCallManager.logCallback = { msg -> broadcastLog("AUDIO: $msg") }
        sip.onConnectionLost = { reconnect() }

        try {
            sip.start()
            broadcastLog("[v${BuildConfig.VERSION_NAME}] SIP client started, registering with $cfgServer:$cfgPort")
            broadcastStatus("STARTING", "Registering with $cfgServer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SIP client: ${e.message}", e)
            broadcastLog("ERROR: SIP start failed — ${e.message}")
            broadcastStatus("ERROR", "SIP start failed: ${e.message}")
        }
    }

    @Volatile private var stopped = false

    private fun stopGateway() {
        if (stopped) return
        stopped = true
        onlineSince = 0L
        Log.i(TAG, "Stopping gateway")
        mainHandler.removeCallbacksAndMessages(null) // stop heartbeat
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus("STOPPED", "Gateway stopped")
    }

    override fun onDestroy() {
        Log.w(TAG, "⚡ GatewayService onDestroy — reason: timeout / kill / restart")
        broadcastLog("⚡ ALERT: GatewayService onDestroy")
        unregisterNetworkCallback()
        if (!stopped) {
            Log.w(TAG, "⚡ onDestroy without stopGateway — saving state for restart")
            // Save that we were unexpectedly killed
            val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
            prefs.edit().putBoolean("was_unexpected_kill", true).apply()
        }
        stopGateway()
        super.onDestroy()
    }

    // ── Notification ────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private enum class NotifState { OK, WARN, ERROR }

    private var notifStatusText = "Connecting"

    private fun buildNotification(state: NotifState = NotifState.ERROR, statusText: String = notifStatusText): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = when (state) {
            NotifState.OK -> R.drawable.ic_notif_check
            NotifState.WARN -> R.drawable.ic_notif_warning
            NotifState.ERROR -> R.drawable.ic_notif_cross
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(statusText)
            .setSmallIcon(icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(true)
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    private fun updateNotification(state: NotifState = NotifState.ERROR, statusText: String? = null) {
        if (statusText != null) notifStatusText = statusText
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    // ── Wake / WiFi locks ───────────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gateway:sip").apply {
            acquire()
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gateway:wifi").apply {
            acquire()
        }
        Log.i(TAG, "Wake + WiFi locks acquired")
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    // ── Broadcast to MainActivity ────────────────────────

    private fun broadcastStatus(state: String, info: String) {
        val intent = Intent(STATUS_ACTION).apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra("info", info)
            putExtra("online_since", onlineSince)
            putExtra("in_calls", incomingCalls)
            putExtra("in_duration", incomingDurationSec)
            putExtra("out_calls", outgoingCalls)
            putExtra("out_duration", outgoingDurationSec)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(msg: String) {
        synchronized(logBuffer) {
            logBuffer.add(msg)
            if (logBuffer.size > LOG_BUFFER_SIZE) logBuffer.removeAt(0)
        }
        val intent = Intent(LOG_ACTION).apply {
            setPackage(packageName)
            putExtra("msg", msg)
        }
        sendBroadcast(intent)
    }

    // ── Network ─────────────────────────────────────────

    private fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return "0.0.0.0"
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     */
    private fun forceAllowRecordAudio() {
        try {
            val pkg = packageName
            val maxWaitMs = 90_000L
            val pollMs = 3_000L
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < maxWaitMs) {
                val uidProbe = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
                val probe = RootShell.execForOutput(
                    "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
                )
                if (!probe.contains("Can't find service", ignoreCase = true)) {
                    val waited = System.currentTimeMillis() - waitStart
                    if (waited > 100) {
                        Log.i(TAG, "appops service ready after ${waited}ms")
                        broadcastLog("System services ready (${waited / 1000}s)")
                    }
                    break
                }
                val elapsed = (System.currentTimeMillis() - waitStart) / 1000
                Log.i(TAG, "appops service not ready (${elapsed}s), waiting...")
                broadcastLog("Waiting for system services... (${elapsed}s)")
                Thread.sleep(pollMs)
            }

            val t0 = System.currentTimeMillis()
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")
            broadcastLog("appops RECORD_AUDIO: ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
                broadcastLog("appops fallback: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed (non-root?): ${e.message}")
            broadcastLog("appops RECORD_AUDIO failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val LOG_BUFFER_SIZE = 200
        val logBuffer = mutableListOf<String>()

        fun drainLogBuffer(): List<String> = synchronized(logBuffer) {
            val copy = logBuffer.toList()
            logBuffer.clear()
            copy
        }

        const val CHANNEL_ID = "gateway_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.callagent.gateway.START"
        const val ACTION_STOP = "com.callagent.gateway.STOP"
        const val ACTION_RELOAD_STATS = "com.callagent.gateway.RELOAD_STATS"
        const val ACTION_DIAL = "com.callagent.gateway.DIAL"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_NUMBER = "number"
        const val STATUS_ACTION = "com.callagent.gateway.STATUS"
        const val LOG_ACTION = "com.callagent.gateway.LOG"

        fun start(context: Context, server: String, port: Int, user: String, pass: String) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
