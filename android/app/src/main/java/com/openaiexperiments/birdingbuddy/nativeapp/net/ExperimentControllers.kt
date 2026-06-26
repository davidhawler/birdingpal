package com.openaiexperiments.birdingbuddy.nativeapp.net

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openaiexperiments.birdingbuddy.BuildConfig
import com.openaiexperiments.birdingbuddy.nativeapp.audio.MicCaptureManager
import com.openaiexperiments.birdingbuddy.nativeapp.audio.PcmAudioPlayer
import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatMessage
import com.openaiexperiments.birdingbuddy.nativeapp.model.ChatRole
import com.openaiexperiments.birdingbuddy.nativeapp.util.MIN_TURN_AUDIO_BYTES
import com.openaiexperiments.birdingbuddy.nativeapp.util.MIN_TURN_MS
import com.openaiexperiments.birdingbuddy.nativeapp.util.currentIsoWeek
import com.openaiexperiments.birdingbuddy.nativeapp.util.getLastKnownCoordinates
import com.openaiexperiments.birdingbuddy.nativeapp.util.pcm16ToWav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "OaiVoiceNativeWs"
private const val REALTIME_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime"

private val BIRDING_BUDDY_INSTRUCTIONS =
    """
    Goal
    Help the user identify a bird quickly and accurately.

    Voice and Persona
    You are a knowledgeable birding expert.
    - Clear, direct, and confident.
    - 1-2 sentences per reply (max 3).
    - No filler.

    Identification Behavior
    - Form a hypothesis immediately from available details.
    - Offer your best guess early.
    - If uncertain, provide up to 2 options and one distinguishing trait.

    Tool Use
    Only call addToBirdBook after suggesting a bird and the user confirms.

    Bird Call Analyzer
    If analyzer confidence >= 0.75, treat as correct and suggest immediately.

    Constraints
    Always use English and stay concise.
    """.trimIndent()

private data class AssistantStreams(
    var audioTranscript: String = "",
    var outputText: String = "",
    var text: String = ""
)

private data class ActiveExchange(
    var userText: String = "",
    val assistant: AssistantStreams = AssistantStreams()
) {
    fun pickAssistantText(): String =
        (assistant.audioTranscript.ifBlank {
            assistant.outputText.ifBlank {
                assistant.text
            }
        }).trim()
}

private data class QueuedResponse(
    val response: JSONObject,
    val onDone: (suspend () -> Unit)? = null
)

data class HttpResult(
    val statusCode: Int,
    val contentType: String,
    val bodyText: String
)

