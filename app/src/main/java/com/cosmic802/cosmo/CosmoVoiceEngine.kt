package com.cosmic802.cosmo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class CosmoVoiceEngine(context: Context) {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isInternalCancel = false
    private var restartRunnable: Runnable? = null

    private val cameraManager: CameraManager? =
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var rearFlashCameraId: String? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isActivelyRecognizing = MutableStateFlow(false)
    val isActivelyRecognizing: StateFlow<Boolean> = _isActivelyRecognizing.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _voiceStatus = MutableStateFlow("VOICE STANDBY")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    var onFreeformCommand: (suspend (String) -> Unit)? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == rearFlashCameraId) {
                _isTorchOn.value = enabled
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == rearFlashCameraId) {
                _isTorchOn.value = false
            }
        }
    }

    init {
        initFlashlightHardware()
        initTextToSpeech()
    }

    private fun initFlashlightHardware() {
        val manager = cameraManager ?: return
        try {
            var fallbackFlashId: String? = null
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    if (fallbackFlashId == null) {
                        fallbackFlashId = id
                    }
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        rearFlashCameraId = id
                        break
                    }
                }
            }
            if (rearFlashCameraId == null) {
                rearFlashCameraId = fallbackFlashId
            }
            if (rearFlashCameraId != null) {
                manager.registerTorchCallback(torchCallback, mainHandler)
            }
        } catch (_: Exception) {
            rearFlashCameraId = null
        }
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureJarvisVoice()
                _isTtsReady.value = true
            } else {
                _isTtsReady.value = false
                _voiceStatus.value = "TTS ENGINE ERROR"
            }
        }
    }

    private fun configureJarvisVoice() {
        val engine = textToSpeech ?: return
        val availableVoices = try {
            engine.voices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        val selectedVoice = selectRefinedMaleVoice(availableVoices)
        if (selectedVoice != null) {
            engine.voice = selectedVoice
        } else {
            val ukResult = engine.setLanguage(Locale.UK)
            if (ukResult == TextToSpeech.LANG_MISSING_DATA || ukResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
        }

        engine.setPitch(0.85f)
        engine.setSpeechRate(0.95f)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    _isSpeaking.value = true
                    _voiceStatus.value = "SPEAKING"
                    pauseRecognizerWhileSpeaking()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    _isSpeaking.value = false
                    if (_isListening.value) {
                        _voiceStatus.value = "LISTENING OFFLINE"
                        scheduleRecognitionRestart(350L)
                    } else {
                        _voiceStatus.value = "VOICE STANDBY"
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    _isSpeaking.value = false
                    if (_isListening.value) {
                        _voiceStatus.value = "LISTENING OFFLINE"
                        scheduleRecognitionRestart(400L)
                    } else {
                        _voiceStatus.value = "VOICE STANDBY"
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    _isSpeaking.value = false
                    if (_isListening.value) {
                        _voiceStatus.value = "LISTENING OFFLINE"
                        scheduleRecognitionRestart(400L)
                    } else {
                        _voiceStatus.value = "VOICE STANDBY"
                    }
                }
            }
        })
    }

    private fun selectRefinedMaleVoice(voices: List<Voice>): Voice? {
        if (voices.isEmpty()) return null

        val femaleHints = listOf(
            "female", "woman", "girl", "samantha", "victoria", "karen",
            "moira", "tessa", "fiona", "veena", "serena", "alice",
            "gba", "gbc", "sfg", "tpc", "tpf", "nna"
        )
        val maleHints = listOf(
            "male", "man", "daniel", "arthur", "oliver", "george",
            "james", "gbd", "rjs", "iom", "iob", "tpd", "fis"
        )

        fun scoreVoice(voice: Voice): Int {
            val lang = voice.locale.language.lowercase(Locale.ROOT)
            if (lang != "en") return -1000

            val country = voice.locale.country.uppercase(Locale.ROOT)
            val name = voice.name.lowercase(Locale.ROOT)
            val features = voice.features.joinToString(" ").lowercase(Locale.ROOT)
            val combined = "$name $features"

            var score = 0
            if (country == "GB") score += 40
            else if (country == "US") score += 25
            else if (country == "AU" || country == "CA") score += 15

            if (!voice.isNetworkConnectionRequired) score += 20
            if (voice.quality >= Voice.QUALITY_HIGH) score += 15

            if (maleHints.any { combined.contains(it) }) score += 50
            if (femaleHints.any { combined.contains(it) }) score -= 60

            return score
        }

        return voices
            .map { it to scoreVoice(it) }
            .filter { it.second > -500 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun speak(text: String, flushQueue: Boolean = true) {
        val sanitized = text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (sanitized.isBlank()) return

        mainHandler.post {
            val engine = textToSpeech ?: return@post
            if (!_isTtsReady.value) return@post

            pauseRecognizerWhileSpeaking()
            val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = "cosmo_${UUID.randomUUID()}"
            val params = Bundle()
            engine.speak(sanitized, queueMode, params, utteranceId)
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            textToSpeech?.stop()
            _isSpeaking.value = false
            if (_isListening.value) {
                _voiceStatus.value = "LISTENING OFFLINE"
                scheduleRecognitionRestart(300L)
            } else {
                _voiceStatus.value = "VOICE STANDBY"
            }
        }
    }

    fun toggleListening() {
        if (_isListening.value) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        mainHandler.post {
            val hasRecordPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasRecordPermission) {
                _isListening.value = false
                _isActivelyRecognizing.value = false
                _voiceStatus.value = "MIC PERMISSION REQUIRED"
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                _isListening.value = false
                _isActivelyRecognizing.value = false
                _voiceStatus.value = "OFFLINE STT UNAVAILABLE"
                return@post
            }

            _isListening.value = true
            _voiceStatus.value = "LISTENING OFFLINE"
            if (!_isSpeaking.value) {
                startRecognitionSession()
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            _isListening.value = false
            _isActivelyRecognizing.value = false
            cancelPendingRestart()
            destroyRecognizer()
            if (!_isSpeaking.value) {
                _voiceStatus.value = "VOICE STANDBY"
            }
        }
    }

    private fun pauseRecognizerWhileSpeaking() {
        cancelPendingRestart()
        _isActivelyRecognizing.value = false
        try {
            isInternalCancel = true
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun scheduleRecognitionRestart(delayMs: Long) {
        cancelPendingRestart()
        if (!_isListening.value || _isSpeaking.value) return

        val runnable = Runnable {
            if (_isListening.value && !_isSpeaking.value) {
                startRecognitionSession()
            }
        }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        val existing = speechRecognizer
        if (existing != null) return existing

        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isActivelyRecognizing.value = true
                if (!_isSpeaking.value) {
                    _voiceStatus.value = "LISTENING OFFLINE"
                }
            }

            override fun onBeginningOfSpeech() {
                _voiceStatus.value = "RECEIVING VOICE"
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _isActivelyRecognizing.value = false
                if (!_isSpeaking.value && _isListening.value) {
                    _voiceStatus.value = "PROCESSING AUDIO"
                }
            }

            override fun onError(error: Int) {
                _isActivelyRecognizing.value = false
                if (isInternalCancel) {
                    isInternalCancel = false
                    return
                }
                if (!_isListening.value || _isSpeaking.value) return

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        _voiceStatus.value = "LISTENING OFFLINE"
                        scheduleRecognitionRestart(250L)
                    }

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        destroyRecognizer()
                        scheduleRecognitionRestart(650L)
                    }

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        _isListening.value = false
                        _voiceStatus.value = "MIC PERMISSION REQUIRED"
                    }

                    else -> {
                        destroyRecognizer()
                        _voiceStatus.value = "RECALIBRATING MIC"
                        scheduleRecognitionRestart(850L)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                _isActivelyRecognizing.value = false
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()

                if (matches.isNotEmpty()) {
                    _lastTranscript.value = matches.first()
                    handleRecognizedHypotheses(matches)
                }

                if (_isListening.value && !_isSpeaking.value) {
                    _voiceStatus.value = "LISTENING OFFLINE"
                    scheduleRecognitionRestart(300L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    _lastTranscript.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        speechRecognizer = created
        return created
    }

    private fun startRecognitionSession() {
        try {
            isInternalCancel = false
            val recognizer = ensureRecognizer()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1200L
                )
            }
            recognizer.startListening(intent)
        } catch (_: Exception) {
            destroyRecognizer()
            if (_isListening.value && !_isSpeaking.value) {
                scheduleRecognitionRestart(800L)
            }
        }
    }

    private fun destroyRecognizer() {
        try {
            isInternalCancel = true
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
            _isActivelyRecognizing.value = false
        }
    }

    private fun handleRecognizedHypotheses(hypotheses: List<String>) {
        val normalized = hypotheses.map { raw ->
            raw.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }.filter { it.isNotBlank() }

        if (normalized.isEmpty()) return

        fun hasWakeWord(text: String): Boolean {
            return text.contains("cosmo") || text.contains("kosmo") || text.contains("cosmos")
        }

        // 1. Study Mode
        if (normalized.any { hasWakeWord(it) && it.contains("study") }) {
            _voiceStatus.value = "CMD: STUDY MODE"
            engineScope.launch {
                RootShell.activateStudyMode(appContext)
                speak("Engaging Study Mode, Sir. Distracting applications terminated and Do Not Disturb enabled.")
            }
            return
        }

        // 2. Sleep Mode
        if (normalized.any { hasWakeWord(it) && it.contains("sleep") }) {
            _voiceStatus.value = "CMD: SLEEP MODE"
            engineScope.launch {
                RootShell.activateSleepMode(appContext)
                speak("Engaging Sleep Mode, Sir. Crimson eye shield active, display dimmed, and your forty-minute wind-down timer has commenced.")
            }
            return
        }

        // 3. Game Mode
        if (normalized.any { hasWakeWord(it) && (it.contains("game") || it.contains("gaming")) }) {
            _voiceStatus.value = "CMD: GAME MODE"
            engineScope.launch {
                RootShell.activateGameMode(appContext)
                speak("Engaging Game Mode, Sir. Display locked at 120 hertz and background memory purged.")
            }
            return
        }

        // 4. Normal Mode
        if (normalized.any { hasWakeWord(it) && it.contains("normal") }) {
            _voiceStatus.value = "CMD: NORMAL MODE"
            engineScope.launch {
                RootShell.activateNormalMode(appContext)
                speak("Restoring Normal Mode, Sir. Adaptive refresh rate and standard display parameters active.")
            }
            return
        }

        // 5. Flashlight Toggle
        if (normalized.any {
                hasWakeWord(it) && (it.contains("flashlight") || it.contains("torch") || it.contains("light"))
            }
        ) {
            _voiceStatus.value = "CMD: FLASHLIGHT"
            val enabled = toggleFlashlight()
            if (enabled) {
                speak("Illumination engaged, Sir.")
            } else {
                speak("Flashlight deactivated, Sir.")
            }
            return
        }

        // 6. Wake Greeting ("hey cosmo")
        val wakeMatch = normalized.firstOrNull { hasWakeWord(it) } ?: return
        val strippedQuery = wakeMatch
            .replace(Regex("\\b(hey|hi|ok|okay|yo)?\\s*(cosmo|kosmo|cosmos)\\b"), "")
            .trim()

        if (strippedQuery.isBlank()) {
            _voiceStatus.value = "WAKE: HEY COSMO"
            speak("At your service, Sir. How may I assist?")
        } else {
            _voiceStatus.value = "QUERY: ${strippedQuery.uppercase(Locale.ROOT)}"
            val handler = onFreeformCommand
            if (handler != null) {
                engineScope.launch {
                    handler.invoke(strippedQuery)
                }
            } else {
                speak("Understood, Sir.")
            }
        }
    }

    fun toggleFlashlight(): Boolean {
        return setFlashlight(!_isTorchOn.value)
    }

    fun setFlashlight(enabled: Boolean): Boolean {
        val manager = cameraManager ?: return false
        val id = rearFlashCameraId ?: return false
        return try {
            manager.setTorchMode(id, enabled)
            _isTorchOn.value = enabled
            enabled
        } catch (_: Exception) {
            _isTorchOn.value = false
            false
        }
    }

    fun release() {
        mainHandler.post {
            _isListening.value = false
            cancelPendingRestart()
            destroyRecognizer()
            try {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
            } catch (_: Exception) {
            }
            textToSpeech = null
            try {
                cameraManager?.unregisterTorchCallback(torchCallback)
            } catch (_: Exception) {
            }
            engineScope.cancel()
        }
    }
}
