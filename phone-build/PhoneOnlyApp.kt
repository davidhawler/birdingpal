package com.openaiexperiments.birdingbuddy.nativeapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

private const val SETTINGS_PREFS = "birdingpal_settings"
private const val PREF_OPENAI_API_KEY = "openai_api_key"
private const val PREF_BIRDNET_ANALYZER_URL = "birdnet_analyzer_url"
private const val PREF_ONBOARDING_DONE = "onboarding_done_v2"

private val LightBirdingColors =
    lightColorScheme(
        primary = Color(0xFF176B3A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD9F2DF),
        onPrimaryContainer = Color(0xFF0B351D),
        secondary = Color(0xFF49664E),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE8DC),
        onSecondaryContainer = Color(0xFF172B1B),
        tertiary = Color(0xFF7A5D20),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE8A9),
        onTertiaryContainer = Color(0xFF2A1D00),
        background = Color(0xFFF6F8F4),
        onBackground = Color(0xFF171C18),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF171C18),
        surfaceVariant = Color(0xFFE6ECE5),
        onSurfaceVariant = Color(0xFF465048),
        outline = Color(0xFF768078)
    )

private val DarkBirdingColors =
    darkColorScheme(
        primary = Color(0xFF91D5A6),
        onPrimary = Color(0xFF00391B),
        primaryContainer = Color(0xFF0E512A),
        onPrimaryContainer = Color(0xFFB1F2C0),
        secondary = Color(0xFFB8CCB8),
        onSecondary = Color(0xFF233426),
        secondaryContainer = Color(0xFF394B3C),
        onSecondaryContainer = Color(0xFFD3E8D3),
        tertiary = Color(0xFFE8C56D),
        onTertiary = Color(0xFF3D2E00),
        tertiaryContainer = Color(0xFF574500),
        onTertiaryContainer = Color(0xFFFFE8A9),
        background = Color(0xFF101512),
        onBackground = Color(0xFFE0E5DF),
        surface = Color(0xFF171C18),
        onSurface = Color(0xFFE0E5DF),
        surfaceVariant = Color(0xFF29312B),
        onSurfaceVariant = Color(0xFFC3CBC3),
        outline = Color(0xFF8D978F)
    )

private enum class AppTab(val label: String, val glyph: String) {
    HOME("Explore", "⌂"),
    BIRD_BOOK("Bird Book", "♧"),
    SETTINGS("Settings", "⚙")
}

