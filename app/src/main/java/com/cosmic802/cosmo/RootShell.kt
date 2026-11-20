package com.cosmic802.cosmo

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

enum class SystemMode(val displayName: String, val code: String) {
    NORMAL("Normal Mode", "NORMAL"),
    STUDY("Study Mode", "STUDY"),
    GAME("Game Mode", "GAME"),
    SLEEP("Sleep Mode", "SLEEP")
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

object RootShell {

    const val SLEEP_WIND_DOWN_SECONDS = 40 * 60L

    val DISTRACTING_PACKAGES = listOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.google.android.youtube",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.twitter.android",
        "com.reddit.frontpage"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sleepTimerJob: Job? = null

    private val _currentMode = MutableStateFlow(SystemMode.NORMAL)
    val currentMode: StateFlow<SystemMode> = _currentMode.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _sleepTimerRemainingSeconds = MutableStateFlow(0L)
    val sleepTimerRemainingSeconds: StateFlow<Long> = _sleepTimerRemainingSeconds.asStateFlow()

    private val _isSleepTimerRunning = MutableStateFlow(false)
    val isSleepTimerRunning: StateFlow<Boolean> = _isSleepTimerRunning.asStateFlow()

    private val _lastExecutionLog = MutableStateFlow("Root subsystem initialized.")
    val lastExecutionLog: StateFlow<String> = _lastExecutionLog.asStateFlow()

    suspend fun checkRootAccess(): Boolean {
        val result = exec("id")
        val granted = result.isSuccess && result.stdout.contains("uid=0")
        _isRootAvailable.value = granted
        _lastExecutionLog.value = if (granted) {
            "Root access verified (uid=0)."
        } else {
            "Root check failed (exit=${result.exitCode}): ${result.stderr.ifBlank { "No root shell" }}"
        }
        return granted
    }

    suspend fun exec(command: String, timeoutMs: Long = 10_000L): ShellResult =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                process = ProcessBuilder("su", "-c", command).start()
                val activeProcess = process

                val stdoutDeferred = async {
                    BufferedReader(InputStreamReader(activeProcess.inputStream)).use { reader ->
                        reader.readText().trim()
                    }
                }
                val stderrDeferred = async {
                    BufferedReader(InputStreamReader(activeProcess.errorStream)).use { reader ->
                        reader.readText().trim()
                    }
                }

                val completedCode = withTimeoutOrNull(timeoutMs) {
                    activeProcess.waitFor()
                }

