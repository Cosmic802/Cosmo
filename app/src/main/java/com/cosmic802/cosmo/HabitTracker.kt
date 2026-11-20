package com.cosmic802.cosmo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DailyHabitState(
    val dateKey: String = HabitTracker.todayDateKey(),
    val waterMl: Int = 0,
    val waterGoalMl: Int = 2500,
    val steps: Int = 0,
    val stepsGoal: Int = 8000,
    val brushedTeeth: Boolean = false,
    val haircareDone: Boolean = false,
    val skincareDone: Boolean = false
) {
    val waterProgress: Float
        get() = (waterMl.toFloat() / waterGoalMl.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    val stepsProgress: Float
        get() = (steps.toFloat() / stepsGoal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    val looksmaxxingCompletedCount: Int
        get() = listOf(brushedTeeth, haircareDone, skincareDone).count { it }

    val looksmaxxingProgress: Float
        get() = looksmaxxingCompletedCount / 3f

    val overallProgress: Float
        get() {
            val total = waterProgress +
                stepsProgress +
                (if (brushedTeeth) 1f else 0f) +
                (if (haircareDone) 1f else 0f) +
                (if (skincareDone) 1f else 0f)
            return (total / 5f).coerceIn(0f, 1f)
        }

    val overallPercentage: Int
        get() = (overallProgress * 100f).toInt().coerceIn(0, 100)
}

object HabitTracker {

    private val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "wav", "ogg", "flac")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    private val _habitState = MutableStateFlow(DailyHabitState())
    val habitState: StateFlow<DailyHabitState> = _habitState.asStateFlow()

    private val _tracks = MutableStateFlow<List<File>>(emptyList())
    val tracks: StateFlow<List<File>> = _tracks.asStateFlow()

    private val _currentTrackIndex = MutableStateFlow(0)
    val currentTrackIndex: StateFlow<Int> = _currentTrackIndex.asStateFlow()

    private val _currentTrackTitle = MutableStateFlow("No Study Tracks Found")
    val currentTrackTitle: StateFlow<String> = _currentTrackTitle.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _trackProgress = MutableStateFlow(0f)
    val trackProgress: StateFlow<Float> = _trackProgress.asStateFlow()

    private val _audioStatus = MutableStateFlow("AUDIO STANDBY")
    val audioStatus: StateFlow<String> = _audioStatus.asStateFlow()

    fun todayDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun studyMusicDirectory(): File {
        val root = try {
            Environment.getExternalStorageDirectory()
        } catch (_: Exception) {
            File("/sdcard")
        }
        return File(root, "Music/CosmoStudy")
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        scope.launch {
            loadAndReconcileDailyHabits()
            scanStudyTracks()
        }
    }

    private suspend fun loadAndReconcileDailyHabits() {
        val today = todayDateKey()
        val stored = CosmoBrain.loadHabitsFromMemory()
        if (stored == null) {
            val fresh = DailyHabitState(dateKey = today)
            _habitState.value = fresh
            persistHabits(fresh)
            return
        }

        val storedDate = stored.optString("dateKey", today)
        val waterGoal = stored.optInt("waterGoalMl", 2500).coerceAtLeast(500)
        val stepsGoal = stored.optInt("stepsGoal", 8000).coerceAtLeast(1000)

        if (storedDate != today) {
            val resetState = DailyHabitState(
                dateKey = today,
                waterMl = 0,
                waterGoalMl = waterGoal,
                steps = 0,
                stepsGoal = stepsGoal,
                brushedTeeth = false,
                haircareDone = false,
                skincareDone = false
            )
            _habitState.value = resetState
            persistHabits(resetState)
            CosmoBrain.appendLog("HABITS", "Daily habits auto-reset for $today.")
        } else {
            val restored = DailyHabitState(
                dateKey = today,
                waterMl = stored.optInt("waterMl", 0).coerceAtLeast(0),
                waterGoalMl = waterGoal,
                steps = stored.optInt("steps", 0).coerceAtLeast(0),
                stepsGoal = stepsGoal,
                brushedTeeth = stored.optBoolean("brushedTeeth", false),
                haircareDone = stored.optBoolean("haircareDone", false),
                skincareDone = stored.optBoolean("skincareDone", false)
            )
            _habitState.value = restored
        }
    }

    private fun currentReconciledState(): DailyHabitState {
        val today = todayDateKey()
        val current = _habitState.value
        return if (current.dateKey != today) {
            DailyHabitState(
                dateKey = today,
                waterGoalMl = current.waterGoalMl,
                stepsGoal = current.stepsGoal
            )
        } else {
            current
        }
    }

    private fun updateHabitState(transform: (DailyHabitState) -> DailyHabitState) {
        val base = currentReconciledState()
        val updated = transform(base)
        _habitState.value = updated
        scope.launch {
            persistHabits(updated)
        }
    }

    private suspend fun persistHabits(state: DailyHabitState) {
        val json = JSONObject().apply {
            put("dateKey", state.dateKey)
            put("waterMl", state.waterMl)
            put("waterGoalMl", state.waterGoalMl)
            put("steps", state.steps)
            put("stepsGoal", state.stepsGoal)
            put("brushedTeeth", state.brushedTeeth)
            put("haircareDone", state.haircareDone)
            put("skincareDone", state.skincareDone)
            put("overallPercentage", state.overallPercentage)
        }
        CosmoBrain.syncHabitsToMemory(json)
    }

    fun addWater(deltaMl: Int = 250) {
        updateHabitState { state ->
            state.copy(waterMl = (state.waterMl + deltaMl).coerceAtLeast(0))
        }
    }

    fun setWater(totalMl: Int) {
        updateHabitState { state ->
            state.copy(waterMl = totalMl.coerceAtLeast(0))
        }
    }

    fun addSteps(deltaSteps: Int = 1000) {
        updateHabitState { state ->
            state.copy(steps = (state.steps + deltaSteps).coerceAtLeast(0))
        }
    }

    fun setSteps(totalSteps: Int) {
        updateHabitState { state ->
            state.copy(steps = totalSteps.coerceAtLeast(0))
        }
    }

    fun toggleBrushedTeeth() {
        updateHabitState { state ->
            state.copy(brushedTeeth = !state.brushedTeeth)
        }
    }

    fun toggleHaircare() {
        updateHabitState { state ->
            state.copy(haircareDone = !state.haircareDone)
        }
    }

    fun toggleSkincare() {
        updateHabitState { state ->
            state.copy(skincareDone = !state.skincareDone)
        }
    }

    fun resetTodayHabits() {
        updateHabitState { state ->
            DailyHabitState(
                dateKey = todayDateKey(),
                waterGoalMl = state.waterGoalMl,
                stepsGoal = state.stepsGoal
            )
        }
    }

    suspend fun scanStudyTracks(): List<File> = withContext(Dispatchers.IO) {
        val dir = studyMusicDirectory()
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
        } catch (_: Exception) {
        }

        val found = try {
            dir.listFiles()
                ?.filter { file ->
                    file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
                }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        withContext(Dispatchers.Main.immediate) {
            _tracks.value = found
            if (found.isEmpty()) {
                _currentTrackIndex.value = 0
                _currentTrackTitle.value = "Add MP3s to /sdcard/Music/CosmoStudy"
                _audioStatus.value = "NO TRACKS"
                _durationMs.value = 0
                _currentPositionMs.value = 0
                _trackProgress.value = 0f
            } else {
                val safeIndex = _currentTrackIndex.value.coerceIn(0, found.lastIndex)
                _currentTrackIndex.value = safeIndex
                _currentTrackTitle.value = found[safeIndex].nameWithoutExtension
                if (!_isPlaying.value && mediaPlayer == null) {
                    _audioStatus.value = "READY (${found.size} TRACKS)"
                }
            }
        }
        found
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            playOrResume()
        }
    }

    fun playOrResume() {
        scope.launch {
            var list = _tracks.value
            if (list.isEmpty()) {
                list = scanStudyTracks()
            }
            if (list.isEmpty()) {
                _audioStatus.value = "FOLDER EMPTY"
                return@launch
            }

            val player = mediaPlayer
            if (player != null) {
                try {
                    if (!player.isPlaying) {
                        player.start()
                        _isPlaying.value = true
                        _audioStatus.value = "PLAYING"
                        startProgressTicker()
                    }
                    return@launch
                } catch (_: Exception) {
                    releasePlayerInternal()
                }
            }

            val targetIndex = _currentTrackIndex.value.coerceIn(0, list.lastIndex)
            playTrackAtIndex(targetIndex)
        }
    }

    fun pause() {
        try {
            val player = mediaPlayer
            if (player != null && player.isPlaying) {
                player.pause()
                _isPlaying.value = false
                _audioStatus.value = "PAUSED"
                stopProgressTicker()
                updatePlaybackPosition()
            }
        } catch (_: Exception) {
            _isPlaying.value = false
            stopProgressTicker()
        }
    }

    fun nextTrack() {
        scope.launch {
            var list = _tracks.value
            if (list.isEmpty()) {
                list = scanStudyTracks()
            }
            if (list.isEmpty()) return@launch

            val nextIdx = (_currentTrackIndex.value + 1) % list.size
            playTrackAtIndex(nextIdx)
        }
    }

    fun previousTrack() {
        scope.launch {
            var list = _tracks.value
            if (list.isEmpty()) {
                list = scanStudyTracks()
            }
            if (list.isEmpty()) return@launch

            val currentPos = _currentPositionMs.value
            if (currentPos > 3000 && mediaPlayer != null) {
                try {
                    mediaPlayer?.seekTo(0)
                    updatePlaybackPosition()
                    return@launch
                } catch (_: Exception) {
                }
            }

            val prevIdx = if (_currentTrackIndex.value - 1 < 0) {
                list.lastIndex
            } else {
                _currentTrackIndex.value - 1
            }
            playTrackAtIndex(prevIdx)
        }
    }

    fun seekToFraction(fraction: Float) {
        val player = mediaPlayer ?: return
        val duration = _durationMs.value
        if (duration <= 0) return
        val targetMs = (fraction.coerceIn(0f, 1f) * duration).toInt()
        try {
            player.seekTo(targetMs)
            updatePlaybackPosition()
        } catch (_: Exception) {
        }
    }

    private fun playTrackAtIndex(index: Int) {
        val list = _tracks.value
        if (list.isEmpty()) return
        val safeIndex = index.coerceIn(0, list.lastIndex)
        val file = list[safeIndex]

        releasePlayerInternal()

        _currentTrackIndex.value = safeIndex
        _currentTrackTitle.value = file.nameWithoutExtension
        _currentPositionMs.value = 0
        _trackProgress.value = 0f

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    nextTrack()
                }
                setOnErrorListener { _, _, _ ->
                    _isPlaying.value = false
                    _audioStatus.value = "PLAYBACK ERROR"
                    stopProgressTicker()
                    true
                }
                prepare()
                start()
            }

            mediaPlayer = player
            _durationMs.value = player.duration.coerceAtLeast(0)
            _isPlaying.value = true
            _audioStatus.value = "PLAYING"
            startProgressTicker()
        } catch (_: Exception) {
            releasePlayerInternal()
            _audioStatus.value = "UNABLE TO PLAY"
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                updatePlaybackPosition()
                delay(250L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updatePlaybackPosition() {
        val player = mediaPlayer ?: return
        try {
            val pos = player.currentPosition.coerceAtLeast(0)
            val dur = player.duration.coerceAtLeast(0)
            _currentPositionMs.value = pos
            _durationMs.value = dur
            _trackProgress.value = if (dur > 0) {
                (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        } catch (_: Exception) {
        }
    }

    private fun releasePlayerInternal() {
        stopProgressTicker()
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _isPlaying.value = false
    }

    fun releaseAudio() {
        releasePlayerInternal()
        _audioStatus.value = "AUDIO STANDBY"
    }

    fun formatDuration(ms: Int): String {
        val totalSeconds = (ms.coerceAtLeast(0)) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