private data class BirdBookEntry(
    val name: String,
    val commonName: String,
    val scientificName: String,
    val family: String,
    val habitat: String,
    val size: String,
    val diet: String,
    val notes: String,
    val addedAt: Long,
    val lat: Double?,
    val lon: Double?,
    val confidencePercent: Int?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneOnlyApp() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }
    var storedApiKey by remember { mutableStateOf(prefs.getString(PREF_OPENAI_API_KEY, "").orEmpty()) }
    var storedBirdNetUrl by remember { mutableStateOf(prefs.getString(PREF_BIRDNET_ANALYZER_URL, "").orEmpty()) }
    var onboardingDone by remember { mutableStateOf(prefs.getBoolean(PREF_ONBOARDING_DONE, false)) }
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

    fun runConnectPermissions(action: () -> Unit) {
        val permissions =
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        val micGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val coarseGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fineGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (micGranted && (coarseGranted || fineGranted)) {
            action()
            return
        }

        pendingPermissionAction = { result ->
            val finalMicGranted = micGranted || result[Manifest.permission.RECORD_AUDIO] == true
            val finalLocationGranted =
                coarseGranted || fineGranted ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if (!finalMicGranted) {
                BirdBuddySessionStore.appendLog("Microphone permission is required.")
            } else {
                if (!finalLocationGranted) {
                    BirdBuddySessionStore.appendLog("Location denied. BirdingPal will still work, but regional matching and sighting locations may be less useful.")
                }
                action()
            }
        }
        permissionLauncher.launch(permissions)
    }

    fun runBirdCallAnalysis() {
        runConnectPermissions {
            BirdBuddySessionService.enqueueAction(
                context = context,
                action = BirdBuddyActions.ACTION_START_ANALYZE_10S,
                triggerSource = BirdBuddyTriggerSource.UI
            )
        }
    }

    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (darkTheme) DarkBirdingColors else LightBirdingColors) {
        val state by BirdBuddySessionStore.state.collectAsState()
        val buildApiKeyConfigured = BuildConfig.OPENAI_API_KEY.isNotBlank()
        val buildBirdNetConfigured = BuildConfig.BIRDNET_ANALYZER_URL.isNotBlank()
        val apiKeyConfigured = storedApiKey.isNotBlank() || buildApiKeyConfigured
        val birdNetConfigured = storedBirdNetUrl.isNotBlank() || buildBirdNetConfigured
        val birdBook = remember(state.chatMessages.size, state.statusText, state.candidateName) { loadBirdBook(context.filesDir) }

        if (!onboardingDone) {
            OnboardingScreen(
                onFinished = {
                    prefs.edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
                    onboardingDone = true
                }
            )
        } else {
            BirdingPalShell(
                state = state,
                birdBook = birdBook,
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
                    runConnectPermissions {
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
}

@Composable
private fun OnboardingScreen(onFinished: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (page < 2) {
                TextButton(onClick = onFinished) { Text("Skip") }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            BirdArt(speciesName = "BirdingPal", size = 164.dp)

            when (page) {
                0 -> {
                    Text("Meet BirdingPal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Your AI birding companion for the field — describe what you see, talk naturally, and keep a personal record of the birds you find.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                1 -> {
                    Text("Three simple ways to use it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    FeatureRow("🎙", "Talk", "Hold the microphone and describe the bird you see.")
                    FeatureRow("♫", "Listen", "Record a 10-second bird call when a BirdNET endpoint is configured.")
                    FeatureRow("♧", "Save", "Ask BirdingPal to add a confirmed identification to your Bird Book.")
                }

                else -> {
                    Text("Ready for the field", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "BirdingPal will ask for microphone access. Location is optional, but it improves regional bird-call matching and lets saved sightings include coordinates.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Your OpenAI API key is entered inside the app and stored in BirdingPal's private app storage on this phone.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(3) { index ->
                    Box(
                        modifier =
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (index == page) 10.dp else 7.dp)
                                .background(
                                    if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                    )
                }
            }

            Button(
                onClick = {
                    if (page < 2) page += 1 else onFinished()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (page < 2) "Continue" else "Get started")
            }
            if (page > 0) {
                TextButton(onClick = { page -= 1 }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}

@Composable
private fun FeatureRow(glyph: String, title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(glyph, fontSize = 28.sp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirdingPalShell(
    state: BirdBuddySessionState,
    birdBook: List<BirdBookEntry>,
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
    var selectedTab by remember(apiKeyConfigured) {
        mutableStateOf(if (apiKeyConfigured) AppTab.HOME else AppTab.SETTINGS)
    }
    var showLogs by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("BirdingPal", fontWeight = FontWeight.Bold)
                        Text(
                            when (selectedTab) {
                                AppTab.HOME -> "AI birding companion"
                                AppTab.BIRD_BOOK -> "${birdBook.size} saved ${if (birdBook.size == 1) "bird" else "birds"}"
                                AppTab.SETTINGS -> "App settings"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.glyph, fontSize = 20.sp) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
        ) {
            when (selectedTab) {
                AppTab.HOME ->
                    HomeScreen(
                        state = state,
                        recentBird = birdBook.lastOrNull(),
                        apiKeyConfigured = apiKeyConfigured,
                        birdNetConfigured = birdNetConfigured,
                        onConnect = onConnect,
                        onAnalyzeBirdCall = onAnalyzeBirdCall,
                        onHoldTalkStart = onHoldTalkStart,
                        onHoldTalkEnd = onHoldTalkEnd,
                        onOpenSettings = { selectedTab = AppTab.SETTINGS },
                        onOpenBirdBook = { selectedTab = AppTab.BIRD_BOOK }
                    )

                AppTab.BIRD_BOOK -> BirdBookScreen(birdBook)

                AppTab.SETTINGS ->
                    SettingsScreen(
                        apiKey = storedApiKey,
                        birdNetUrl = storedBirdNetUrl,
                        buildApiKeyConfigured = buildApiKeyConfigured,
                        buildBirdNetConfigured = buildBirdNetConfigured,
                        onSave = onSaveSettings,
                        onDiagnostics = { showLogs = true }
                    )
            }
        }
    }

    if (showLogs) {
        LogsDialog(state.logs) { showLogs = false }
    }
}

@Composable
private fun HomeScreen(
    state: BirdBuddySessionState,
    recentBird: BirdBookEntry?,
    apiKeyConfigured: Boolean,
    birdNetConfigured: Boolean,
    onConnect: () -> Unit,
    onAnalyzeBirdCall: () -> Unit,
    onHoldTalkStart: () -> Unit,
    onHoldTalkEnd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBirdBook: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatusPill(state.statusText, state.statusLive)

        if (!apiKeyConfigured) {
            SetupCard(onOpenSettings)
        } else if (!state.isSocketConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Connect BirdingPal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Connect once, then hold the microphone whenever you want to ask about a bird.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.armed) "Reconnect" else "Connect")
                    }
                }
            }
        }

        if (!state.candidateName.isNullOrBlank()) {
            IdentificationCard(
                name = state.candidateName.orEmpty(),
                confidencePercent = state.candidateConfidencePercent,
                source = state.candidateSource ?: "Identification"
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when {
                    state.isHoldingToTalk -> "Listening…"
                    state.analyzingBirdCall -> "Analyzing the bird call…"
                    else -> "What bird did you find?"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (state.isHoldingToTalk) "Release when you're finished speaking." else "Press and hold to describe what you see.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HoldMicButton(
                active = state.isHoldingToTalk,
                enabled = state.armed && apiKeyConfigured && !state.analyzingBirdCall,
                onPressStart = onHoldTalkStart,
                onPressEnd = onHoldTalkEnd
            )

            AnimatedWaveform(active = state.isHoldingToTalk || state.analyzingBirdCall)
        }

        Button(
            onClick = onAnalyzeBirdCall,
            enabled = state.armed && birdNetConfigured && !state.analyzingBirdCall,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
        ) {
            Text(if (state.analyzingBirdCall) "Listening for 10 seconds…" else "♫  Identify a bird call")
        }

        if (!birdNetConfigured) {
            Text(
                "Bird-call listening is optional. Add a BirdNET-compatible endpoint in Settings to enable it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (recentBird != null) {
            RecentSightingCard(recentBird, onOpenBirdBook)
        }

        ConversationPreview(state.chatMessages)

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("One quick setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Add your OpenAI API key to start talking with BirdingPal.",
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Settings") }
        }
    }
}

@Composable
private fun IdentificationCard(name: String, confidencePercent: Int?, source: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BirdArt(speciesName = name, size = 88.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Likely match", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (confidencePercent != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(
                            progress = confidencePercent.coerceIn(0, 100) / 100f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("$confidencePercent%", fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Talk to BirdingPal to confirm it, compare alternatives, or save it to your Bird Book.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun RecentSightingCard(bird: BirdBookEntry, onOpenBirdBook: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Latest sighting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onOpenBirdBook) { Text("View book") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BirdArt(speciesName = bird.name, size = 72.dp)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(bird.commonName.ifBlank { bird.name }, fontWeight = FontWeight.Bold)
                    if (bird.scientificName.isNotBlank()) {
                        Text(
                            bird.scientificName,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(sightingMeta(bird), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ConversationPreview(messages: List<ChatMessage>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Conversation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (messages.isEmpty()) {
                Text(
                    "Your conversation with BirdingPal will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                messages.takeLast(6).forEach { message -> ChatBubble(message) }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
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

@Composable
private fun HoldMicButton(
    active: Boolean,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "micPulse")
    val pulse by
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.055f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "micScale"
        )

    val background = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier =
            Modifier
                .size(168.dp)
                .graphicsLayer {
                    val scale = if (active) pulse else 1f
                    scaleX = scale
                    scaleY = scale
                }
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
        shape = CircleShape,
        color = background,
        shadowElevation = if (enabled) 10.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🎙", fontSize = 42.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !enabled -> "Connect first"
                    active -> "Release to send"
                    else -> "Hold to talk"
                },
                color = foreground,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AnimatedWaveform(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "wavePhase"
        )
    val waveColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    Canvas(modifier = Modifier.width(160.dp).height(34.dp)) {
        val bars = 11
        val spacing = size.width / (bars + 1)
        for (index in 0 until bars) {
            val normalized = if (active) abs(sin((phase * 6.283f + index * 0.65f).toDouble())).toFloat() else 0.18f
            val barHeight = size.height * (0.20f + normalized * 0.72f)
            val x = spacing * (index + 1)
            drawLine(
                color = waveColor,
                start = Offset(x, (size.height - barHeight) / 2f),
                end = Offset(x, (size.height + barHeight) / 2f),
                strokeWidth = 7f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun StatusPill(statusText: String, live: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(if (live) Color(0xFF2E8B57) else MaterialTheme.colorScheme.outline, CircleShape)
            )
            Text(statusText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BirdBookScreen(entries: List<BirdBookEntry>) {
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BirdArt(speciesName = "New bird", size = 132.dp)
            Spacer(Modifier.height(20.dp))
            Text("Your Bird Book is ready", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "After BirdingPal identifies a bird, say “add that to my bird book.” Your saved sightings will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Your sightings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "A personal record of birds you've confirmed with BirdingPal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(entries.asReversed()) { bird -> BirdBookCard(bird) }
    }
}

@Composable
private fun BirdBookCard(bird: BirdBookEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BirdArt(speciesName = bird.name, size = 82.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(bird.commonName.ifBlank { bird.name }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (bird.scientificName.isNotBlank()) {
                        Text(
                            bird.scientificName,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (bird.family.isNotBlank()) {
                        Text(bird.family, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(sightingMeta(bird), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (bird.confidencePercent != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Call confidence", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = bird.confidencePercent.coerceIn(0, 100) / 100f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${bird.confidencePercent}%", fontWeight = FontWeight.Bold)
                }
            }

            if (bird.habitat.isNotBlank()) DetailLine("Habitat", bird.habitat)
            if (bird.diet.isNotBlank()) DetailLine("Diet", bird.diet)
            if (bird.size.isNotBlank()) DetailLine("Size", bird.size)
            if (bird.notes.isNotBlank()) DetailLine("Notes", bird.notes)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(
    apiKey: String,
    birdNetUrl: String,
    buildApiKeyConfigured: Boolean,
    buildBirdNetConfigured: Boolean,
    onSave: (String, String) -> Unit,
    onDiagnostics: () -> Unit
) {
    var keyDraft by remember(apiKey) { mutableStateOf(apiKey) }
    var birdNetDraft by remember(birdNetUrl) { mutableStateOf(birdNetUrl) }
    var savedMessage by remember { mutableStateOf("") }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Connection", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SettingsCard {
            Text("OpenAI API key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = keyDraft,
                onValueChange = {
                    keyDraft = it
                    savedMessage = ""
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("sk-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(
                if (buildApiKeyConfigured && keyDraft.isBlank()) {
                    "This build contains a fallback API key. A key entered here overrides it."
                } else {
                    "Stored in BirdingPal's private app storage on this phone and not committed to GitHub."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsCard {
            Text("Bird-call recognition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Optional. Add a BirdNET-compatible analyzer endpoint to enable the 10-second bird-call button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = birdNetDraft,
                onValueChange = {
                    birdNetDraft = it
                    savedMessage = ""
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("https://…") },
                singleLine = true
            )
            if (buildBirdNetConfigured && birdNetDraft.isBlank()) {
                Text("A BirdNET endpoint is already included in this build.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            onClick = {
                onSave(keyDraft.trim(), birdNetDraft.trim())
                savedMessage = "Settings saved"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save settings")
        }
        if (savedMessage.isNotBlank()) {
            Text(savedMessage, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }

        Text("Experience", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SettingsCard {
            Text("Appearance", fontWeight = FontWeight.Bold)
            Text(
                "BirdingPal follows your phone's light or dark appearance automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Phone-first mode", fontWeight = FontWeight.Bold)
            Text(
                "The plush bird and Bluetooth controls are disabled. Everything you need is on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text("Advanced", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(
            onClick = onDiagnostics,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
        ) {
            Text("Open diagnostics")
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
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
private fun BirdArt(speciesName: String, size: Dp) {
    val seed = (speciesName.hashCode() and Int.MAX_VALUE) % 3
    val wingColor =
        when (seed) {
            0 -> Color(0xFF4D7D5A)
            1 -> Color(0xFF58758A)
            else -> Color(0xFF8A704D)
        }
    val bodyColor = MaterialTheme.colorScheme.primary
    val headColor = MaterialTheme.colorScheme.onPrimaryContainer
    val beakColor = Color(0xFFE0A62E)
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer

    Canvas(
        modifier =
            Modifier
                .size(size)
                .background(backgroundColor, CircleShape)
                .padding(5.dp)
    ) {
        val w = this.size.width
        val h = this.size.height

        drawLine(
            color = wingColor.copy(alpha = 0.7f),
            start = Offset(w * 0.16f, h * 0.78f),
            end = Offset(w * 0.85f, h * 0.78f),
            strokeWidth = w * 0.035f,
            cap = StrokeCap.Round
        )
        drawOval(
            color = bodyColor,
            topLeft = Offset(w * 0.20f, h * 0.40f),
            size = Size(w * 0.57f, h * 0.34f)
        )
        drawCircle(
            color = headColor,
            radius = w * 0.145f,
            center = Offset(w * 0.69f, h * 0.38f)
        )
        drawOval(
            color = wingColor,
            topLeft = Offset(w * 0.33f, h * 0.48f),
            size = Size(w * 0.31f, h * 0.18f)
        )

        val beak = Path().apply {
            moveTo(w * 0.81f, h * 0.36f)
            lineTo(w * 0.96f, h * 0.42f)
            lineTo(w * 0.81f, h * 0.47f)
            close()
        }
        drawPath(beak, beakColor)

        val tail = Path().apply {
            moveTo(w * 0.25f, h * 0.57f)
            lineTo(w * 0.06f, h * 0.68f)
            lineTo(w * 0.28f, h * 0.70f)
            close()
        }
        drawPath(tail, wingColor)

        drawCircle(
            color = backgroundColor,
            radius = w * 0.025f,
            center = Offset(w * 0.73f, h * 0.34f)
        )
    }
}

private fun sightingMeta(bird: BirdBookEntry): String {
    val parts = mutableListOf<String>()
    if (bird.addedAt > 0L) {
        parts += SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(bird.addedAt))
    }
    if (bird.lat != null && bird.lon != null) {
        parts += String.format(Locale.US, "%.4f, %.4f", bird.lat, bird.lon)
    }
    return parts.joinToString(" • ").ifBlank { "Saved sighting" }
}

private fun loadBirdBook(filesDir: File): List<BirdBookEntry> {
    val file = File(filesDir, "bird_book.json")
    if (!file.exists()) return emptyList()

    return runCatching {
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: JSONObject()
                val info = obj.optJSONObject("info") ?: obj
                val name = obj.optString("name").ifBlank { info.optString("name", "Unknown bird") }
                val commonName = info.optString("commonName").ifBlank { info.optString("common_name").ifBlank { name } }
                val scientificName = info.optString("scientificName").ifBlank { info.optString("scientific_name") }
                val lat = if (obj.has("lat") && !obj.isNull("lat")) obj.optDouble("lat") else null
                val lon = if (obj.has("lon") && !obj.isNull("lon")) obj.optDouble("lon") else null
                val confidence = if (obj.has("confidence") && !obj.isNull("confidence")) obj.optInt("confidence") else null

                add(
                    BirdBookEntry(
                        name = name,
                        commonName = commonName,
                        scientificName = scientificName,
                        family = info.optString("family", ""),
                        habitat = info.optString("habitat", ""),
                        size = info.optString("size", ""),
                        diet = info.optString("diet", ""),
                        notes = info.optString("notes", ""),
                        addedAt = obj.optLong("addedAt", 0L),
                        lat = lat,
                        lon = lon,
                        confidencePercent = confidence
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
