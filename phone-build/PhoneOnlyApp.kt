package com.openaiexperiments.birdingbuddy.nativeapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openaiexperiments.birdingbuddy.BuildConfig
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddyActions
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionService
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionState
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddySessionStore
import com.openaiexperiments.birdingbuddy.nativeapp.background.BirdBuddyTriggerSource
import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatMessage
import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val SETTINGS_PREFS = "birdingpal_settings"
private const val PREF_OPENAI_API_KEY = "openai_api_key"
private const val PREF_BIRDNET_ANALYZER_URL = "birdnet_analyzer_url"

private val PhoneColorScheme =
    lightColorScheme(
        primary = Color(0xFF176B3A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8F3DF),
        onPrimaryContainer = Color(0xFF0B351D),
        background = Color(0xFFF5F7F3),
        onBackground = Color(0xFF151A16),
        surface = Color.White,
        onSurface = Color(0xFF151A16),
        surfaceVariant = Color(0xFFE7ECE7),
        onSurfaceVariant = Color(0xFF4B554D)
    )

private data class BirdBookEntry(
    val name: String,
    val family: String,
    val habitat: String,
    val diet: String,
    val notes: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneOnlyApp() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var storedApiKey by remember { mutableStateOf(prefs.getString(PREF_OPENAI_API_KEY, "").orEmpty()) }
    var storedBirdNetUrl by remember { mutableStateOf(prefs.getString(PREF_BIRDNET_ANALYZER_URL, "").orEmpty()) }
    var pendingPermissionAction by remember { mutableStateOf<((Map<String, Boolean>) -> Unit)?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingPermissionAction?.invoke(result)
            pendingPermissionAction = null
        }

    fun runWithMicrophone(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        pendingPermissionAction = { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                action()
            } else {
                BirdBuddySessionStore.appendLog("Microphone permission is required.")
            }
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    fun runBirdCallAnalysis() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val micAlreadyGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val coarseAlreadyGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fineAlreadyGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (micAlreadyGranted && (coarseAlreadyGranted || fineAlreadyGranted)) {
            BirdBuddySessionService.enqueueAction(
                context = context,
                action = BirdBuddyActions.ACTION_START_ANALYZE_10S,
                triggerSource = BirdBuddyTriggerSource.UI
            )
            return
        }

        pendingPermissionAction = { result ->
            val micGranted = micAlreadyGranted || result[Manifest.permission.RECORD_AUDIO] == true
            val locationGranted =
                coarseAlreadyGranted || fineAlreadyGranted ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if (!micGranted) {
                BirdBuddySessionStore.appendLog("Microphone permission is required.")
            } else {
                if (!locationGranted) {
                    BirdBuddySessionStore.appendLog("Location denied; BirdNET will use fallback coordinates.")
                }
                BirdBuddySessionService.enqueueAction(
                    context = context,
                    action = BirdBuddyActions.ACTION_START_ANALYZE_10S,
                    triggerSource = BirdBuddyTriggerSource.UI
                )
            }
        }
        permissionLauncher.launch(permissions)
    }

    MaterialTheme(colorScheme = PhoneColorScheme) {
        val state by BirdBuddySessionStore.state.collectAsState()
        val buildApiKeyConfigured = BuildConfig.OPENAI_API_KEY.isNotBlank()
        val buildBirdNetConfigured = BuildConfig.BIRDNET_ANALYZER_URL.isNotBlank()
        val apiKeyConfigured = storedApiKey.isNotBlank() || buildApiKeyConfigured
        val birdNetConfigured = storedBirdNetUrl.isNotBlank() || buildBirdNetConfigured

        PhoneOnlyScreen(
            state = state,
            apiKeyConfigured = apiKeyConfigured,
            birdNetConfigured = birdNetConfigured,
            storedApiKey = storedApiKey,
            storedBirdNetUrl = storedBirdNetUrl,
            buildApiKeyConfigured = buildApiKeyConfigured,
            buildBirdNetConfigured = buildBirdNetConfigured,
            onSaveSettings = { apiKey, birdNetUrl ->
                prefs.edit()
                    .putString(PREF_OPENAI_API_KEY, apiKey.trim())
                    .putString(PREF_BIRDNET_ANALYZER_URL, birdNetUrl.trim())
                    .apply()
                storedApiKey = apiKey.trim()
                storedBirdNetUrl = birdNetUrl.trim()
                if (state.armed) {
                    BirdBuddySessionService.enqueueAction(
                        context = context,
                        action = BirdBuddyActions.ACTION_RECONNECT_REALTIME,
                        triggerSource = BirdBuddyTriggerSource.UI
                    )
                }
            },
            onConnect = {
                runWithMicrophone {
                    BirdBuddySessionService.enqueueAction(
                        context = context,
                        action = if (state.armed) BirdBuddyActions.ACTION_RECONNECT_REALTIME else BirdBuddyActions.ACTION_ARM,
                        triggerSource = BirdBuddyTriggerSource.UI
                    )
                }
            },
            onAnalyzeBirdCall = { runBirdCallAnalysis() },
            onHoldTalkStart = {
                runWithMicrophone {
                    BirdBuddySessionService.enqueueAction(
                        context = context,
                        action = BirdBuddyActions.ACTION_HOLD_TALK_START,
                        triggerSource = BirdBuddyTriggerSource.UI
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneOnlyScreen(
    state: BirdBuddySessionState,
    apiKeyConfigured: Boolean,
    birdNetConfigured: Boolean,
    storedApiKey: String,
    storedBirdNetUrl: String,
    buildApiKeyConfigured: Boolean,
    buildBirdNetConfigured: Boolean,
    onSaveSettings: (String, String) -> Unit,
    onConnect: () -> Unit,
    onAnalyzeBirdCall: () -> Unit,
    onHoldTalkStart: () -> Unit,
    onHoldTalkEnd: () -> Unit
) {
    val context = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }
    var showBirdBook by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(!apiKeyConfigured) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("BirdingPal") },
                actions = {
                    TextButton(onClick = { showBirdBook = true }) { Text("Bird Book") }
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusRow(state.statusText, state.statusLive)

            if (!apiKeyConfigured) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("One-time setup", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Add your OpenAI API key on this phone, then connect. The key is kept in BirdingPal's private app storage and Android backup is disabled for this build.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Add OpenAI API Key")
                        }
                    }
                }
            } else if (!state.isSocketConnected) {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.armed) "Reconnect to BirdingPal" else "Connect to BirdingPal")
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Phone mode", fontWeight = FontWeight.SemiBold)
                    Text(
                        "No plush bird or Bluetooth device is required. Use the phone microphone to talk or record a bird call.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = onAnalyzeBirdCall,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.armed && birdNetConfigured && !state.analyzingBirdCall
            ) {
                Text(if (state.analyzingBirdCall) "Listening for bird call…" else "Identify Bird Call (10 sec)")
            }

            if (!birdNetConfigured) {
                Text(
                    "Bird-call audio analysis is optional and needs a BirdNET-compatible endpoint in Settings. Voice identification and the Bird Book work without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HoldToTalkButton(
                text = if (state.isHoldingToTalk) "Release to Send" else "Hold to Talk",
                enabled = state.armed && apiKeyConfigured && !state.analyzingBirdCall,
                onPressStart = onHoldTalkStart,
                onPressEnd = onHoldTalkEnd
            )

            Text(
                "Conversation",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            ChatMessages(
                messages = state.chatMessages,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = { showLogs = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Diagnostics")
            }
        }
    }

    if (showLogs) {
        LogsDialog(state.logs) { showLogs = false }
    }
    if (showBirdBook) {
        BirdBookDialog(loadBirdBook(context.filesDir)) { showBirdBook = false }
    }
    if (showSettings) {
        SettingsDialog(
            apiKey = storedApiKey,
            birdNetUrl = storedBirdNetUrl,
            buildApiKeyConfigured = buildApiKeyConfigured,
            buildBirdNetConfigured = buildBirdNetConfigured,
            onSave = { apiKey, birdNetUrl ->
                onSaveSettings(apiKey, birdNetUrl)
                showSettings = false
            },
            onClose = { showSettings = false }
        )
    }
}

@Composable
private fun SettingsDialog(
    apiKey: String,
    birdNetUrl: String,
    buildApiKeyConfigured: Boolean,
    buildBirdNetConfigured: Boolean,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit
) {
    var keyDraft by remember(apiKey) { mutableStateOf(apiKey) }
    var birdNetDraft by remember(birdNetUrl) { mutableStateOf(birdNetUrl) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("BirdingPal Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "OpenAI API key",
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("sk-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (buildApiKeyConfigured && keyDraft.isBlank()) {
                    Text(
                        "This private build already contains an OpenAI API key. Entering one here overrides it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Stored in this app's private storage on the phone. It is not committed to GitHub.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    "BirdNET endpoint (optional)",
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = birdNetDraft,
                    onValueChange = { birdNetDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https://…") },
                    singleLine = true
                )
                Text(
                    if (buildBirdNetConfigured && birdNetDraft.isBlank()) {
                        "A BirdNET endpoint is already included in this build."
                    } else {
                        "Leave blank if you only want conversational bird identification."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(keyDraft.trim(), birdNetDraft.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    )
}

@Composable
private fun StatusRow(statusText: String, live: Boolean) {
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
                        if (live) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                        CircleShape
                    )
        )
        Text(statusText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChatMessages(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(messages) { message ->
            val isUser = message.role == ChatRole.USER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldToTalkButton(
    text: String,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val background =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val foreground =
        if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            if (!enabled) return@detectTapGestures
                            onPressStart()
                            try {
                                tryAwaitRelease()
                            } finally {
                                onPressEnd()
                            }
                        }
                    )
                },
        shape = RoundedCornerShape(18.dp),
        color = background
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = foreground, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LogsDialog(logs: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Diagnostics") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (logs.isEmpty()) Text("No logs yet.")
                logs.take(100).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun BirdBookDialog(entries: List<BirdBookEntry>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("My Bird Book") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entries.isEmpty()) {
                    item {
                        Text("No birds saved yet. Ask BirdingPal to add an identified bird to your bird book.")
                    }
                }
                items(entries) { bird ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(bird.name, fontWeight = FontWeight.Bold)
                            if (bird.family.isNotBlank()) Text("Family: ${bird.family}", style = MaterialTheme.typography.bodySmall)
                            if (bird.habitat.isNotBlank()) Text("Habitat: ${bird.habitat}", style = MaterialTheme.typography.bodySmall)
                            if (bird.diet.isNotBlank()) Text("Diet: ${bird.diet}", style = MaterialTheme.typography.bodySmall)
                            if (bird.notes.isNotBlank()) Text(bird.notes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private fun loadBirdBook(filesDir: File): List<BirdBookEntry> {
    val file = File(filesDir, "bird_book.json")
    if (!file.exists()) return emptyList()
    return runCatching {
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: JSONObject()
                add(
                    BirdBookEntry(
                        name = obj.optString("name", "Unknown bird"),
                        family = obj.optString("family", ""),
                        habitat = obj.optString("habitat", ""),
                        diet = obj.optString("diet", ""),
                        notes = obj.optString("notes", "")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