                if (completedCode == null) {
                    activeProcess.destroyForcibly()
                    val result = ShellResult(-1, "", "Command timed out after ${timeoutMs}ms: $command")
                    _lastExecutionLog.value = result.stderr
                    return@withContext result
                }

                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()
                val result = ShellResult(completedCode, stdout, stderr)
                if (!result.isSuccess && stderr.isNotBlank()) {
                    _lastExecutionLog.value = "CMD [$command] exit=$completedCode: $stderr"
                }
                result
            } catch (e: Exception) {
                process?.destroyForcibly()
                val msg = e.message ?: "Unknown root shell exception"
                _lastExecutionLog.value = "Root exception: $msg"
                ShellResult(-1, "", msg)
            }
        }

    suspend fun execBatch(commands: List<String>, timeoutMs: Long = 15_000L): ShellResult =
        withContext(Dispatchers.IO) {
            if (commands.isEmpty()) return@withContext ShellResult(0, "", "")
            var process: Process? = null
            try {
                process = ProcessBuilder("su").start()
                val activeProcess = process

                OutputStreamWriter(activeProcess.outputStream).use { writer ->
                    commands.forEach { cmd ->
                        writer.write(cmd)
                        writer.write("\n")
                    }
                    writer.write("exit\n")
                    writer.flush()
                }

                val stdoutDeferred = async {
                    BufferedReader(InputStreamReader(activeProcess.inputStream)).use { reader ->
                        reader.readText().trim()
                    }
                }
                val stderrDeferred = async {
                    BufferedReader(InputStreamReader(activeProcess.errorStream)).use { reader ->
                        reader.readText().trim()
                    }
                }

                val completedCode = withTimeoutOrNull(timeoutMs) {
                    activeProcess.waitFor()
                }

                if (completedCode == null) {
                    activeProcess.destroyForcibly()
                    val result = ShellResult(-1, "", "Batch root script timed out after ${timeoutMs}ms")
                    _lastExecutionLog.value = result.stderr
                    return@withContext result
                }

                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()
                ShellResult(completedCode, stdout, stderr)
            } catch (e: Exception) {
                process?.destroyForcibly()
                val msg = e.message ?: "Unknown root batch exception"
                _lastExecutionLog.value = "Root batch exception: $msg"
                ShellResult(-1, "", msg)
            }
        }

    suspend fun forceStopDistractingApps(): ShellResult {
        val commands = DISTRACTING_PACKAGES.map { pkg -> "am force-stop $pkg" }
        val result = execBatch(commands)
        _lastExecutionLog.value = "Purged ${DISTRACTING_PACKAGES.size} distracting packages (exit=${result.exitCode})."
        return result
    }

    suspend fun activateStudyMode(context: Context): ShellResult {
        cancelSleepWindDownTimer()
        stopRedOverlay(context)

        val commands = buildList {
            add("cmd notification set_dnd priority")
            DISTRACTING_PACKAGES.forEach { pkg ->
                add("am force-stop $pkg")
            }
        }
        val result = execBatch(commands)
        _currentMode.value = SystemMode.STUDY
        _lastExecutionLog.value = "Study Mode engaged: DND priority active & distracting apps terminated."
        return result
    }

    suspend fun activateGameMode(context: Context): ShellResult {
        cancelSleepWindDownTimer()
        stopRedOverlay(context)

        val commands = listOf(
            "settings put system peak_refresh_rate 120.0",
            "settings put system min_refresh_rate 120.0",
            "am kill-all",
            "cmd notification set_dnd priority"
        )
        val result = execBatch(commands)
        _currentMode.value = SystemMode.GAME
        _lastExecutionLog.value = "Game Mode engaged: 120Hz locked, background processes purged, DND active."
        return result
    }

    suspend fun activateSleepMode(context: Context): ShellResult {
        startRedOverlay(context)

        val commands = listOf(
            "settings put system screen_brightness_mode 0",
            "settings put system screen_brightness 15",
            "cmd notification set_dnd priority"
        )
        val result = execBatch(commands)
        _currentMode.value = SystemMode.SLEEP
        startSleepWindDownTimer()
        _lastExecutionLog.value = "Sleep Mode engaged: Crimson shield active, brightness dimmed, 40m wind-down started."
        return result
    }

    suspend fun activateNormalMode(context: Context): ShellResult {
        cancelSleepWindDownTimer()
        stopRedOverlay(context)

        val commands = listOf(
            "settings put system peak_refresh_rate 120.0",
            "settings put system min_refresh_rate 60.0",
            "settings put system screen_brightness_mode 1",
            "settings put system screen_brightness 128",
            "cmd notification set_dnd off"
        )
        val result = execBatch(commands)
        _currentMode.value = SystemMode.NORMAL
        _lastExecutionLog.value = "Normal Mode restored: Adaptive 60-120Hz, auto-brightness, DND off."
        return result
    }

    suspend fun setMode(context: Context, mode: SystemMode): ShellResult {
        return when (mode) {
            SystemMode.NORMAL -> activateNormalMode(context)
            SystemMode.STUDY -> activateStudyMode(context)
            SystemMode.GAME -> activateGameMode(context)
            SystemMode.SLEEP -> activateSleepMode(context)
        }
    }

    private fun startSleepWindDownTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingSeconds.value = SLEEP_WIND_DOWN_SECONDS
        _isSleepTimerRunning.value = true

        sleepTimerJob = scope.launch {
            var remaining = SLEEP_WIND_DOWN_SECONDS
            while (remaining > 0L) {
                delay(1_000L)
                remaining -= 1L
                _sleepTimerRemainingSeconds.value = remaining
            }

            _isSleepTimerRunning.value = false
            val lockdownCommands = buildList {
                DISTRACTING_PACKAGES.forEach { pkg ->
                    add("am force-stop $pkg")
                }
                add("cmd notification set_dnd total")
                add("input keyevent 223")
            }
            val result = execBatch(lockdownCommands)
            _lastExecutionLog.value = "Sleep wind-down complete: distracting apps terminated & screen locked (exit=${result.exitCode})."
        }
    }

    fun cancelSleepWindDownTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _isSleepTimerRunning.value = false
        _sleepTimerRemainingSeconds.value = 0L
    }

    private fun startRedOverlay(context: Context) {
        try {
            val intent = Intent(context, RedOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            _lastExecutionLog.value = "RedOverlayService start warning: ${e.message}"
        }
    }

    private fun stopRedOverlay(context: Context) {
        try {
            val intent = Intent(context, RedOverlayService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            _lastExecutionLog.value = "RedOverlayService stop warning: ${e.message}"
        }
    }

    fun formatCountdown(totalSeconds: Long): String {
        val clamped = totalSeconds.coerceAtLeast(0L)
        val minutes = clamped / 60L
        val seconds = clamped % 60L
        return String.format("%02d:%02d", minutes, seconds)
    }
}
