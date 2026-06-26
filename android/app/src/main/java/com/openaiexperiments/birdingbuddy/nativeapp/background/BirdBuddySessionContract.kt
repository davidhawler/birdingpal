package com.openaiexperiments.birdingbuddy.nativeapp.background

import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatMessage

const val BIRD_BUDDY_CHANNEL_ID = "bird_buddy_runtime"
const val BIRD_BUDDY_ALERT_CHANNEL_ID = "bird_buddy_alerts"
const val BIRD_BUDDY_NOTIFICATION_ID = 4201
const val BIRD_BUDDY_ALERT_NOTIFICATION_ID = 4202

enum class BirdBuddyTriggerSource {
    UI,
    BLE_ANALYZE,
    BLE_HOLD_HEARTBEAT,
    NOTIFICATION,
    UNKNOWN
}

data class BirdBuddySessionState(
    val serviceRunning: Boolean = false,
    val armed: Boolean = false,
    val serverStatus: String = "idle",
    val statusText: String = "Bird Buddy service idle.",
    val statusLive: Boolean = false,
    val isSocketConnected: Boolean = false,
    val isCapturing: Boolean = false,
    val isHoldingToTalk: Boolean = false,
    val analyzingBirdCall: Boolean = false,
    val bleConnected: Boolean = false,
    val bleStatusText: String = "BLE not connected",
    val bleDeviceName: String? = null,
    val bleLastPacketHex: String = "",
    val lastTriggerSource: BirdBuddyTriggerSource? = null,
    val lastError: String? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
    val logs: List<String> = emptyList()
)

object BirdBuddyActions {
    private const val PREFIX = "com.openaiexperiments.birdingbuddy.action"

    const val ACTION_ARM = "$PREFIX.BIRD_BUDDY_ARM"
    const val ACTION_DISARM = "$PREFIX.BIRD_BUDDY_DISARM"
    const val ACTION_START_ANALYZE_10S = "$PREFIX.BIRD_BUDDY_START_ANALYZE_10S"
    const val ACTION_HOLD_TALK_START = "$PREFIX.BIRD_BUDDY_HOLD_TALK_START"
    const val ACTION_HOLD_TALK_END = "$PREFIX.BIRD_BUDDY_HOLD_TALK_END"
    const val ACTION_RECONNECT_REALTIME = "$PREFIX.BIRD_BUDDY_RECONNECT_REALTIME"
    const val ACTION_RETRY_BLE_CONNECT = "$PREFIX.BIRD_BUDDY_RETRY_BLE_CONNECT"

    const val ACTION_NOTIFICATION_ARM = "$PREFIX.BIRD_BUDDY_NOTIFICATION_ARM"
    const val ACTION_NOTIFICATION_DISARM = "$PREFIX.BIRD_BUDDY_NOTIFICATION_DISARM"
    const val ACTION_NOTIFICATION_START_ANALYZE = "$PREFIX.BIRD_BUDDY_NOTIFICATION_START_ANALYZE"

    const val EXTRA_TRIGGER_SOURCE = "$PREFIX.extra.TRIGGER_SOURCE"
}
