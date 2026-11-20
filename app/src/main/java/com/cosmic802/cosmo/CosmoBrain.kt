package com.cosmic802.cosmo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long,
    val isQueued: Boolean = false
)

data class QueuedPrompt(
    val id: String,
    val prompt: String,
    val timestamp: Long,
    val systemSnapshot: String
)

object CosmoBrain {

    private const val GEMINI_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    private const val MAX_CHAT_HISTORY = 24
    private const val MAX_LOGS = 100
    private const val MAX_MODE_HISTORY = 50

    private const val JARVIS_SYSTEM_PROMPT = """
You are Cosmo, an ultra-refined, calm, proactive personal AI Butler and Life-OS inspired by J.A.R.V.I.S., engineered exclusively for the user's rooted Samsung Galaxy M23 5G.
Core Behavioral Directives:
1. Always address the user respectfully as "Sir" (never overuse it awkwardly; once or twice per response is ideal).
2. Maintain a composed, articulate, dry-witted British-butler cadence — concise, intelligent, and decisive.
3. Keep responses crisp and voice-friendly (2 to 4 short sentences unless Sir explicitly requests a detailed breakdown or plan).
4. Do not use markdown asterisks, hashtags, or heavy formatting because your replies are spoken aloud via text-to-speech as well as displayed on a HUD.
5. Leverage the provided live device telemetry, active system mode, and daily habit progress when giving advice, briefings, or recommendations.
""".trimIndent()

    private val brainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    private val requestMutex = Mutex()

    private var appContext: Context? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _hasApiKey = MutableStateFlow(false)
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory.asStateFlow()

    private val _latestBriefing = MutableStateFlow(
        "Systems online, Sir. Configure your Gemini API key in Settings or issue a voice directive."
    )
    val latestBriefing: StateFlow<String> = _latestBriefing.asStateFlow()

    private val _statusMessage = MutableStateFlow("BRAIN STANDBY")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    var onQueueFlushed: ((String) -> Unit)? = null

    private fun dataDir(): File {
        val extRoot = try {
            Environment.getExternalStorageDirectory()
        } catch (_: Exception) {
            File("/sdcard")
        }
        return File(extRoot, "CosmoData")
    }

    private fun memoryFile(): File = File(dataDir(), "memory.json")
    private fun apiKeyFile(): File = File(dataDir(), "api_key.txt")
    private fun queueFile(): File = File(dataDir(), "queue.json")

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        connectivityManager =
            appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        updateNetworkState()
        registerNetworkMonitor()