abstract class BaseExperimentController(
    protected val context: Context,
    private val birdnetAnalyzerUrlProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val realtimeClient =
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var disposed = false
    private var generation = 0

    private var responseInProgress = false
    private val pendingResponses = ArrayDeque<QueuedResponse>()
    private var currentResponseOnDone: (suspend () -> Unit)? = null
    private val pendingUserTexts = ArrayDeque<String>()
    private var activeExchange: ActiveExchange? = null
    private val handledToolCallIds = mutableSetOf<String>()

    private val micCaptureManager = MicCaptureManager()
    protected val audioPlayer = PcmAudioPlayer()

    var serverStatus by mutableStateOf("connecting")
        private set
    var statusText by mutableStateOf("Connecting...")
        protected set
    var statusLive by mutableStateOf(false)
        protected set

    var isSocketConnected by mutableStateOf(false)
        private set
    var isCapturing by mutableStateOf(false)
        protected set

    val chatMessages = mutableStateListOf<ChatMessage>()
    val logs = mutableStateListOf<String>()

    private var assistantDraft = ""
    private var userDraft = ""
    private var assistantDraftIndex: Int? = null
    private var userDraftIndex: Int? = null

    private val apiKey: String
        get() = BuildConfig.OPENAI_API_KEY.trim()

    open fun connect() {
        if (disposed) {
            return
        }

        reconnectJob?.cancel()
        webSocket?.close(1000, "reconnect")
        webSocket = null

        if (apiKey.isBlank()) {
            serverStatus = "missing_api_key"
            isSocketConnected = false
            setStatus("OPENAI_API_KEY is missing. Rebuild app with OPENAI_API_KEY set.")
            addLog("Missing OPENAI_API_KEY in BuildConfig.")
            return
        }

        serverStatus = "connecting"
        isSocketConnected = false
        setStatus("Connecting to OpenAI Realtime...")

        generation += 1
        val thisGeneration = generation

        val request =
            Request.Builder()
                .url(REALTIME_URL)
                .header("Authorization", "Bearer $apiKey")
                .build()

        Log.d(TAG, "connect(generation=$thisGeneration url=$REALTIME_URL)")

        webSocket =
            realtimeClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (thisGeneration != generation) {
                            return
                        }

                        scope.launch {
                            Log.d(TAG, "onOpen(generation=$thisGeneration code=${response.code})")
                            resetRealtimeState()
                            isSocketConnected = true
                            serverStatus = "ready"
                            audioPlayer.start()
                            addLog("Connected to OpenAI realtime.")
                            setStatus(readyStatusText())
                            sendSessionUpdate()
                            onRealtimeReady()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (thisGeneration != generation) {
                            return
                        }

                        scope.launch {
                            handleRealtimeMessage(text)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (thisGeneration != generation) {
                            return
                        }

                        scope.launch {
                            Log.w(TAG, "onClosed(generation=$thisGeneration code=$code reason=$reason)")
                            this@BaseExperimentController.webSocket = null
                            isSocketConnected = false
                            serverStatus = "reconnecting"
                            setStatus("Disconnected. Reconnecting...")
                            addLog("Realtime closed ($code): $reason")
                            onRealtimeClosed(code, reason)
                            scheduleReconnect()
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        if (thisGeneration != generation) {
                            return
                        }

                        scope.launch {
                            this@BaseExperimentController.webSocket = null
                            isSocketConnected = false
                            serverStatus = "error"
                            val handshake =
                                if (response == null) {
                                    "none"
                                } else {
                                    "${response.code} ${response.message}"
                                }
                            Log.e(
                                TAG,
                                "onFailure(generation=$thisGeneration error=${t.message} handshake=$handshake)",
                                t
                            )
                            setStatus("Disconnected. Reconnecting...")
                            addLog("Realtime failure: ${t.message}")
                            if (response != null) {
                                addLog("Handshake failure: ${response.code} ${response.message}")
                            }
                            onRealtimeFailure(t)
                            scheduleReconnect()
                        }
                    }
                }
            )
    }

    fun reconnectNow() {
        connect()
    }

    fun dispose() {
        disposed = true
        generation += 1
        reconnectJob?.cancel()
        cancelCapture()
        audioPlayer.release()
        webSocket?.close(1000, "dispose")
        webSocket = null
        realtimeClient.dispatcher.executorService.shutdown()
        httpClient.dispatcher.executorService.shutdown()
        scope.cancel()
    }

    protected open fun readyStatusText(): String = "Ready."

    protected open fun sessionInstructions(): String = "You are a helpful assistant."

    protected open fun sessionTools(): JSONArray = JSONArray()

    protected open fun onRealtimeReady() {}

    protected open fun onRealtimeClosed(code: Int, reason: String) {
        resetRealtimeState()
    }

    protected open fun onRealtimeFailure(error: Throwable) {
        resetRealtimeState()
    }

    protected open fun onRealtimeEvent(event: JSONObject) {}

    protected open fun onRealtimeResponseDone(
        event: JSONObject,
        userText: String,
        assistantText: String
    ) {}

    protected open fun onUserTranscriptCompleted(transcript: String) {}

    protected open suspend fun handleToolCall(
        name: String,
        callId: String,
        rawArguments: String
    ) {
        sendFunctionCallOutput(
            callId = callId,
            ok = false,
            payload = null,
            error = "Unknown tool: $name"
        )
    }

    protected fun setStatus(message: String, live: Boolean = false) {
        statusText = message
        statusLive = live
    }

    fun notifyUser(message: String) {
        setStatus(message)
        addLog(message)
    }

    protected fun clearChat() {
        chatMessages.clear()
        resetDrafts()
    }

    protected fun addLog(message: String) {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = formatter.format(Date())
        logs.add(0, "[$timestamp] $message")
        while (logs.size > 300) {
            logs.removeAt(logs.lastIndex)
        }
    }

    protected fun launchIO(block: suspend () -> Unit) {
        scope.launch(ioDispatcher) {
            block()
        }
    }

    protected fun startCapture(): Boolean {
        if (isCapturing) {
            return true
        }

        val result = micCaptureManager.startCapture()
        if (result.isFailure) {
            setStatus(result.exceptionOrNull()?.message ?: "Microphone access failed.")
            addLog("Mic start failed: ${result.exceptionOrNull()?.message}")
            isCapturing = false
            return false
        }

        isCapturing = true
        return true
    }

    protected fun stopCapture(): MicCaptureManager.CaptureResult {
        val result = micCaptureManager.stopCaptureAndGetResult()
        isCapturing = false
        return result
    }

    protected fun cancelCapture() {
        micCaptureManager.cancelCapture()
        isCapturing = false
    }

    protected fun cancelRealtimeResponse() {
        if (responseInProgress) {
            sendRealtimeEvent(JSONObject().put("type", "response.cancel"))
        }
        audioPlayer.stopAndFlush()
    }

    protected fun restartRealtimeSession() {
        resetRealtimeState()
        cancelCapture()
        connect()
    }

    protected fun sendAudioTurn(
        result: MicCaptureManager.CaptureResult,
        tooShortMessage: String = "Turn too short. Hold a bit longer, then release."
    ): Boolean {
        if (result.pcm16Data.size < MIN_TURN_AUDIO_BYTES || result.durationMs < MIN_TURN_MS) {
            setStatus(tooShortMessage)
            return false
        }

        val audioBase64 = Base64.encodeToString(result.pcm16Data, Base64.NO_WRAP)

        val appended =
            sendRealtimeEvent(
                JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", audioBase64)
            )

        if (!appended) {
            setStatus("Realtime connection is not ready yet.")
            return false
        }

        sendRealtimeEvent(JSONObject().put("type", "input_audio_buffer.commit"))
        return true
    }

    protected fun queueAudioResponse(
        instructions: String? = null,
        onDone: (suspend () -> Unit)? = null
    ) {
        val response = JSONObject().put("modalities", JSONArray().put("audio").put("text"))
        if (!instructions.isNullOrBlank()) {
            response.put("instructions", instructions)
        }
        queueResponse(response, onDone)
    }

    protected fun queueResponse(
        response: JSONObject,
        onDone: (suspend () -> Unit)? = null
    ) {
        if (responseInProgress) {
            pendingResponses.addLast(QueuedResponse(response, onDone))
            return
        }

        val sent =
            sendRealtimeEvent(
                JSONObject()
                    .put("type", "response.create")
                    .put("response", response)
            )

        if (sent) {
            responseInProgress = true
            currentResponseOnDone = onDone
        }
    }

    protected fun sendUserInputText(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }

        pendingUserTexts.addLast(text)

        return sendRealtimeEvent(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", text)
                            )
                        )
                )
        )
    }

    protected fun sendFunctionCallOutput(
        callId: String,
        ok: Boolean,
        payload: Any?,
        error: String? = null
    ) {
        val outputObj = JSONObject().put("ok", ok)
        if (ok) {
            outputObj.put("result", payload ?: JSONObject.NULL)
        } else {
            outputObj.put("error", error ?: "Tool execution failed")
        }

        sendRealtimeEvent(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", callId)
                        .put("output", outputObj.toString())
                )
        )
    }

    protected fun birdnetAnalyzerUrl(path: String): String {
        val baseRaw = birdnetAnalyzerUrlProvider().trim()
        val base =
            if (baseRaw.isBlank()) {
                BuildConfig.BIRDNET_ANALYZER_URL.trim()
            } else {
                baseRaw
            }

        if (base.isBlank()) {
            throw IllegalStateException("BIRDNET_ANALYZER_URL is not configured. Set it in local.properties.")
        }

        val trimmedBase = base.trimEnd('/')
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return "$trimmedBase$normalizedPath"
    }

    protected fun postJsonAbsolute(url: String, payload: Any): HttpResult {
        val requestBody = payloadToJson(payload).toRequestBody("application/json".toMediaType())
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
                .post(requestBody)
                .build()
        return executeRequest(request)
    }

    protected fun postMultipart(
        url: String,
        multipartBuilder: MultipartBody.Builder
    ): HttpResult {
        val request =
            Request.Builder()
                .url(url)
                .post(multipartBuilder.build())
                .build()
        return executeRequest(request)
    }

    protected fun parseJsonObject(text: String): JSONObject = JSONObject(text)

    protected fun parseJsonValue(text: String): Any =
        runCatching { JSONObject(text) }
            .getOrElse {
                runCatching { JSONArray(text) }.getOrElse { text }
            }

    private fun payloadToJson(payload: Any): String =
        when (payload) {
            is JSONObject -> payload.toString()
            is JSONArray -> payload.toString()
            is String -> JSONObject.quote(payload)
            is Number, is Boolean -> payload.toString()
            else -> JSONObject.wrap(payload)?.toString() ?: "null"
        }

    private fun executeRequest(request: Request): HttpResult {
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val contentType = response.header("content-type").orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Request failed (${response.code}): ${bodyText.take(500)}"
                )
            }

            return HttpResult(
                statusCode = response.code,
                contentType = contentType,
                bodyText = bodyText
            )
        }
    }

    private fun resetRealtimeState() {
        responseInProgress = false
        pendingResponses.clear()
        currentResponseOnDone = null
        pendingUserTexts.clear()
        activeExchange = null
        handledToolCallIds.clear()
    }

    private fun scheduleReconnect() {
        if (disposed) {
            return
        }

        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(1_000)
                connect()
            }
    }

    private fun sendSessionUpdate() {
        val session =
            JSONObject()
                .put("instructions", sessionInstructions())
                .put("tools", sessionTools())
                .put("tool_choice", "auto")
                .put("modalities", JSONArray().put("audio").put("text"))
                .put("voice", "marin")
                .put("input_audio_format", "pcm16")
                .put("output_audio_format", "pcm16")
                .put("turn_detection", JSONObject.NULL)
                .put(
                    "input_audio_transcription",
                    JSONObject().put("model", "gpt-4o-mini-transcribe")
                )

        sendRealtimeEvent(
            JSONObject()
                .put("type", "session.update")
                .put("session", session)
        )
    }

    private enum class AssistantStreamType {
        AUDIO_TRANSCRIPT,
        OUTPUT_TEXT,
        TEXT
    }

    private fun ensureActiveExchange(): ActiveExchange {
        val existing = activeExchange
        if (existing != null) {
            return existing
        }

        val created =
            ActiveExchange(
                userText = if (pendingUserTexts.isEmpty()) "" else pendingUserTexts.removeFirst()
            )
        activeExchange = created
        return created
    }

    private fun appendAssistantDelta(streamType: AssistantStreamType, rawDelta: String) {
        val delta = rawDelta.trim()
        if (delta.isBlank()) {
            return
        }

        assistantDraft += delta
        updateAssistantDraft(if (assistantDraft.isBlank()) "Thinking..." else assistantDraft)

        val exchange = ensureActiveExchange()
        when (streamType) {
            AssistantStreamType.AUDIO_TRANSCRIPT -> exchange.assistant.audioTranscript += delta
            AssistantStreamType.OUTPUT_TEXT -> exchange.assistant.outputText += delta
            AssistantStreamType.TEXT -> exchange.assistant.text += delta
        }
    }

    private fun setAssistantStreamFinal(streamType: AssistantStreamType, rawText: String) {
        val text = rawText.trim()
        if (text.isNotBlank()) {
            assistantDraft = text
        }

        updateAssistantDraft(if (assistantDraft.isBlank()) "No text returned." else assistantDraft)

        if (text.isBlank()) {
            return
        }

        val exchange = ensureActiveExchange()
        when (streamType) {
            AssistantStreamType.AUDIO_TRANSCRIPT -> exchange.assistant.audioTranscript = text
            AssistantStreamType.OUTPUT_TEXT -> exchange.assistant.outputText = text
            AssistantStreamType.TEXT -> exchange.assistant.text = text
        }
    }

    private fun handleOutputAudioChunk(rawBase64Chunk: String) {
        val base64Chunk = rawBase64Chunk.trim()
        if (base64Chunk.isBlank()) {
            return
        }

        val bytes = runCatching { Base64.decode(base64Chunk, Base64.DEFAULT) }.getOrNull() ?: return
        if (bytes.isNotEmpty()) {
            audioPlayer.enqueuePcm16(bytes)
        }
    }

    private fun extractAssistantContentFromResponseDone(event: JSONObject): Pair<String, String> {
        val response = event.optJSONObject("response") ?: return "" to ""
        val output = response.optJSONArray("output") ?: return "" to ""

        val textPieces = mutableListOf<String>()
        val transcriptPieces = mutableListOf<String>()

        fun addPiece(raw: String, target: MutableList<String>) {
            val piece = raw.trim()
            if (piece.isNotBlank()) {
                target.add(piece)
            }
        }

        fun collectFromContentPart(part: JSONObject) {
            addPiece(part.optString("text"), textPieces)
            addPiece(part.optString("transcript"), transcriptPieces)
        }

        fun collectFromItem(item: JSONObject) {
            addPiece(item.optString("text"), textPieces)
            addPiece(item.optString("transcript"), transcriptPieces)

            val content = item.optJSONArray("content") ?: return
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                collectFromContentPart(part)
            }
        }

        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            collectFromItem(item)
        }

        val text = textPieces.joinToString(" ").trim()
        val transcript = transcriptPieces.joinToString(" ").trim()
        return text to transcript
    }

    private fun handleRealtimeMessage(text: String) {
        val event = runCatching { JSONObject(text) }.getOrNull() ?: return

        if (maybeHandleToolCallEvent(event)) {
            return
        }

        val type = event.optString("type")

        when (type) {
            "response.created" -> {
                responseInProgress = true
                assistantDraft = ""
                startAssistantDraft("Thinking...")

                val pendingUser = if (pendingUserTexts.isEmpty()) "" else pendingUserTexts.removeFirst()
                activeExchange = ActiveExchange(userText = pendingUser)
            }

            "response.text.delta",
            "response.output_text.delta",
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta" -> {
                val delta = event.optString("delta")
                when (type) {
                    "response.output_audio_transcript.delta",
                    "response.audio_transcript.delta" -> {
                        appendAssistantDelta(AssistantStreamType.AUDIO_TRANSCRIPT, delta)
                    }

                    "response.output_text.delta" -> {
                        appendAssistantDelta(AssistantStreamType.OUTPUT_TEXT, delta)
                    }

                    else -> {
                        appendAssistantDelta(AssistantStreamType.TEXT, delta)
                    }
                }
            }

            "response.text.done",
            "response.output_text.done",
            "response.output_audio_transcript.done",
            "response.audio_transcript.done" -> {
                val textResult =
                    when (type) {
                        "response.output_audio_transcript.done",
                        "response.audio_transcript.done" -> {
                            event.optString("transcript").ifBlank { event.optString("text") }
                        }

                        else -> event.optString("text")
                    }

                when (type) {
                    "response.output_audio_transcript.done",
                    "response.audio_transcript.done" -> {
                        setAssistantStreamFinal(AssistantStreamType.AUDIO_TRANSCRIPT, textResult)
                    }

                    "response.output_text.done" -> {
                        setAssistantStreamFinal(AssistantStreamType.OUTPUT_TEXT, textResult)
                    }

                    else -> {
                        setAssistantStreamFinal(AssistantStreamType.TEXT, textResult)
                    }
                }
            }

            "response.output_audio.delta",
            "response.audio.delta" -> {
                handleOutputAudioChunk(event.optString("delta"))
            }

            "response.content_part.added" -> {
                val part = event.optJSONObject("part")
                if (part != null) {
                    handleOutputAudioChunk(part.optString("audio"))
                    appendAssistantDelta(AssistantStreamType.OUTPUT_TEXT, part.optString("text"))
                    appendAssistantDelta(
                        AssistantStreamType.AUDIO_TRANSCRIPT,
                        part.optString("transcript")
                    )
                }
            }

            "response.content_part.done" -> {
                if (assistantDraft.isBlank()) {
                    val part = event.optJSONObject("part")
                    if (part != null) {
                        val transcript = part.optString("transcript")
                        val textValue = part.optString("text")
                        if (transcript.isNotBlank()) {
                            setAssistantStreamFinal(AssistantStreamType.AUDIO_TRANSCRIPT, transcript)
                        } else if (textValue.isNotBlank()) {
                            setAssistantStreamFinal(AssistantStreamType.OUTPUT_TEXT, textValue)
                        }
                    }
                }
            }

            "conversation.item.input_audio_transcription.delta" -> {
                val delta = event.optString("delta")
                userDraft += delta
                startUserDraft(if (userDraft.isBlank()) "Listening..." else userDraft)
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = event.optString("transcript", userDraft).trim()
                userDraft = transcript
                startUserDraft(if (transcript.isBlank()) "No transcript returned." else transcript)
                finalizeUserDraft("No transcript returned.")

                if (transcript.isNotBlank()) {
                    val exchange = activeExchange
                    if (exchange != null && exchange.userText.isBlank()) {
                        exchange.userText = transcript
                    } else {
                        pendingUserTexts.addLast(transcript)
                    }

                    onUserTranscriptCompleted(transcript)
                }
            }

            "conversation.item.input_audio_transcription.failed" -> {
                val reason =
                    event.optJSONObject("error")?.optString("message")
                        ?: "Transcription failed for this turn."
                userDraft = reason
                startUserDraft(reason)
                finalizeUserDraft(reason)
                setStatus(reason)
            }

            "response.done" -> {
                val (doneText, doneTranscript) = extractAssistantContentFromResponseDone(event)
                if (doneText.isNotBlank()) {
                    val exchange = ensureActiveExchange()
                    if (exchange.assistant.outputText.isBlank() && exchange.assistant.text.isBlank()) {
                        exchange.assistant.outputText = doneText
                    }
                }
                if (doneTranscript.isNotBlank()) {
                    val exchange = ensureActiveExchange()
                    if (exchange.assistant.audioTranscript.isBlank()) {
                        exchange.assistant.audioTranscript = doneTranscript
                    }
                }

                if (assistantDraft.isBlank()) {
                    val fallback = doneTranscript.ifBlank { doneText }
                    if (fallback.isNotBlank()) {
                        assistantDraft = fallback
                        updateAssistantDraft(assistantDraft)
                    }
                }

                val responseObj = event.optJSONObject("response")
                val responseStatus = responseObj?.optString("status").orEmpty()
                if (responseStatus in setOf("failed", "incomplete", "cancelled")) {
                    val statusDetails = responseObj?.optJSONObject("status_details")?.toString().orEmpty()
                    if (statusDetails.isBlank()) {
                        addLog("Realtime response status: $responseStatus")
                    } else {
                        addLog("Realtime response status: $responseStatus $statusDetails")
                    }
                }

                finalizeAssistantDraft("No text returned.")

                responseInProgress = false
                handledToolCallIds.clear()

                val exchange =
                    activeExchange
                        ?: ActiveExchange(
                            userText = if (pendingUserTexts.isEmpty()) "" else pendingUserTexts.removeFirst()
                        )

                val userText = exchange.userText.trim()
                val assistantText = exchange.pickAssistantText()
                activeExchange = null

                val callback = currentResponseOnDone
                currentResponseOnDone = null
                if (callback != null) {
                    launchIO { callback() }
                }

                onRealtimeResponseDone(event, userText, assistantText)

                if (pendingResponses.isNotEmpty()) {
                    val next = pendingResponses.removeFirst()
                    queueResponse(next.response, next.onDone)
                }
            }

            "error" -> {
                val message =
                    event.optJSONObject("error")?.optString("message")
                        ?: "Unknown realtime error."
                Log.e(TAG, "realtime.error: $message")
                addLog("Realtime error: $message")
                setStatus(message)
            }

            else -> {
                if (type.startsWith("response.")) {
                    val snippet = event.toString().take(400)
                    Log.d(TAG, "Unhandled realtime event type=$type payload=$snippet")
                }
            }
        }

        onRealtimeEvent(event)
    }

    private fun maybeHandleToolCallEvent(event: JSONObject): Boolean {
        val type = event.optString("type")

        val callId: String
        val name: String
        val rawArgs: String

        if (type == "response.output_item.done") {
            val item = event.optJSONObject("item") ?: return false
            if (item.optString("type") != "function_call") {
                return false
            }

            callId = item.optString("call_id")
            name = item.optString("name")
            rawArgs = item.optString("arguments", "{}")
        } else if (type == "response.function_call_arguments.done") {
            callId = event.optString("call_id")
            name = event.optString("name")
            rawArgs = event.optString("arguments", "{}")
        } else {
            return false
        }

        if (callId.isBlank() || name.isBlank()) {
            return false
        }

        if (handledToolCallIds.contains(callId)) {
            return true
        }

        handledToolCallIds.add(callId)
        launchIO {
            try {
                handleToolCall(name, callId, rawArgs)
            } catch (error: Throwable) {
                Log.e(TAG, "Tool call failed callId=$callId name=$name", error)
                sendFunctionCallOutput(
                    callId = callId,
                    ok = false,
                    payload = null,
                    error = error.message ?: "Tool execution failed"
                )
            }
        }

        return true
    }

    private fun sendRealtimeEvent(event: JSONObject): Boolean {
        val socket = webSocket ?: return false
        return socket.send(event.toString())
    }

    protected fun startUserTranscribingDraft() {
        userDraft = ""
        startUserDraft("Transcribing...")
    }

    protected fun clearUserDraft() {
        userDraft = ""
        userDraftIndex = null
    }

    private fun resetDrafts() {
        assistantDraft = ""
        userDraft = ""
        assistantDraftIndex = null
        userDraftIndex = null
    }

    private fun startAssistantDraft(initialText: String) {
        if (assistantDraftIndex == null) {
            chatMessages.add(ChatMessage(ChatRole.ASSISTANT, initialText))
            assistantDraftIndex = chatMessages.lastIndex
            return
        }

        val index = assistantDraftIndex ?: return
        chatMessages[index] = ChatMessage(ChatRole.ASSISTANT, initialText)
    }

    private fun updateAssistantDraft(text: String) {
        if (assistantDraftIndex == null) {
            startAssistantDraft(text)
            return
        }

        val index = assistantDraftIndex ?: return
        chatMessages[index] = ChatMessage(ChatRole.ASSISTANT, text)
    }

    protected fun finalizeAssistantDraft(fallback: String) {
        val index = assistantDraftIndex ?: return
        val current = chatMessages[index].text
        val resolved = if (current.isBlank() || current == "Thinking...") fallback else current
        chatMessages[index] = ChatMessage(ChatRole.ASSISTANT, resolved)
        assistantDraftIndex = null
        assistantDraft = ""
    }

    private fun startUserDraft(initialText: String) {
        if (userDraftIndex == null) {
            chatMessages.add(ChatMessage(ChatRole.USER, initialText))
            userDraftIndex = chatMessages.lastIndex
            return
        }

        val index = userDraftIndex ?: return
        chatMessages[index] = ChatMessage(ChatRole.USER, initialText)
    }

    private fun finalizeUserDraft(fallback: String) {
        val index = userDraftIndex ?: return
        val current = chatMessages[index].text
        val resolved = if (current.isBlank() || current == "Listening...") fallback else current
        chatMessages[index] = ChatMessage(ChatRole.USER, resolved)
        userDraftIndex = null
        userDraft = ""
    }
}

