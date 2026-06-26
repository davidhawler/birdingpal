package com.openaiexperiments.birdingbuddy.nativeapp.background

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.openaiexperiments.birdingbuddy.BuildConfig
import com.openaiexperiments.birdingbuddy.nativeapp.net.BirdingBuddyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BirdBuddySessionService : Service() {
    private val logTag = "BirdBuddyBleRx"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: BirdingBuddyController

    private lateinit var bleManager: BirdBuddyBleManager

    private var notificationJob: Job? = null
    private var controllerSyncJob: Job? = null
    private var analyzeCaptureJob: Job? = null
    private var bleHoldWatchdogJob: Job? = null

    private var bleHoldMicActive = false
    private var pendingBleHoldStart = false
    private var pendingAnalyzeTrigger: BirdBuddyTriggerSource? = null
    private var lastBleHoldHeartbeatElapsedMs: Long = 0L

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()

        controller = createController()

        BirdBuddyNotificationHelper.ensureChannels(this)

        bleManager =
            BirdBuddyBleManager(applicationContext, object : BirdBuddyBleManager.Listener {
                override fun onBleStatus(connected: Boolean, status: String, deviceName: String?) {
                    BirdBuddySessionStore.setBleState(
                        connected = connected,
                        statusText = status,
                        deviceName = deviceName
                    )
                    addServiceLog(status)
                }

                override fun onBlePacket(packet: ByteArray) {
                    this@BirdBuddySessionService.onBlePacket(packet)
                }
            })

        BirdBuddySessionStore.setServiceRunning(true)

        startOrUpdateForegroundNotification()
        observeStateChanges()
        startControllerSyncLoop()

        if (isArmedPersisted()) {
            armInternal()
        } else {
            BirdBuddySessionStore.setArmed(false)
            BirdBuddySessionStore.setStatus("Bird Buddy service disarmed.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BirdBuddyActions.ACTION_ARM -> armInternal()
            BirdBuddyActions.ACTION_DISARM -> disarmInternal()
            BirdBuddyActions.ACTION_RETRY_BLE_CONNECT -> {
                if (BirdBuddySessionStore.state.value.armed) {
                    bleManager.retryNow()
                }
            }

            BirdBuddyActions.ACTION_RECONNECT_REALTIME -> {
                if (BirdBuddySessionStore.state.value.armed) {
                    controller.reconnectNow()
                }
            }

            BirdBuddyActions.ACTION_START_ANALYZE_10S -> {
                val source = parseTriggerSource(intent) ?: BirdBuddyTriggerSource.UI
                startAnalyzeCapture10Seconds(source)
            }

            BirdBuddyActions.ACTION_HOLD_TALK_START -> {
                beginHoldToTalk(BirdBuddyTriggerSource.UI)
            }

            BirdBuddyActions.ACTION_HOLD_TALK_END -> {
                endHoldToTalk(BirdBuddyTriggerSource.UI)
            }

            null -> {
                if (isArmedPersisted()) {
                    armInternal()
                }
            }
        }

        startOrUpdateForegroundNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null

        controllerSyncJob?.cancel()
        controllerSyncJob = null

        analyzeCaptureJob?.cancel()
        analyzeCaptureJob = null

        bleHoldWatchdogJob?.cancel()
        bleHoldWatchdogJob = null

        bleManager.dispose()
        controller.dispose()

        BirdBuddySessionStore.setServiceRunning(false)

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun armInternal() {
        if (BirdBuddySessionStore.state.value.armed) {
            return
        }

        persistArmed(true)
        BirdBuddySessionStore.setArmed(true)

        controller.connect()
        bleManager.start()

        addServiceLog("Bird Buddy service armed.")
    }

    private fun disarmInternal() {
        if (!BirdBuddySessionStore.state.value.armed) {
            return
        }

        persistArmed(false)

        analyzeCaptureJob?.cancel()
        analyzeCaptureJob = null

        bleHoldWatchdogJob?.cancel()
        bleHoldWatchdogJob = null

        bleHoldMicActive = false
        pendingBleHoldStart = false
        pendingAnalyzeTrigger = null

        runCatching { controller.endChatCapture() }
        runCatching { controller.endAnalyzeCapture() }

        bleManager.stop()
        controller.dispose()
        controller = createController()

        BirdBuddySessionStore.setArmed(false)
        BirdBuddySessionStore.setStatus("Bird Buddy service disarmed.")
        BirdBuddySessionStore.setBleState(
            connected = false,
            statusText = "BLE disconnected"
        )

        addServiceLog("Bird Buddy service disarmed.")
    }

    private fun startAnalyzeCapture10Seconds(source: BirdBuddyTriggerSource) {
        if (!ensureArmedAndMicPermission()) {
            return
        }

        if (controller.serverStatus != "ready") {
            pendingAnalyzeTrigger = source
            controller.reconnectNow()
            controller.notifyUser("Waiting for realtime session...")
            addServiceLog("Queued 10-second bird-call analyze trigger while reconnecting.")
            return
        }

        if (controller.isHoldingToTalk || controller.analyzingBirdCall) {
            addServiceLog("Analyze trigger ignored: Bird Buddy is busy.")
            return
        }

        pendingAnalyzeTrigger = null

        controller.beginAnalyzeCapture()
        addServiceLog(
            "Started timed bird-call capture (${source.name}) for ${ANALYZE_CAPTURE_TARGET_MS}ms + ${ANALYZE_CAPTURE_SAFETY_BUFFER_MS}ms buffer."
        )

        analyzeCaptureJob?.cancel()
        analyzeCaptureJob =
            serviceScope.launch {
                // Add a small buffer so we consistently clear the controller's >=10s requirement.
                delay(ANALYZE_CAPTURE_TARGET_MS + ANALYZE_CAPTURE_SAFETY_BUFFER_MS)
                controller.endAnalyzeCapture()
            }
    }

    private fun beginHoldToTalk(source: BirdBuddyTriggerSource) {
        if (!ensureArmedAndMicPermission()) {
            return
        }

        if (controller.serverStatus != "ready") {
            if (source == BirdBuddyTriggerSource.BLE_HOLD_HEARTBEAT) {
                pendingBleHoldStart = true
            }
            controller.reconnectNow()
            controller.notifyUser("Waiting for realtime session...")
            addServiceLog("Queued hold-to-talk while reconnecting.")
            return
        }

        if (!controller.isHoldingToTalk) {
            controller.beginChatCapture()
            addServiceLog("Hold-to-talk started (${source.name}).")
        }

        if (source == BirdBuddyTriggerSource.BLE_HOLD_HEARTBEAT) {
            bleHoldMicActive = true
            startBleHoldWatchdog()
        }
    }

    private fun endHoldToTalk(source: BirdBuddyTriggerSource) {
        if (controller.isHoldingToTalk) {
            controller.endChatCapture()
        }

        if (source == BirdBuddyTriggerSource.BLE_HOLD_HEARTBEAT) {
            bleHoldMicActive = false
            pendingBleHoldStart = false
        }

        addServiceLog("Hold-to-talk ended (${source.name}).")
    }

    private fun onBlePacket(packet: ByteArray) {
        val packetHex = packet.toHexString()
        val rxMessage = "BLE RX <Buffer $packetHex> (${packet.size} bytes)"
        Log.d(logTag, rxMessage)
        addServiceLog(rxMessage)

        BirdBuddySessionStore.setBleState(
            connected = BirdBuddySessionStore.state.value.bleConnected,
            statusText = BirdBuddySessionStore.state.value.bleStatusText,
            lastPacketHex = packetHex
        )

        when (parseBirdBuddyBleMessage(packet)) {
            BirdBuddyBleMessage.ANALYZE -> {
                addServiceLog("BLE analyze trigger received (<Buffer $packetHex>).")
                startAnalyzeCapture10Seconds(BirdBuddyTriggerSource.BLE_ANALYZE)
            }

            BirdBuddyBleMessage.HOLD_HEARTBEAT -> {
                lastBleHoldHeartbeatElapsedMs = SystemClock.elapsedRealtime()
                if (!bleHoldMicActive) {
                    beginHoldToTalk(BirdBuddyTriggerSource.BLE_HOLD_HEARTBEAT)
                }
                startBleHoldWatchdog()
            }

            BirdBuddyBleMessage.UNKNOWN -> {
                addServiceLog("Ignored unknown BLE packet (<Buffer $packetHex>).")
            }
        }
    }

    private fun startBleHoldWatchdog() {
        if (bleHoldWatchdogJob?.isActive == true) {
            return
        }

        bleHoldWatchdogJob =
            serviceScope.launch {
                while (isActive) {
                    delay(200)

                    if (!bleHoldMicActive) {
                        continue
                    }

                    val ageMs = SystemClock.elapsedRealtime() - lastBleHoldHeartbeatElapsedMs
                    if (ageMs > BirdBuddyBleProtocol.HOLD_HEARTBEAT_TIMEOUT_MS) {
                        endHoldToTalk(BirdBuddyTriggerSource.BLE_HOLD_HEARTBEAT)
                        controller.notifyUser("BLE hold heartbeat timed out; closing mic.")
                    }
                }
            }
    }

    private fun startControllerSyncLoop() {
        controllerSyncJob?.cancel()
        controllerSyncJob =
            serviceScope.launch {
                while (isActive) {
                    if (BirdBuddySessionStore.state.value.armed) {
                        BirdBuddySessionStore.syncControllerSnapshot(
                            serverStatus = controller.serverStatus,
                            statusText = controller.statusText,
                            statusLive = controller.statusLive,
                            isSocketConnected = controller.isSocketConnected,
                            isCapturing = controller.isCapturing,
                            isHoldingToTalk = controller.isHoldingToTalk,
                            analyzingBirdCall = controller.analyzingBirdCall,
                            chatMessages = controller.chatMessages.toList(),
                            logs = controller.logs.toList()
                        )

                        val queuedAnalyze = pendingAnalyzeTrigger
                        if (queuedAnalyze != null && controller.serverStatus == "ready") {
                            startAnalyzeCapture10Seconds(queuedAnalyze)
                        }

                        if (pendingBleHoldStart && controller.serverStatus == "ready") {
                            val ageMs = SystemClock.elapsedRealtime() - lastBleHoldHeartbeatElapsedMs
                            if (ageMs <= BirdBuddyBleProtocol.HOLD_HEARTBEAT_TIMEOUT_MS) {
                                pendingBleHoldStart = false
                                beginHoldToTalk(BirdBuddyTriggerSource.BLE_HOLD_HEARTBEAT)
                            } else {
                                pendingBleHoldStart = false
                            }
                        }
                    }

                    delay(150)
                }
            }
    }

    private fun observeStateChanges() {
        notificationJob?.cancel()
        notificationJob =
            serviceScope.launch {
                BirdBuddySessionStore.state.collectLatest {
                    startOrUpdateForegroundNotification()
                }
            }
    }

    private fun startOrUpdateForegroundNotification() {
        val notification =
            BirdBuddyNotificationHelper.buildPersistentNotification(
                context = this,
                state = BirdBuddySessionStore.state.value
            )

        startForeground(BIRD_BUDDY_NOTIFICATION_ID, notification)

        NotificationManagerCompat.from(this).notify(BIRD_BUDDY_NOTIFICATION_ID, notification)
    }

    private fun createController(): BirdingBuddyController =
        BirdingBuddyController(applicationContext) {
            BuildConfig.BIRDNET_ANALYZER_URL
        }

    private fun ensureArmedAndMicPermission(): Boolean {
        if (!BirdBuddySessionStore.state.value.armed) {
            armInternal()
        }

        val hasMicPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            controller.notifyUser("Microphone permission is required.")
            BirdBuddyNotificationHelper.postActionableNotification(
                context = this,
                reason = "Microphone permission is required. Open app and grant mic access."
            )
            return false
        }

        return true
    }

    private fun addServiceLog(message: String) {
        val timestamp =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        controller.logs.add(0, "[$timestamp] $message")
        while (controller.logs.size > 300) {
            controller.logs.removeAt(controller.logs.lastIndex)
        }
    }

    private fun isArmedPersisted(): Boolean = prefs.getBoolean(KEY_ARMED, true)

    private fun persistArmed(armed: Boolean) {
        prefs.edit().putBoolean(KEY_ARMED, armed).apply()
    }

    private fun parseTriggerSource(intent: Intent?): BirdBuddyTriggerSource? {
        val raw = intent?.getStringExtra(BirdBuddyActions.EXTRA_TRIGGER_SOURCE) ?: return null
        return runCatching { BirdBuddyTriggerSource.valueOf(raw) }.getOrNull()
    }

    companion object {
        private const val ANALYZE_CAPTURE_TARGET_MS = 10_000L
        private const val ANALYZE_CAPTURE_SAFETY_BUFFER_MS = 750L
        private const val PREFS_NAME = "bird_buddy_service_prefs"
        private const val KEY_ARMED = "armed"

        fun enqueueAction(
            context: Context,
            action: String,
            triggerSource: BirdBuddyTriggerSource? = null
        ) {
            val intent =
                Intent(context, BirdBuddySessionService::class.java)
                    .setAction(action)

            if (triggerSource != null) {
                intent.putExtra(BirdBuddyActions.EXTRA_TRIGGER_SOURCE, triggerSource.name)
            }

            ContextCompat.startForegroundService(context, intent)
        }
    }
}
