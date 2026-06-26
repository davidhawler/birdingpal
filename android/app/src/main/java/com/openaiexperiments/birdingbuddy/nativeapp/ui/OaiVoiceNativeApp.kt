package com.openaiexperiments.birdingbuddy.nativeapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openaiexperiments.birdingbuddy.R
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddyActions
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddyBleProtocol
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionService
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionState
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionStore
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddyTriggerSource
import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatMessage
import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatRole

private val AppColorScheme =
    lightColorScheme(
        primary = Color(0xFF006AFF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE9FF),
        onPrimaryContainer = Color(0xFF001D3A),
        secondary = Color(0xFF006AFF),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFECECEC),
        onSecondaryContainer = Color(0xFF111111),
        tertiary = Color(0xFF006AFF),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFECECEC),
        onTertiaryContainer = Color(0xFF111111),
        background = Color(0xFFF2F2F2),
        onBackground = Color(0xFF111111),
        surface = Color.White,
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFE5E5E5),
        onSurfaceVariant = Color(0xFF4A4A4A),
        outline = Color(0xFF9A9A9A),
        outlineVariant = Color(0xFFCFCFCF),
        inverseSurface = Color(0xFF1F1F1F),
        inverseOnSurface = Color(0xFFF2F2F2),
        inversePrimary = Color(0xFF87B4FF),
        surfaceTint = Color(0xFF006AFF),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B)
    )

private val ChatUserBubbleColor = Color(0xFFE6E6E6)
private val ChatAssistantBubbleColor = Color(0xFFF5F5F5)
private val ChatSystemBubbleColor = Color(0xFFDDDDDD)
private val ChatTextColor = Color(0xFF111111)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OaiVoiceNativeApp() {
    val context = LocalContext.current
    var permissionCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            permissionCallback?.invoke(granted)
            permissionCallback = null
        }

    val requestPermissions: (Array<String>, (Boolean) -> Unit) -> Unit = { permissions, onResult ->
        val missing =
            permissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isEmpty()) {
            onResult(true)
        } else {
            permissionCallback = onResult
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val ensureBlePermissions: ((Boolean) -> Unit) -> Unit = { onResult ->
        val blePermissions =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                }

                else -> {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

        requestPermissions(blePermissions, onResult)
    }

    MaterialTheme(colorScheme = AppColorScheme) {
        val sessionState by BirdBuddySessionStore.state.collectAsState()

        DisposableEffect(Unit) {
            ensureBlePermissions { bleGranted ->
                BirdBuddySessionService.enqueueAction(
                    context = context,
                    action = BirdBuddyActions.ACTION_ARM,
                    triggerSource = BirdBuddyTriggerSource.UI
                )

                if (bleGranted) {
                    BirdBuddySessionService.enqueueAction(
                        context = context,
                        action = BirdBuddyActions.ACTION_RETRY_BLE_CONNECT,
                        triggerSource = BirdBuddyTriggerSource.UI
                    )
                } else {
                    BirdBuddySessionStore.appendLog(
                        "BLE permissions denied. Grant BLUETOOTH_SCAN/CONNECT to pair remote."
                    )
                }
            }
            onDispose {}
        }

        BirdingBuddyScreen(
            state = sessionState,
            onReconnect = {
                BirdBuddySessionService.enqueueAction(
                    context = context,
                    action = BirdBuddyActions.ACTION_RECONNECT_REALTIME,
                    triggerSource = BirdBuddyTriggerSource.UI
                )
            },
            onArm = {
                ensureBlePermissions { bleGranted ->
                    BirdBuddySessionService.enqueueAction(
                        context = context,
                        action = BirdBuddyActions.ACTION_ARM,
                        triggerSource = BirdBuddyTriggerSource.UI
                    )
                    if (bleGranted) {
                        BirdBuddySessionService.enqueueAction(
                            context = context,
                            action = BirdBuddyActions.ACTION_RETRY_BLE_CONNECT,
                            triggerSource = BirdBuddyTriggerSource.UI
                        )
                    } else {
                        BirdBuddySessionStore.appendLog(
                            "BLE permissions denied. Grant BLUETOOTH_SCAN/CONNECT to pair remote."
                        )
                    }
                }
            },
            onDisarm = {
                BirdBuddySessionService.enqueueAction(
                    context = context,
                    action = BirdBuddyActions.ACTION_DISARM,
                    triggerSource = BirdBuddyTriggerSource.UI
                )
            },
            onRetryBleConnect = {
                ensureBlePermissions { bleGranted ->
                    if (bleGranted) {
                        BirdBuddySessionService.enqueueAction(
                            context = context,
                            action = BirdBuddyActions.ACTION_RETRY_BLE_CONNECT,
                            triggerSource = BirdBuddyTriggerSource.UI
                        )
                    } else {
                        BirdBuddySessionStore.appendLog(
                            "BLE permissions denied. Cannot scan/connect to remote."
                        )
                    }
                }
            },
            onAnalyzeBirdCallTimed = {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
                    if (granted) {
                        BirdBuddySessionService.enqueueAction(
                            context = context,
                            action = BirdBuddyActions.ACTION_START_ANALYZE_10S,
                            triggerSource = BirdBuddyTriggerSource.UI
                        )
                    } else {
                        BirdBuddySessionStore.appendLog("Microphone permission is required.")
                    }
                }
            },
            onHoldTalkStart = {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
                    if (granted) {
                        BirdBuddySessionService.enqueueAction(
                            context = context,
                            action = BirdBuddyActions.ACTION_HOLD_TALK_START,
                            triggerSource = BirdBuddyTriggerSource.UI
                        )
                    } else {
                        BirdBuddySessionStore.appendLog("Microphone permission is required.")
                    }
                }
            },
            onHoldTalkEnd = {
                BirdBuddySessionService.enqueueAction(
                    context = context,
                    action = BirdBuddyActions.ACTION_HOLD_TALK_END,
                    triggerSource = BirdBuddyTriggerSource.UI
                )
            }
        )
    }
}

