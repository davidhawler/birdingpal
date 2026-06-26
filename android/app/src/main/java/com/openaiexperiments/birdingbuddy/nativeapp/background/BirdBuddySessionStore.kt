package com.openaiexperiments.birdingbuddy.nativeapp.background

import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BirdBuddySessionStore {
    private val _state = MutableStateFlow(BirdBuddySessionState())
    val state: StateFlow<BirdBuddySessionState> = _state.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _state.update { it.copy(serviceRunning = running) }
    }

    fun setArmed(armed: Boolean) {
        _state.update {
            it.copy(
                armed = armed,
                statusText = if (armed) it.statusText else "Bird Buddy service disarmed.",
                statusLive = if (armed) it.statusLive else false
            )
        }
    }

    fun setBleState(
        connected: Boolean,
        statusText: String,
        deviceName: String? = null,
        lastPacketHex: String? = null
    ) {
        _state.update {
            it.copy(
                bleConnected = connected,
                bleStatusText = statusText,
                bleDeviceName = deviceName ?: it.bleDeviceName,
                bleLastPacketHex = lastPacketHex ?: it.bleLastPacketHex
            )
        }
    }

    fun setStatus(
        text: String,
        live: Boolean = false,
        triggerSource: BirdBuddyTriggerSource? = null,
        lastError: String? = null
    ) {
        _state.update {
            it.copy(
                statusText = text,
                statusLive = live,
                lastTriggerSource = triggerSource ?: it.lastTriggerSource,
                lastError = lastError ?: it.lastError
            )
        }
    }

    fun syncControllerSnapshot(
        serverStatus: String,
        statusText: String,
        statusLive: Boolean,
        isSocketConnected: Boolean,
        isCapturing: Boolean,
        isHoldingToTalk: Boolean,
        analyzingBirdCall: Boolean,
        chatMessages: List<ChatMessage>,
        logs: List<String>
    ) {
        _state.update {
            it.copy(
                serverStatus = serverStatus,
                statusText = statusText,
                statusLive = statusLive,
                isSocketConnected = isSocketConnected,
                isCapturing = isCapturing,
                isHoldingToTalk = isHoldingToTalk,
                analyzingBirdCall = analyzingBirdCall,
                chatMessages = chatMessages.takeLast(200),
                logs = logs.take(300)
            )
        }
    }

    fun appendLog(message: String) {
        if (message.isBlank()) {
            return
        }

        val timestamp =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        _state.update { current ->
            val bounded = (listOf("[$timestamp] $message") + current.logs).take(300)
            current.copy(logs = bounded)
        }
    }
}
