package com.cosmic802.cosmo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RedOverlayService : Service() {

    companion object {
        const val MIN_INTENSITY = 0.0f
        const val MAX_INTENSITY = 0.85f
        const val DEFAULT_INTENSITY = 0.45f

        const val ACTION_START = "com.cosmic802.cosmo.action.START_RED_OVERLAY"
        const val ACTION_STOP = "com.cosmic802.cosmo.action.STOP_RED_OVERLAY"
        const val EXTRA_INTENSITY = "extra_intensity"

        private const val CHANNEL_ID = "cosmo_crimson_shield_channel"
        private const val NOTIFICATION_ID = 8021

        private val _intensity = MutableStateFlow(DEFAULT_INTENSITY)
        val intensity: StateFlow<Float> = _intensity.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun setIntensity(value: Float) {
            _intensity.value = value.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var intensityJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.hasExtra(EXTRA_INTENSITY) == true) {
            val requested = intent.getFloatExtra(EXTRA_INTENSITY, _intensity.value)
            setIntensity(requested)
        } else if (_intensity.value <= 0.01f) {
            setIntensity(DEFAULT_INTENSITY)
        }

        if (!Settings.canDrawOverlays(this)) {
            _isRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        attachOverlayIfNeeded()
        observeIntensityUpdates()
        _isRunning.value = true

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        intensityJob?.cancel()
        intensityJob = null
        detachOverlay()
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun attachOverlayIfNeeded() {
        if (overlayView != null) {
            applyIntensityColor(_intensity.value)
            return
        }

        val wm = windowManager ?: return
        val filterView = View(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val layoutFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            layoutFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(filterView, params)
            overlayView = filterView
            applyIntensityColor(_intensity.value)
        } catch (_: Exception) {
            overlayView = null
            _isRunning.value = false
            stopSelf()
        }
    }

    private fun observeIntensityUpdates() {
        if (intensityJob?.isActive == true) return
        intensityJob = serviceScope.launch {
            intensity.collect { currentIntensity ->
                applyIntensityColor(currentIntensity)
            }
        }
    }

    private fun applyIntensityColor(currentIntensity: Float) {
        val clamped = currentIntensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        val alpha = (clamped * 255f).toInt().coerceIn(0, 217)
        val crimsonColor = Color.argb(alpha, 215, 0, 12)
        overlayView?.setBackgroundColor(crimsonColor)
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        } finally {
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cosmo Crimson Eye Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hardware-independent crimson ocular filter service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        val stopIntent = Intent(this, RedOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("COSMO // Crimson Eye Shield")
            .setContentText("Ocular blue-light suppression active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disable", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