        brainScope.launch {
            ensureStorageDirectory()
            loadApiKey()
            val memory = readMemory()
            _chatHistory.value = parseChatHistory(memory)
            val queue = readQueueInternal()
            _queuedCount.value = queue.size

            if (_isOnline.value && queue.isNotEmpty() && _apiKey.value.isNotBlank()) {
                flushOfflineQueue()
            }
        }
    }

    private fun updateNetworkState(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: run {
            _isOnline.value = false
            return false
        }
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: run {
            _isOnline.value = false
            return false
        }
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _isOnline.value = online
        return online
    }

    private fun registerNetworkMonitor() {
        if (networkCallbackRegistered) return
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                    brainScope.launch {
                        if (_queuedCount.value > 0 && _apiKey.value.isNotBlank()) {
                            flushOfflineQueue()
                        }
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val validated = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    _isOnline.value = validated
                    if (validated) {
                        brainScope.launch {
                            if (_queuedCount.value > 0 && _apiKey.value.isNotBlank()) {
                                flushOfflineQueue()
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    updateNetworkState()
                }
            })
            networkCallbackRegistered = true
        } catch (_: Exception) {
            networkCallbackRegistered = false
        }
    }

    private suspend fun ensureStorageDirectory() {
        val dir = dataDir()
        val createdDirect = try {
            dir.exists() || dir.mkdirs()
        } catch (_: Exception) {
            false
        }
        if (!createdDirect || !dir.canWrite()) {
            RootShell.exec("mkdir -p '${dir.absolutePath}' && chmod 775 '${dir.absolutePath}'")
        }
    }

    private suspend fun readTextResilient(file: File): String? = withContext(Dispatchers.IO) {
        try {
            if (file.exists() && file.canRead()) {
                return@withContext file.readText(Charsets.UTF_8)
            }
        } catch (_: Exception) {
        }

        val checkResult = RootShell.exec("if [ -f '${file.absolutePath}' ]; then cat '${file.absolutePath}'; fi")
        if (checkResult.isSuccess && checkResult.stdout.isNotEmpty()) {
            return@withContext checkResult.stdout
        }
        null
    }

    private suspend fun writeTextResilient(file: File, content: String): Boolean =
        withContext(Dispatchers.IO) {
            ensureStorageDirectory()
            try {
                file.writeText(content, Charsets.UTF_8)
                if (file.exists()) {
                    return@withContext true
                }
            } catch (_: Exception) {
            }

            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cmd = "mkdir -p '${file.parent}' && echo '$encoded' | base64 -d > '${file.absolutePath}' && chmod 664 '${file.absolutePath}'"
            val rootResult = RootShell.exec(cmd)
            rootResult.isSuccess
        }

    suspend fun loadApiKey(): String = fileMutex.withLock {
        val key = readTextResilient(apiKeyFile())?.trim().orEmpty()
        _apiKey.value = key
        _hasApiKey.value = key.isNotBlank()
        key
    }

    suspend fun saveApiKey(rawKey: String): Boolean {
        val cleaned = rawKey.trim()
        val saved = fileMutex.withLock {
            writeTextResilient(apiKeyFile(), cleaned)
        }
        _apiKey.value = cleaned
        _hasApiKey.value = cleaned.isNotBlank()
        _statusMessage.value = if (cleaned.isNotBlank()) "API KEY STORED" else "API KEY CLEARED"
        appendLog("CONFIG", if (cleaned.isNotBlank()) "Gemini API key updated." else "Gemini API key cleared.")
        if (saved && cleaned.isNotBlank() && updateNetworkState() && _queuedCount.value > 0) {
            flushOfflineQueue()
        }
        return saved
    }

    private fun createDefaultMemoryJson(): JSONObject {
        return JSONObject().apply {
            put("habits", JSONObject())
            put("logs", JSONArray())
            put("modeHistory", JSONArray())
            put("chatContext", JSONArray())
        }
    }

    suspend fun readMemory(): JSONObject = fileMutex.withLock {
        readMemoryUnlocked()
    }

    private suspend fun readMemoryUnlocked(): JSONObject {
        val raw = readTextResilient(memoryFile())
        if (raw.isNullOrBlank()) {
            val initial = createDefaultMemoryJson()
            writeTextResilient(memoryFile(), initial.toString(2))
            return initial
        }
        return try {
            JSONObject(raw).apply {
                if (!has("habits")) put("habits", JSONObject())
                if (!has("logs")) put("logs", JSONArray())
                if (!has("modeHistory")) put("modeHistory", JSONArray())
                if (!has("chatContext")) put("chatContext", JSONArray())
            }
        } catch (_: Exception) {
            val fallback = createDefaultMemoryJson()
            writeTextResilient(memoryFile(), fallback.toString(2))
            fallback
        }
    }

    suspend fun writeMemory(memory: JSONObject): Boolean = fileMutex.withLock {
        writeTextResilient(memoryFile(), memory.toString(2))
    }

    suspend fun syncHabitsToMemory(habitsJson: JSONObject) {
        fileMutex.withLock {
            val memory = readMemoryUnlocked()
            memory.put("habits", habitsJson)
            writeTextResilient(memoryFile(), memory.toString(2))
        }
    }

    suspend fun loadHabitsFromMemory(): JSONObject? {
        return fileMutex.withLock {
            val memory = readMemoryUnlocked()
            val habits = memory.optJSONObject("habits")
            if (habits != null && habits.length() > 0) habits else null
        }
    }

    suspend fun appendLog(category: String, message: String) {
        fileMutex.withLock {
            val memory = readMemoryUnlocked()
            val logs = memory.optJSONArray("logs") ?: JSONArray()
            val entry = JSONObject().apply {
                put("timestamp", formatTimestamp(System.currentTimeMillis()))
                put("epochMs", System.currentTimeMillis())
                put("category", category)
                put("message", message)
            }
            logs.put(entry)
            memory.put("logs", trimJsonArray(logs, MAX_LOGS))
            writeTextResilient(memoryFile(), memory.toString(2))
        }
    }

    suspend fun recordModeChange(mode: SystemMode, source: String = "HUD") {
        fileMutex.withLock {
            val memory = readMemoryUnlocked()
            val history = memory.optJSONArray("modeHistory") ?: JSONArray()
            val entry = JSONObject().apply {
                put("timestamp", formatTimestamp(System.currentTimeMillis()))
                put("epochMs", System.currentTimeMillis())
                put("mode", mode.code)
                put("displayName", mode.displayName)
                put("source", source)
            }
            history.put(entry)
            memory.put("modeHistory", trimJsonArray(history, MAX_MODE_HISTORY))
            writeTextResilient(memoryFile(), memory.toString(2))
        }
        appendLog("MODE", "Switched to ${mode.displayName} via $source")
    }

    suspend fun clearChatHistory() {
        fileMutex.withLock {
            val memory = readMemoryUnlocked()
            memory.put("chatContext", JSONArray())
            writeTextResilient(memoryFile(), memory.toString(2))
        }
        _chatHistory.value = emptyList()
        _statusMessage.value = "MEMORY CONTEXT CLEARED"
    }

    suspend fun askCosmo(prompt: String, includeTelemetry: Boolean = true): String {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) {
            return "I am awaiting your instructions, Sir."
        }

        val currentKey = _apiKey.value.ifBlank { loadApiKey() }
        if (currentKey.isBlank()) {
            val reply = "My Gemini neural link requires an API key, Sir. Please enter it in the Settings panel."
            _latestBriefing.value = reply
            _statusMessage.value = "API KEY MISSING"
            return reply
        }

        val snapshot = if (includeTelemetry) buildLiveTelemetrySnapshot() else ""
        val onlineNow = updateNetworkState()

        if (!onlineNow) {
            enqueuePrompt(cleanPrompt, snapshot)
            val offlineMsg =
                "Network link is currently offline, Sir. I have queued your directive and will process it automatically upon reconnection."
            appendChatTurn(
                userText = cleanPrompt,
                modelText = offlineMsg,
                userQueued = true
            )
            _latestBriefing.value = offlineMsg
            _statusMessage.value = "QUEUED OFFLINE (${_queuedCount.value})"
            return offlineMsg
        }

        return requestMutex.withLock {
            _isThinking.value = true
            _statusMessage.value = "SYNTHESIZING RESPONSE"
            try {
                val recentHistory = _chatHistory.value.takeLast(12)
                val responseText = callGeminiFlashApi(
                    apiKey = currentKey,
                    userPrompt = cleanPrompt,
                    telemetrySnapshot = snapshot,
                    history = recentHistory
                )
                appendChatTurn(
                    userText = cleanPrompt,
                    modelText = responseText,
                    userQueued = false
                )
                _latestBriefing.value = responseText
                _statusMessage.value = "BRIEFING READY"
                appendLog("AI", "Prompt processed (${cleanPrompt.take(40)})")
                responseText
            } catch (e: NetworkUnavailableException) {
                enqueuePrompt(cleanPrompt, snapshot)
                val queuedReply =
                    "Connection dropped mid-transmission, Sir. Your prompt has been safely queued for automatic execution."
                appendChatTurn(
                    userText = cleanPrompt,
                    modelText = queuedReply,
                    userQueued = true
                )
                _latestBriefing.value = queuedReply
                _statusMessage.value = "QUEUED OFFLINE (${_queuedCount.value})"
                queuedReply
            } catch (e: Exception) {
                val errorReply =
                    "I encountered a telemetry fault reaching Gemini, Sir: ${e.message ?: "Unknown network error"}."
                _latestBriefing.value = errorReply
                _statusMessage.value = "NEURAL LINK ERROR"
                appendLog("ERROR", "Gemini request failed: ${e.message}")
                errorReply
            } finally {
                _isThinking.value = false
            }
        }
    }

    suspend fun generateDailyBriefing(): String {
        val briefingPrompt =
            "Provide a crisp J.A.R.V.I.S. tactical status briefing for Sir covering current time, active system mode, hydration, step count, grooming/skincare completion, and one actionable recommendation."
        return askCosmo(briefingPrompt, includeTelemetry = true)
    }

    suspend fun flushOfflineQueue(): Int {
        val currentKey = _apiKey.value.ifBlank { loadApiKey() }
        if (currentKey.isBlank() || !updateNetworkState()) return 0

        return requestMutex.withLock {
            val pending = fileMutex.withLock { readQueueInternal() }
            if (pending.isEmpty()) {
                _queuedCount.value = 0
                return@withLock 0
            }

            _isThinking.value = true
            _statusMessage.value = "FLUSHING QUEUE (${pending.size})"

            val remaining = mutableListOf<QueuedPrompt>()
            var processedCount = 0
            var lastResponse = ""

            for ((index, item) in pending.withIndex()) {
                if (!updateNetworkState()) {
                    remaining.addAll(pending.subList(index, pending.size))
                    break
                }
                try {
                    val reply = callGeminiFlashApi(
                        apiKey = currentKey,
                        userPrompt = item.prompt,
                        telemetrySnapshot = item.systemSnapshot,
                        history = _chatHistory.value.takeLast(10)
                    )
                    appendChatTurn(
                        userText = "[Queued] ${item.prompt}",
                        modelText = reply,
                        userQueued = false
                    )
                    lastResponse = reply
                    processedCount += 1
                } catch (_: NetworkUnavailableException) {
                    remaining.addAll(pending.subList(index, pending.size))
                    break
                } catch (e: Exception) {
                    appendLog("QUEUE_ERR", "Dropped failed queued prompt: ${e.message}")
                }
            }

            fileMutex.withLock {
                writeQueueInternal(remaining)
            }
            _queuedCount.value = remaining.size
            _isThinking.value = false

            if (processedCount > 0) {
                val summary = if (processedCount == 1) {
                    "Queued directive executed, Sir. $lastResponse"
                } else {
                    "Processed $processedCount queued directives upon reconnection, Sir. Latest response: $lastResponse"
                }
                _latestBriefing.value = summary
                _statusMessage.value = "QUEUE FLUSHED ($processedCount)"
                appendLog("QUEUE", "Flushed $processedCount offline prompt(s).")
                onQueueFlushed?.invoke(summary)
            }

            processedCount
        }
    }

    private suspend fun enqueuePrompt(prompt: String, snapshot: String) {
        fileMutex.withLock {
            val existing = readQueueInternal().toMutableList()
            existing.add(
                QueuedPrompt(
                    id = UUID.randomUUID().toString(),
                    prompt = prompt,
                    timestamp = System.currentTimeMillis(),
                    systemSnapshot = snapshot
                )
            )
            writeQueueInternal(existing)
            _queuedCount.value = existing.size
        }
        appendLog("QUEUE", "Queued offline prompt (${prompt.take(40)})")
    }

    private suspend fun readQueueInternal(): List<QueuedPrompt> {
        val raw = readTextResilient(queueFile())
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val prompt = obj.optString("prompt", "")
                    if (prompt.isNotBlank()) {
                        add(
                            QueuedPrompt(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                prompt = prompt,
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                systemSnapshot = obj.optString("systemSnapshot", "")
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun writeQueueInternal(items: List<QueuedPrompt>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("prompt", item.prompt)
                    put("timestamp", item.timestamp)
                    put("systemSnapshot", item.systemSnapshot)
                }
            )
        }
        writeTextResilient(queueFile(), arr.toString(2))
    }

    private suspend fun appendChatTurn(
        userText: String,
        modelText: String,
        userQueued: Boolean
    ) {
        val now = System.currentTimeMillis()
        val updatedList = fileMutex.withLock {
            val memory = readMemoryUnlocked()
            val chatArr = memory.optJSONArray("chatContext") ?: JSONArray()

            chatArr.put(
                JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("role", "user")
                    put("text", userText)
                    put("timestamp", now)
                    put("isQueued", userQueued)
                }
            )
            chatArr.put(
                JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("role", "model")
                    put("text", modelText)
                    put("timestamp", now + 1L)
                    put("isQueued", false)
                }
            )

            val trimmed = trimJsonArray(chatArr, MAX_CHAT_HISTORY)
            memory.put("chatContext", trimmed)
            writeTextResilient(memoryFile(), memory.toString(2))
            parseChatHistoryFromArray(trimmed)
        }
        _chatHistory.value = updatedList
    }

    private fun parseChatHistory(memory: JSONObject): List<ChatMessage> {
        val arr = memory.optJSONArray("chatContext") ?: return emptyList()
        return parseChatHistoryFromArray(arr)
    }

    private fun parseChatHistoryFromArray(arr: JSONArray): List<ChatMessage> {
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val text = obj.optString("text", "")
                if (text.isNotBlank()) {
                    add(
                        ChatMessage(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            role = obj.optString("role", "user"),
                            text = text,
                            timestamp = obj.optLong("timestamp", 0L),
                            isQueued = obj.optBoolean("isQueued", false)
                        )
                    )
                }
            }
        }
    }

    private suspend fun buildLiveTelemetrySnapshot(): String {
        val memory = readMemory()
        val habits = memory.optJSONObject("habits")
        val timeStr = SimpleDateFormat("EEEE, MMM d, yyyy HH:mm", Locale.US).format(Date())
        val mode = RootShell.currentMode.value.displayName
        val rootStatus = if (RootShell.isRootAvailable.value) "GRANTED" else "UNVERIFIED"
        val shieldStatus = if (RedOverlayService.isRunning.value) {
            "ACTIVE (${(RedOverlayService.intensity.value * 100).toInt()}%)"
        } else {
            "STANDBY"
        }

        val habitsSummary = if (habits != null && habits.length() > 0) {
            val water = habits.optInt("waterMl", 0)
            val waterGoal = habits.optInt("waterGoalMl", 2500)
            val steps = habits.optInt("steps", 0)
            val stepsGoal = habits.optInt("stepsGoal", 8000)
            val teeth = habits.optBoolean("brushedTeeth", false)
            val hair = habits.optBoolean("haircareDone", false)
            val skin = habits.optBoolean("skincareDone", false)
            "Water: ${water}/${waterGoal}ml | Steps: ${steps}/${stepsGoal} | Teeth: $teeth | Haircare: $hair | Skincare: $skin"
        } else {
            "No habit metrics logged yet today."
        }

        return "[LIVE SYSTEM TELEMETRY: Time=$timeStr | ActiveMode=$mode | Root=$rootStatus | CrimsonShield=$shieldStatus | Habits=($habitsSummary)]"
    }

    private suspend fun callGeminiFlashApi(
        apiKey: String,
        userPrompt: String,
        telemetrySnapshot: String,
        history: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val endpoint = URL("$GEMINI_BASE_URL?key=$encodedKey")

        val contentsArray = JSONArray()

        for (msg in history) {
            if (msg.isQueued) continue
            val role = if (msg.role == "model") "model" else "user"
            contentsArray.put(
                JSONObject().apply {
                    put("role", role)
                    put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", msg.text))
                    )
                }
            )
        }

        val finalUserText = if (telemetrySnapshot.isNotBlank()) {
            "$telemetrySnapshot\n\nSir's Directive: $userPrompt"
        } else {
            userPrompt
        }

        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", finalUserText))
                )
            }
        )

        val requestBody = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", JARVIS_SYSTEM_PROMPT))
                )
            )
            put("contents", contentsArray)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.7)
                    put("topP", 0.9)
                    put("maxOutputTokens", 512)
                }
            )
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val rawResponse = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()

            if (statusCode !in 200..299) {
                val apiErrMsg = try {
                    JSONObject(rawResponse).optJSONObject("error")?.optString("message")
                } catch (_: Exception) {
                    null
                }
                throw IllegalStateException(
                    apiErrMsg ?: "HTTP $statusCode from Gemini API"
                )
            }

            val json = JSONObject(rawResponse)
            val candidates = json.optJSONArray("candidates")
                ?: throw IllegalStateException("Empty candidates returned by Gemini")
            if (candidates.length() == 0) {
                throw IllegalStateException("No response candidate available")
            }

            val content = candidates.getJSONObject(0).optJSONObject("content")
                ?: throw IllegalStateException("Missing content block in response")
            val parts = content.optJSONArray("parts")
                ?: throw IllegalStateException("Missing content parts in response")

            val replyBuilder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) {
                    if (replyBuilder.isNotEmpty()) replyBuilder.append(" ")
                    replyBuilder.append(text.trim())
                }
            }

            val finalReply = replyBuilder.toString().trim()
            if (finalReply.isBlank()) {
                throw IllegalStateException("Blank text returned by Gemini")
            }
            finalReply
        } catch (e: java.net.UnknownHostException) {
            throw NetworkUnavailableException(e.message ?: "Host unreachable")
        } catch (e: java.net.SocketTimeoutException) {
            throw NetworkUnavailableException(e.message ?: "Connection timed out")
        } catch (e: java.net.ConnectException) {
            throw NetworkUnavailableException(e.message ?: "Connection refused")
        } finally {
            connection?.disconnect()
        }
    }

    private fun trimJsonArray(source: JSONArray, maxItems: Int): JSONArray {
        if (source.length() <= maxItems) return source
        val trimmed = JSONArray()
        val startIdx = source.length() - maxItems
        for (i in startIdx until source.length()) {
            trimmed.put(source.get(i))
        }
        return trimmed
    }

    private fun formatTimestamp(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
    }

    private class NetworkUnavailableException(message: String) : Exception(message)
}
