package com.cosmic802.cosmo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var voiceEngine: CosmoVoiceEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        CosmoBrain.init(this)
        HabitTracker.init(this)
        voiceEngine = CosmoVoiceEngine(this)

        voiceEngine.onFreeformCommand = { spokenQuery ->
            val response = CosmoBrain.askCosmo(spokenQuery, includeTelemetry = true)
            voiceEngine.speak(response)
        }

        CosmoBrain.onQueueFlushed = { summary ->
            voiceEngine.speak(summary)
        }

        setContent {
            CosmoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CosmoPitchBlack
                ) {
                    CosmoHudDashboard(voiceEngine = voiceEngine)
                }
            }
        }
    }

    override fun onDestroy() {
        CosmoBrain.onQueueFlushed = null
        voiceEngine.release()
        if (isFinishing) {
            HabitTracker.releaseAudio()
        }
        super.onDestroy()
    }
}

@Composable
private fun CosmoHudDashboard(voiceEngine: CosmoVoiceEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    // System & Root state
    val currentMode by RootShell.currentMode.collectAsStateWithLifecycle()
    val isRootAvailable by RootShell.isRootAvailable.collectAsStateWithLifecycle()
    val sleepTimerRemaining by RootShell.sleepTimerRemainingSeconds.collectAsStateWithLifecycle()
    val isSleepTimerRunning by RootShell.isSleepTimerRunning.collectAsStateWithLifecycle()
    val lastExecutionLog by RootShell.lastExecutionLog.collectAsStateWithLifecycle()

    // Red Overlay state
    val overlayIntensity by RedOverlayService.intensity.collectAsStateWithLifecycle()
    val isOverlayRunning by RedOverlayService.isRunning.collectAsStateWithLifecycle()

    // Voice & Torch state
    val isListening by voiceEngine.isListening.collectAsStateWithLifecycle()
    val isSpeaking by voiceEngine.isSpeaking.collectAsStateWithLifecycle()
    val isTorchOn by voiceEngine.isTorchOn.collectAsStateWithLifecycle()
    val lastTranscript by voiceEngine.lastTranscript.collectAsStateWithLifecycle()
    val voiceStatus by voiceEngine.voiceStatus.collectAsStateWithLifecycle()

    // AI Brain state
    val apiKey by CosmoBrain.apiKey.collectAsStateWithLifecycle()
    val hasApiKey by CosmoBrain.hasApiKey.collectAsStateWithLifecycle()
    val isThinking by CosmoBrain.isThinking.collectAsStateWithLifecycle()
    val isOnline by CosmoBrain.isOnline.collectAsStateWithLifecycle()
    val queuedCount by CosmoBrain.queuedCount.collectAsStateWithLifecycle()
    val chatHistory by CosmoBrain.chatHistory.collectAsStateWithLifecycle()
    val latestBriefing by CosmoBrain.latestBriefing.collectAsStateWithLifecycle()
    val brainStatus by CosmoBrain.statusMessage.collectAsStateWithLifecycle()

    // Habits & Study Audio state
    val habitState by HabitTracker.habitState.collectAsStateWithLifecycle()
    val tracks by HabitTracker.tracks.collectAsStateWithLifecycle()
    val currentTrackIndex by HabitTracker.currentTrackIndex.collectAsStateWithLifecycle()
    val currentTrackTitle by HabitTracker.currentTrackTitle.collectAsStateWithLifecycle()
    val isAudioPlaying by HabitTracker.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by HabitTracker.currentPositionMs.collectAsStateWithLifecycle()
    val durationMs by HabitTracker.durationMs.collectAsStateWithLifecycle()
    val trackProgress by HabitTracker.trackProgress.collectAsStateWithLifecycle()
    val audioStatus by HabitTracker.audioStatus.collectAsStateWithLifecycle()

    // Local UI state
    var promptInput by rememberSaveable { mutableStateOf("") }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAllFilesAccess by remember { mutableStateOf(checkAllFilesPermission()) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMicPermission = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        scope.launch {
            HabitTracker.scanStudyTracks()
        }
    }

    val micStartLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            voiceEngine.startListening()
        } else {
            Toast.makeText(context, "Microphone permission required for voice commands.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        RootShell.checkRootAccess()
        val requiredPermissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            runtimePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
                hasAllFilesAccess = checkAllFilesPermission()
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                scope.launch {
                    HabitTracker.scanStudyTracks()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val activeModeColor = when (currentMode) {
        SystemMode.NORMAL -> CosmoCyan
        SystemMode.STUDY -> CosmoAmber
        SystemMode.GAME -> CosmoEmerald
        SystemMode.SLEEP -> CosmoCrimson
    }

    val coreSubLabel = when {
        isSleepTimerRunning && sleepTimerRemaining > 0L ->
            "LOCKDOWN ${RootShell.formatCountdown(sleepTimerRemaining)}"
        isThinking -> "NEURAL SYNTHESIS"
        isSpeaking -> "VOICE OUTPUT"
        isListening -> "MIC ACTIVE"
        else -> voiceStatus
    }

    fun handleModeSelection(targetMode: SystemMode) {
        if (targetMode == SystemMode.SLEEP && !Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Grant 'Display over other apps' for Crimson Eye Shield.",
                Toast.LENGTH_LONG
            ).show()
            openOverlaySettings(context)
        }
        scope.launch {
            RootShell.setMode(context, targetMode)
            CosmoBrain.recordModeChange(targetMode, "HUD")
        }
    }

    fun toggleRedOverlay(enable: Boolean) {
        if (enable) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(
                    context,
                    "Enable Display Over Other Apps permission first.",
                    Toast.LENGTH_LONG
                ).show()
                openOverlaySettings(context)
                return
            }
            val startIntent = Intent(context, RedOverlayService::class.java).apply {
                action = RedOverlayService.ACTION_START
                putExtra(
                    RedOverlayService.EXTRA_INTENSITY,
                    if (overlayIntensity <= 0.02f) RedOverlayService.DEFAULT_INTENSITY else overlayIntensity
                )
            }
            ContextCompat.startForegroundService(context, startIntent)
        } else {
            val stopIntent = Intent(context, RedOverlayService::class.java)
            context.stopService(stopIntent)
        }
    }

    fun submitDirectPrompt() {
        val text = promptInput.trim()
        if (text.isBlank() || isThinking) return
        promptInput = ""
        focusManager.clearFocus()
        scope.launch {
            val reply = CosmoBrain.askCosmo(text, includeTelemetry = true)
            voiceEngine.speak(reply)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CosmoPitchBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. TOP HUD TELEMETRY HEADER
        HudTopBar(
            isRootAvailable = isRootAvailable,
            isOnline = isOnline,
            queuedCount = queuedCount,
            hasApiKey = hasApiKey,
            isTorchOn = isTorchOn,
            onToggleTorch = { voiceEngine.toggleFlashlight() },
            onOpenSettings = { showSettingsDialog = true }
        )

        // PERMISSION WARNING STRIP (If Overlay or All-Files access is missing)
        if (!canDrawOverlays || !hasAllFilesAccess) {
            PermissionAlertBanner(
                canDrawOverlays = canDrawOverlays,
                hasAllFilesAccess = hasAllFilesAccess,
                onRequestOverlay = { openOverlaySettings(context) },
                onRequestAllFiles = { openAllFilesSettings(context) }
            )
        }

        // 2. ANIMATED J.A.R.V.I.S. CORE & VOICE CONTROL HUB
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = activeModeColor, cornerRadius = 16.dp)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            JarvisCoreVisualizer(
                coreColor = activeModeColor,
                accentColor = if (currentMode == SystemMode.STUDY) CosmoCyan else CosmoAmber,
                isListening = isListening,
                isSpeaking = isSpeaking,
                isProcessing = isThinking,
                statusLabel = currentMode.code,
                subLabel = coreSubLabel,
                size = 188.dp,
                modifier = Modifier.clickable {
                    if (isSpeaking) {
                        voiceEngine.stopSpeaking()
                    } else if (hasMicPermission) {
                        voiceEngine.toggleListening()
                    } else {
                        micStartLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (hasMicPermission) {
                            voiceEngine.toggleListening()
                        } else {
                            micStartLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening) CosmoCyan else CosmoSurfaceElevated,
                        contentColor = if (isListening) CosmoPitchBlack else CosmoCyan
                    ),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Voice Listener",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isListening) "VOICE: LISTENING" else "VOICE: STANDBY",
                        style = CosmoTypography.labelLarge
                    )
                }

                if (isSpeaking) {
                    OutlinedButton(
                        onClick = { voiceEngine.stopSpeaking() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CosmoCrimson),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Mute TTS",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "MUTE", style = CosmoTypography.labelLarge)
                    }
                }
            }

            if (lastTranscript.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CosmoPitchBlack.copy(alpha = 0.7f))
                        .border(1.dp, CosmoCyanDim.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "HEARD: \"$lastTranscript\"",
                        style = CosmoTypography.bodySmall,
                        color = CosmoTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 3. 4 ROOT SYSTEM MODES PANEL
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = activeModeColor, cornerRadius = 14.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = activeModeColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ROOT SYSTEM PROTOCOLS",
                        style = CosmoTypography.titleSmall,
                        color = CosmoTextPrimary
                    )
                }
                Text(
                    text = currentMode.displayName.uppercase(),
                    style = CosmoTypography.labelSmall,
                    color = activeModeColor
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeControlTile(
                    modifier = Modifier.weight(1f),
                    title = "NORMAL",
                    subtitle = "Adaptive 60-120Hz",
                    icon = Icons.Default.Shield,
                    accentColor = CosmoCyan,
                    isSelected = currentMode == SystemMode.NORMAL,
                    onClick = { handleModeSelection(SystemMode.NORMAL) }
                )
                ModeControlTile(
                    modifier = Modifier.weight(1f),
                    title = "STUDY",
                    subtitle = "DND + Kill Socials",
                    icon = Icons.Default.School,
                    accentColor = CosmoAmber,
                    isSelected = currentMode == SystemMode.STUDY,
                    onClick = { handleModeSelection(SystemMode.STUDY) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeControlTile(
                    modifier = Modifier.weight(1f),
                    title = "GAME",
                    subtitle = "120Hz + Purge RAM",
                    icon = Icons.Default.SportsEsports,
                    accentColor = CosmoEmerald,
                    isSelected = currentMode == SystemMode.GAME,
                    onClick = { handleModeSelection(SystemMode.GAME) }
                )
                ModeControlTile(
                    modifier = Modifier.weight(1f),
                    title = "SLEEP",
                    subtitle = "Red Shield + 40m",
                    icon = Icons.Default.NightsStay,
                    accentColor = CosmoCrimson,
                    isSelected = currentMode == SystemMode.SLEEP,
                    onClick = { handleModeSelection(SystemMode.SLEEP) }
                )
            }

            if (isSleepTimerRunning && sleepTimerRemaining > 0L) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CosmoCrimson.copy(alpha = 0.14f))
                        .border(1.dp, CosmoCrimson.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WIND-DOWN LOCKDOWN IN ${RootShell.formatCountdown(sleepTimerRemaining)}",
                        style = CosmoTypography.labelLarge,
                        color = CosmoCrimson
                    )
                    TextButton(
                        onClick = { RootShell.cancelSleepWindDownTimer() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "CANCEL",
                            style = CosmoTypography.labelSmall,
                            color = CosmoTextPrimary
                        )
                    }
                }
            }

            Text(
                text = "> $lastExecutionLog",
                style = CosmoTypography.bodySmall,
                color = CosmoTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 4. CRIMSON EYE SHIELD (RED OVERLAY SERVICE)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(
                    borderColor = if (isOverlayRunning) CosmoCrimson else CosmoCyanDim,
                    cornerRadius = 14.dp
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.RemoveRedEye,
                        contentDescription = null,
                        tint = if (isOverlayRunning) CosmoCrimson else CosmoTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "CRIMSON EYE SHIELD",
                            style = CosmoTypography.titleSmall,
                            color = CosmoTextPrimary
                        )
                        Text(
                            text = "Intensity: ${(overlayIntensity * 100).toInt()}% (Hardware-Independent)",
                            style = CosmoTypography.labelSmall,
                            color = if (isOverlayRunning) CosmoCrimson else CosmoTextSecondary
                        )
                    }
                }
                Switch(
                    checked = isOverlayRunning,
                    onCheckedChange = { enabled -> toggleRedOverlay(enabled) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CosmoPitchBlack,
                        checkedTrackColor = CosmoCrimson,
                        uncheckedThumbColor = CosmoTextSecondary,
                        uncheckedTrackColor = CosmoSurfaceElevated
                    )
                )
            }

            Slider(
                value = overlayIntensity,
                onValueChange = { value ->
                    RedOverlayService.setIntensity(value)
                },
                valueRange = RedOverlayService.MIN_INTENSITY..RedOverlayService.MAX_INTENSITY,
                colors = SliderDefaults.colors(
                    thumbColor = CosmoCrimson,
                    activeTrackColor = CosmoCrimson,
                    inactiveTrackColor = CosmoSurfaceElevated
                )
            )
        }

        // 5. LIFE-OS HABITS & LOOKSMAXXING TRACKER
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = CosmoCyan, cornerRadius = 14.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LIFE-OS & LOOKSMAXXING",
                        style = CosmoTypography.titleSmall,
                        color = CosmoTextPrimary
                    )
                    Text(
                        text = "DAILY COMPLETION: ${habitState.overallPercentage}% (${habitState.dateKey})",
                        style = CosmoTypography.labelSmall,
                        color = CosmoCyan
                    )
                }
                IconButton(
                    onClick = { HabitTracker.resetTodayHabits() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Daily Habits",
                        tint = CosmoTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { habitState.overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = CosmoCyan,
                trackColor = CosmoSurfaceElevated,
                strokeCap = StrokeCap.Round
            )

            // Hydration Row
            MetricProgressBlock(
                icon = Icons.Default.LocalDrink,
                label = "HYDRATION",
                valueText = "${habitState.waterMl} / ${habitState.waterGoalMl} ml",
                progress = habitState.waterProgress,
                accentColor = CosmoCyan,
                action1Label = "+250ml",
                onAction1 = { HabitTracker.addWater(250) },
                action2Label = "+500ml",
                onAction2 = { HabitTracker.addWater(500) }
            )

            // Steps Row
            MetricProgressBlock(
                icon = Icons.Default.DirectionsWalk,
                label = "DAILY STEPS",
                valueText = "${habitState.steps} / ${habitState.stepsGoal}",
                progress = habitState.stepsProgress,
                accentColor = CosmoEmerald,
                action1Label = "+1,000",
                onAction1 = { HabitTracker.addSteps(1000) },
                action2Label = "+2,500",
                onAction2 = { HabitTracker.addSteps(2500) }
            )

            HorizontalDivider(color = CosmoCyanDim.copy(alpha = 0.3f))

            Text(
                text = "GROOMING & CARE PROTOCOL (${habitState.looksmaxxingCompletedCount}/3)",
                style = CosmoTypography.labelSmall,
                color = CosmoTextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RoutineToggleChip(
                    modifier = Modifier.weight(1f),
                    label = "TEETH",
                    completed = habitState.brushedTeeth,
                    onClick = { HabitTracker.toggleBrushedTeeth() }
                )
                RoutineToggleChip(
                    modifier = Modifier.weight(1f),
                    label = "HAIRCARE",
                    completed = habitState.haircareDone,
                    onClick = { HabitTracker.toggleHaircare() }
                )
                RoutineToggleChip(
                    modifier = Modifier.weight(1f),
                    label = "SKINCARE",
                    completed = habitState.skincareDone,
                    onClick = { HabitTracker.toggleSkincare() }
                )
            }
        }

        // 6. STUDY AUDIO MINI-PLAYER (/sdcard/Music/CosmoStudy)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = CosmoAmber, cornerRadius = 14.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = CosmoAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STUDY AUDIO // $audioStatus",
                        style = CosmoTypography.titleSmall,
                        color = CosmoAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { scope.launch { HabitTracker.scanStudyTracks() } },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan Study Music",
                        tint = CosmoTextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Text(
                text = if (tracks.isNotEmpty()) {
                    "[${currentTrackIndex + 1}/${tracks.size}] $currentTrackTitle"
                } else {
                    currentTrackTitle
                },
                style = CosmoTypography.bodyMedium,
                color = CosmoTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Slider(
                value = trackProgress,
                onValueChange = { fraction -> HabitTracker.seekToFraction(fraction) },
                enabled = tracks.isNotEmpty() && durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = CosmoAmber,
                    activeTrackColor = CosmoAmber,
                    inactiveTrackColor = CosmoSurfaceElevated
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = HabitTracker.formatDuration(currentPositionMs),
                    style = CosmoTypography.labelSmall,
                    color = CosmoTextSecondary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { HabitTracker.previousTrack() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CosmoSurfaceElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = CosmoTextPrimary
                        )
                    }

                    IconButton(
                        onClick = { HabitTracker.togglePlayPause() },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(CosmoAmber)
                    ) {
                        Icon(
                            imageVector = if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play or Pause",
                            tint = CosmoPitchBlack,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = { HabitTracker.nextTrack() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CosmoSurfaceElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = CosmoTextPrimary
                        )
                    }
                }

                Text(
                    text = HabitTracker.formatDuration(durationMs),
                    style = CosmoTypography.labelSmall,
                    color = CosmoTextSecondary
                )
            }
        }

        // 7. J.A.R.V.I.S. AI BRIEFING & NEURAL CONSOLE
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hudPanel(borderColor = CosmoCyan, cornerRadius = 14.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "J.A.R.V.I.S. NEURAL CONSOLE",
                        style = CosmoTypography.titleSmall,
                        color = CosmoCyan
                    )
                    Text(
                        text = brainStatus,
                        style = CosmoTypography.labelSmall,
                        color = CosmoTextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val briefing = CosmoBrain.generateDailyBriefing()
                                voiceEngine.speak(briefing)
                            }
                        },
                        enabled = !isThinking,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CosmoCyan)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "BRIEFING", style = CosmoTypography.labelSmall)
                    }

                    IconButton(
                        onClick = { voiceEngine.speak(latestBriefing) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speak Latest Briefing",
                            tint = CosmoCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { scope.launch { CosmoBrain.clearChatHistory() } },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Chat Context",
                            tint = CosmoTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Active Briefing Readout
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CosmoPitchBlack.copy(alpha = 0.85f))
                    .border(1.dp, CosmoCyanDim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = latestBriefing,
                    style = CosmoTypography.bodyMedium,
                    color = CosmoTextPrimary
                )
            }

            // Recent Conversation Log
            if (chatHistory.isNotEmpty()) {
                val recentMessages = chatHistory.takeLast(6)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CosmoPitchBlack.copy(alpha = 0.55f))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recentMessages.forEach { msg ->
                        val isUser = msg.role == "user"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isUser) CosmoSurfaceElevated.copy(alpha = 0.7f)
                                    else CosmoCyan.copy(alpha = 0.08f)
                                )
                                .padding(8.dp)
                        ) {
                            Text(
                                text = when {
                                    isUser && msg.isQueued -> "SIR [QUEUED OFFLINE]"
                                    isUser -> "SIR"
                                    else -> "COSMO"
                                },
                                style = CosmoTypography.labelSmall,
                                color = when {
                                    msg.isQueued -> CosmoAmber
                                    isUser -> CosmoTextSecondary
                                    else -> CosmoCyan
                                }
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = msg.text,
                                style = CosmoTypography.bodySmall,
                                color = CosmoTextPrimary
                            )
                        }
                    }
                }
            }

            // Directive Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Issue directive to Cosmo...",
                            style = CosmoTypography.bodySmall,
                            color = CosmoTextMuted
                        )
                    },
                    singleLine = true,
                    textStyle = CosmoTypography.bodyMedium.copy(color = CosmoTextPrimary),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CosmoCyan,
                        unfocusedBorderColor = CosmoCyanDim,
                        focusedContainerColor = CosmoPitchBlack,
                        unfocusedContainerColor = CosmoPitchBlack,
                        cursorColor = CosmoCyan
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitDirectPrompt() })
                )

                IconButton(
                    onClick = { submitDirectPrompt() },
                    enabled = promptInput.isNotBlank() && !isThinking,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (promptInput.isNotBlank() && !isThinking) CosmoCyan
                            else CosmoSurfaceElevated
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Directive",
                        tint = if (promptInput.isNotBlank() && !isThinking) CosmoPitchBlack else CosmoTextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showSettingsDialog) {
        ApiKeySettingsDialog(
            currentKey = apiKey,
            isRootAvailable = isRootAvailable,
            canDrawOverlays = canDrawOverlays,
            hasAllFilesAccess = hasAllFilesAccess,
            onDismiss = { showSettingsDialog = false },
            onSaveKey = { newKey ->
                scope.launch {
                    val saved = CosmoBrain.saveApiKey(newKey)
                    Toast.makeText(
                        context,
                        if (saved) "API key saved to /sdcard/CosmoData/api_key.txt"
                        else "Warning: unable to persist API key",
                        Toast.LENGTH_SHORT
                    ).show()
                    showSettingsDialog = false
                }
            },
            onReverifyRoot = {
                scope.launch {
                    val ok = RootShell.checkRootAccess()
                    Toast.makeText(
                        context,
                        if (ok) "Root uid=0 verified." else "Root access denied.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onOpenOverlaySettings = { openOverlaySettings(context) },
            onOpenAllFilesSettings = { openAllFilesSettings(context) }
        )
    }
}

@Composable
private fun HudTopBar(
    isRootAvailable: Boolean,
    isOnline: Boolean,
    queuedCount: Int,
    hasApiKey: Boolean,
    isTorchOn: Boolean,
    onToggleTorch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = CosmoCyanDim, cornerRadius = 12.dp, showCornerBrackets = false)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "COSMO // HUD",
                style = CosmoTypography.titleMedium,
                color = CosmoCyan
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(
                    text = if (isRootAvailable) "ROOT:OK" else "NO ROOT",
                    color = if (isRootAvailable) CosmoEmerald else CosmoCrimson
                )
                StatusBadge(
                    text = if (isOnline) "NET:ONLINE" else "OFFLINE",
                    color = if (isOnline) CosmoCyan else CosmoAmber
                )
                if (queuedCount > 0) {
                    StatusBadge(
                        text = "Q:$queuedCount",
                        color = CosmoAmber
                    )
                }
                if (!hasApiKey) {
                    StatusBadge(
                        text = "NO KEY",
                        color = CosmoCrimson
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(
                onClick = onToggleTorch,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isTorchOn) CosmoAmber else CosmoSurfaceElevated)
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                    contentDescription = "Toggle Flashlight",
                    tint = if (isTorchOn) CosmoPitchBlack else CosmoTextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CosmoSurfaceElevated)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings and API Key",
                    tint = if (hasApiKey) CosmoCyan else CosmoAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = CosmoTypography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun PermissionAlertBanner(
    canDrawOverlays: Boolean,
    hasAllFilesAccess: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAllFiles: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hudPanel(borderColor = CosmoAmber, cornerRadius = 12.dp, showCornerBrackets = false)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "SYSTEM PERMISSIONS REQUIRED FOR FULL AUTONOMY",
            style = CosmoTypography.labelSmall,
            color = CosmoAmber
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!canDrawOverlays) {
                OutlinedButton(
                    onClick = onRequestOverlay,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CosmoAmber)
                ) {
                    Text(text = "GRANT OVERLAY", style = CosmoTypography.labelSmall)
                }
            }
            if (!hasAllFilesAccess) {
                OutlinedButton(
                    onClick = onRequestAllFiles,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CosmoAmber)
                ) {
                    Text(text = "GRANT ALL FILES", style = CosmoTypography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ModeControlTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) accentColor.copy(alpha = 0.18f) else CosmoSurfaceElevated
    val borderColor = if (isSelected) accentColor else CosmoCyanDim.copy(alpha = 0.45f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) accentColor else CosmoTextSecondary,
                modifier = Modifier.size(20.dp)
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }
        Text(
            text = title,
            style = CosmoTypography.labelLarge,
            color = if (isSelected) accentColor else CosmoTextPrimary
        )
        Text(
            text = subtitle,
            style = CosmoTypography.labelSmall,
            color = CosmoTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetricProgressBlock(
    icon: ImageVector,
    label: String,
    valueText: String,
    progress: Float,
    accentColor: Color,
    action1Label: String,
    onAction1: () -> Unit,
    action2Label: String,
    onAction2: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = CosmoTypography.labelLarge,
                    color = CosmoTextPrimary
                )
            }
            Text(
                text = valueText,
                style = CosmoTypography.bodySmall,
                color = accentColor
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape),
            color = accentColor,
            trackColor = CosmoSurfaceElevated,
            strokeCap = StrokeCap.Round
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onAction1,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
            ) {
                Text(text = action1Label, style = CosmoTypography.labelSmall)
            }
            OutlinedButton(
                onClick = onAction2,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
            ) {
                Text(text = action2Label, style = CosmoTypography.labelSmall)
            }
        }
    }
}