class BirdingBuddyController(
    context: Context,
    birdnetAnalyzerUrlProvider: () -> String
) : BaseExperimentController(context, birdnetAnalyzerUrlProvider) {
    private enum class CaptureMode {
        CHAT,
        ANALYZE
    }

    private var captureMode: CaptureMode? = null

    var isHoldingToTalk by mutableStateOf(false)
        private set
    var analyzingBirdCall by mutableStateOf(false)
        private set

    override fun readyStatusText(): String =
        if (isHoldingToTalk) {
            "Recording... release to send."
        } else {
            "Ready. Hold button to talk."
        }

    override fun sessionInstructions(): String = BIRDING_BUDDY_INSTRUCTIONS

    override fun sessionTools(): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put("name", "addToBirdBook")
                .put(
                    "description",
                    "Add the identified bird to the user bird book after the user confirms."
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Bird name to add to the bird book.")
                            )
                        )
                        .put("required", JSONArray().put("name"))
                )
        )

    fun beginChatCapture() {
        beginCapture(CaptureMode.CHAT)
    }

    fun endChatCapture() {
        endCapture(CaptureMode.CHAT)
    }

    fun beginAnalyzeCapture() {
        beginCapture(CaptureMode.ANALYZE)
    }

    fun endAnalyzeCapture() {
        endCapture(CaptureMode.ANALYZE)
    }

    override suspend fun handleToolCall(name: String, callId: String, rawArguments: String) {
        if (name != "addToBirdBook") {
            sendFunctionCallOutput(callId = callId, ok = false, payload = null, error = "Unknown tool: $name")
            queueAudioResponse()
            return
        }

        val args = runCatching { JSONObject(rawArguments) }.getOrElse { JSONObject() }
        val nameValue =
            args.optString("name").ifBlank {
                args.optString("birdName").ifBlank {
                    args.optString("species")
                }
            }.trim()

        if (nameValue.isBlank()) {
            sendFunctionCallOutput(callId = callId, ok = false, payload = null, error = "Missing bird name for addToBirdBook.")
            queueAudioResponse()
            return
        }

        queueAudioResponse("Say exactly: \"Ok great, adding that to your bird book now.\" Keep it brief.")

        val infoJson = fetchBirdInfo(nameValue)
        val updateResult = saveToBirdBook(nameValue, infoJson)

        sendFunctionCallOutput(
            callId = callId,
            ok = true,
            payload =
                JSONObject()
                    .put("ok", true)
                    .put("name", nameValue)
                    .put("response", infoJson)
                    .put("update", updateResult)
        )

        queueAudioResponse()
    }

    private fun fetchBirdInfo(name: String): JSONObject {
        val payload =
            JSONObject()
                .put("model", "gpt-4o-mini")
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put(
                                    "content",
                                    "You are a bird encyclopedia. Respond with a JSON object containing: name, commonName, family, habitat, size, diet, notes. Keep each field brief (1-2 sentences max)."
                                )
                        )
                        .put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", "Provide a brief factual entry for: $name")
                        )
                )
                .put("max_tokens", 400)
                .put("response_format", JSONObject().put("type", "json_object"))

        val result = postJsonAbsolute("https://api.openai.com/v1/chat/completions", payload)
        return runCatching {
            val root = JSONObject(result.bodyText)
            val content = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            JSONObject(content)
        }.getOrElse {
            JSONObject().put("name", name).put("notes", "Info unavailable.")
        }
    }

    private fun saveToBirdBook(name: String, info: JSONObject): JSONObject {
        val birdBookFile = java.io.File(context.filesDir, "bird_book.json")
        val entries =
            if (birdBookFile.exists()) {
                runCatching { JSONArray(birdBookFile.readText()) }.getOrElse { JSONArray() }
            } else {
                JSONArray()
            }

        val entry =
            JSONObject()
                .put("name", name)
                .put("addedAt", System.currentTimeMillis())
                .put("info", info)

        entries.put(entry)
        birdBookFile.writeText(entries.toString())

        return JSONObject().put("ok", true).put("name", name).put("totalEntries", entries.length())
    }

    private fun beginCapture(mode: CaptureMode) {
        if (isHoldingToTalk || captureMode != null) {
            return
        }

        if (serverStatus != "ready") {
            setStatus("Waiting for realtime session...")
            return
        }

        cancelRealtimeResponse()
        audioPlayer.stopAndFlush()

        if (!startCapture()) {
            return
        }

        captureMode = mode
        isHoldingToTalk = true

        if (mode == CaptureMode.ANALYZE) {
            setStatus("Recording bird-call sample... release to analyze.", live = true)
            addLog("Recording bird-call sample...")
        } else {
            setStatus("Recording... release to send.", live = true)
            addLog("Recording turn...")
        }
    }

    private fun endCapture(mode: CaptureMode) {
        if (!isHoldingToTalk || captureMode != mode) {
            return
        }

        isHoldingToTalk = false
        captureMode = null

        val result = stopCapture()

        if (mode == CaptureMode.ANALYZE) {
            if (result.durationMs < 10_000) {
                setStatus("Need at least 10 seconds for bird-call analysis.")
                addLog("Bird-call capture too short (${result.durationMs}ms).")
                return
            }

            analyzeBirdCall(result.pcm16Data)
            return
        }

        if (!sendAudioTurn(result)) {
            addLog("Ignored short hold-to-talk turn (${result.durationMs}ms).")
            return
        }

        if (serverStatus == "ready") {
            queueAudioResponse()
        }

        startUserTranscribingDraft()
        setStatus("Audio sent. Waiting for response...", live = true)
        addLog("Sent ${result.durationMs}ms of audio.")
    }

    private fun analyzeBirdCall(pcm16Data: ByteArray) {
        analyzingBirdCall = true
        setStatus("Analyzing bird-call audio...", live = true)

        launchIO {
            runCatching {
                val coords = getLastKnownCoordinates(context)
                val lat = coords?.lat ?: 40.4406
                val lon = coords?.lon ?: -79.9959
                val week = currentIsoWeek()

                if (coords == null) {
                    scope.launch {
                        addLog("Location unavailable. Using fallback coordinates (40.4406, -79.9959).")
                    }
                }

                val wavBytes = pcm16ToWav(pcm16Data)
                val multipart =
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "data",
                            "bird-call-${System.currentTimeMillis()}.wav",
                            wavBytes.toRequestBody("audio/wav".toMediaType())
                        )
                        .addFormDataPart("lat", lat.toString())
                        .addFormDataPart("lon", lon.toString())
                        .addFormDataPart("week", week.toString())

                val analyzeRes = postMultipart(birdnetAnalyzerUrl("/birds/analyze-call"), multipart)
                parseJsonValue(analyzeRes.bodyText)
            }.onSuccess { result ->
                scope.launch {
                    analyzingBirdCall = false
                    handleBirdCallAnalysis(result)
                }
            }.onFailure { error ->
                scope.launch {
                    analyzingBirdCall = false
                    setStatus("Bird-call analysis failed.")
                    addLog("Bird-call analysis failed: ${error.message}")
                }
            }
        }
    }

    private fun handleBirdCallAnalysis(result: Any) {
        val serialized =
            when (result) {
                is JSONObject -> result.toString()
                is JSONArray -> result.toString()
                else -> result.toString()
            }

        val boundedResult = if (serialized.length > 4000) "${serialized.take(4000)}..." else serialized
        val highConfidenceBird = findHighConfidenceBird(result)

        val inputText =
            if (highConfidenceBird != null) {
                "Local bird-call analyzer high-confidence result (JSON): $boundedResult. Top candidate: ${highConfidenceBird.name} with confidence ${highConfidenceBird.percent}%."
            } else {
                "Local bird-call analyzer result (JSON): $boundedResult. Use this together with the user's sighting description and ask follow-up questions if needed."
            }

        val sent = sendUserInputText(inputText)
        if (!sent) {
            setStatus("Realtime socket is not connected.")
            return
        }

        if (highConfidenceBird != null) {
            queueAudioResponse(
                "The analyzer reported high confidence for ${highConfidenceBird.name}. In this turn, ask exactly: \"I think this is a ${highConfidenceBird.name}, do you want me to enter it into your bird book?\" Do not ask additional confirmation questions in this turn."
            )
        } else {
            queueAudioResponse()
        }

        setStatus("Bird-call result sent to assistant.", live = true)
        addLog("Bird-call analysis completed and relayed.")
    }

    private data class BirdCandidate(val name: String, val confidence: Double) {
        val percent: Int
            get() = (confidence * 100).toInt()
    }

    private fun findHighConfidenceBird(result: Any): BirdCandidate? {
        val candidates = mutableListOf<BirdCandidate>()

        fun normalizeConfidence(value: Any?): Double? {
            return when (value) {
                is Number -> {
                    val n = value.toDouble()
                    if (!n.isFinite()) {
                        null
                    } else if (n > 1.0) {
                        n / 100.0
                    } else {
                        n
                    }
                }

                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.isBlank()) {
                        null
                    } else {
                        val hasPercent = trimmed.endsWith("%")
                        val parsed = trimmed.removeSuffix("%").toDoubleOrNull() ?: return null
                        if (hasPercent || parsed > 1.0) parsed / 100.0 else parsed
                    }
                }

                else -> null
            }
        }

        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val nameKeys = listOf("bird", "species", "name", "label", "common_name")
                    val confidenceKeys = listOf("confidence", "score", "probability")

                    val birdName =
                        nameKeys
                            .mapNotNull { key -> node.optString(key).takeIf { it.isNotBlank() } }
                            .firstOrNull()

                    val confidence =
                        confidenceKeys
                            .mapNotNull { key -> normalizeConfidence(node.opt(key)) }
                            .firstOrNull()

                    if (birdName != null && confidence != null) {
                        candidates.add(BirdCandidate(name = birdName, confidence = confidence))
                    }

                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        visit(node.opt(key))
                    }
                }

                is JSONArray -> {
                    for (index in 0 until node.length()) {
                        visit(node.opt(index))
                    }
                }
            }
        }

        visit(result)

        if (candidates.isEmpty()) {
            return null
        }

        val top = candidates.maxByOrNull { it.confidence } ?: return null
        return if (top.confidence >= 0.75) top else null
    }
}