@Composable
private fun StatusRow(
    statusText: String,
    live: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .background(
                        color = if (live) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                        shape = CircleShape
                    )
        )
        Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChatMessages(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(messages) { message ->
            val bubbleColor =
                when (message.role) {
                    ChatRole.USER -> ChatUserBubbleColor
                    ChatRole.ASSISTANT -> ChatAssistantBubbleColor
                    ChatRole.SYSTEM -> ChatSystemBubbleColor
                }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (message.role == ChatRole.USER) {
                        Arrangement.End
                    } else {
                        Arrangement.Start
                    }
            ) {
                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = message.text,
                        color = ChatTextColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirdingBuddyScreen(
    state: BirdBuddySessionState,
    onReconnect: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRetryBleConnect: () -> Unit,
    onAnalyzeBirdCallTimed: () -> Unit,
    onHoldTalkStart: () -> Unit,
    onHoldTalkEnd: () -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Birding Buddy") },
                actions = {
                    TextButton(onClick = { showLogs = true }) {
                        Text("Logs")
                    }
                    TextButton(onClick = onReconnect) {
                        Text("Reconnect")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusRow(statusText = state.statusText, live = state.statusLive)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .background(
                                color = if (state.bleConnected) Color(0xFF2E7D32) else Color(0xFFC62828),
                                shape = CircleShape
                            )
                )
                Text(
                    text = "BLE: ${state.bleStatusText}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Protocol: <Buffer 00 43> = analyze (10s), <Buffer 00 45> = hold heartbeat (>= 1 msg/sec).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Service UUID: ${BirdBuddyBleProtocol.SERVICE_UUID}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Message UUID: ${BirdBuddyBleProtocol.MESSAGE_CHARACTERISTIC_UUID}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.bleLastPacketHex.isNotBlank()) {
                Text(
                    text = "Last BLE packet: <Buffer ${state.bleLastPacketHex}>",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAnalyzeBirdCallTimed, enabled = state.armed) {
                    Text("Analyze Bird Call (10s)")
                }
                Button(onClick = onRetryBleConnect, enabled = state.armed) {
                    Text("Retry BLE")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onArm, enabled = !state.armed) {
                    Text("Arm Service")
                }
                Button(onClick = onDisarm, enabled = state.armed) {
                    Text("Disarm")
                }
            }

            HoldButton(
                text = if (state.isHoldingToTalk) "Release to Send" else "Hold to Talk",
                enabled = state.armed,
                onPressStart = onHoldTalkStart,
                onPressEnd = onHoldTalkEnd
            )

            Text(
                text = "Conversation",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            ChatMessages(
                messages = state.chatMessages,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showLogs) {
        LogsDialog(logs = state.logs, onClose = { showLogs = false })
    }
}

@Composable
private fun HoldButton(
    text: String,
    enabled: Boolean = true,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val labelColor =
        if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            if (!enabled) {
                                return@detectTapGestures
                            }
                            onPressStart()
                            try {
                                tryAwaitRelease()
                            } finally {
                                onPressEnd()
                            }
                        }
                    )
            },
        shape = RoundedCornerShape(14.dp),
        color = containerColor
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.mic),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.Unspecified
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = labelColor
                )
            }
        }
    }
}

@Composable
private fun LogsDialog(
    logs: List<String>,
    onClose: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Session Log") },
        text = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (logs.isEmpty()) "No logs yet." else logs.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }
    )
}