@Composable
private fun RoutineToggleChip(
    modifier: Modifier = Modifier,
    label: String,
    completed: Boolean,
    onClick: () -> Unit
) {
    val activeColor = if (completed) CosmoEmerald else CosmoTextSecondary
    val bgColor = if (completed) CosmoEmerald.copy(alpha = 0.16f) else CosmoSurfaceElevated
    val borderColor = if (completed) CosmoEmerald else CosmoCyanDim.copy(alpha = 0.45f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = label,
            tint = activeColor,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = CosmoTypography.labelSmall,
            color = activeColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ApiKeySettingsDialog(
    currentKey: String,
    isRootAvailable: Boolean,
    canDrawOverlays: Boolean,
    hasAllFilesAccess: Boolean,
    onDismiss: () -> Unit,
    onSaveKey: (String) -> Unit,
    onReverifyRoot: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAllFilesSettings: () -> Unit
) {
    var keyDraft by remember(currentKey) { mutableStateOf(currentKey) }
    var revealKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CosmoSurfaceDark,
        titleContentColor = CosmoCyan,
        textContentColor = CosmoTextPrimary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = null,
                    tint = CosmoCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "COSMO CORE CONFIGURATION",
                    style = CosmoTypography.titleMedium
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Gemini 2.5 Flash API Key (stored in /sdcard/CosmoData/api_key.txt):",
                    style = CosmoTypography.bodySmall,
                    color = CosmoTextSecondary
                )

                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (revealKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    textStyle = CosmoTypography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = CosmoTextPrimary
                    ),
                    placeholder = {
                        Text(
                            text = "AIzaSy...",
                            style = CosmoTypography.bodySmall,
                            color = CosmoTextMuted
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CosmoCyan,
                        unfocusedBorderColor = CosmoCyanDim,
                        focusedContainerColor = CosmoPitchBlack,
                        unfocusedContainerColor = CosmoPitchBlack,
                        cursorColor = CosmoCyan
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { revealKey = !revealKey }) {
                        Text(
                            text = if (revealKey) "HIDE KEY" else "REVEAL KEY",
                            style = CosmoTypography.labelSmall,
                            color = CosmoCyan
                        )
                    }
                    TextButton(onClick = { keyDraft = "" }) {
                        Text(
                            text = "CLEAR",
                            style = CosmoTypography.labelSmall,
                            color = CosmoCrimson
                        )
                    }
                }

                HorizontalDivider(color = CosmoCyanDim.copy(alpha = 0.35f))

                OutlinedButton(
                    onClick = onReverifyRoot,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isRootAvailable) "ROOT ACCESS: VERIFIED (RE-CHECK)" else "VERIFY ROOT ACCESS (su)",
                        style = CosmoTypography.labelSmall,
                        color = if (isRootAvailable) CosmoEmerald else CosmoAmber
                    )
                }

                if (!canDrawOverlays) {
                    OutlinedButton(
                        onClick = onOpenOverlaySettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "ENABLE DISPLAY OVER OTHER APPS",
                            style = CosmoTypography.labelSmall,
                            color = CosmoAmber
                        )
                    }
                }

                if (!hasAllFilesAccess) {
                    OutlinedButton(
                        onClick = onOpenAllFilesSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "ENABLE ALL FILES ACCESS",
                            style = CosmoTypography.labelSmall,
                            color = CosmoAmber
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveKey(keyDraft) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CosmoCyan,
                    contentColor = CosmoPitchBlack
                )
            ) {
                Text(text = "SAVE", style = CosmoTypography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", style = CosmoTypography.labelLarge, color = CosmoTextSecondary)
            }
        }
    )
}

private fun checkAllFilesPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun openOverlaySettings(context: android.content.Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun openAllFilesSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }
}
