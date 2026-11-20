package dev.cosmo

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

private const val FILL = -1
private const val WRAP = -2

/** Actions: "red" (night tint, click-through), "lock" (opaque + Emergency button), "pill" (tiny alert), anything else = clear. */
class ScreenOverlayService : Service() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val ui = Handler(Looper.getMainLooper())
    private var main: View? = null
    private var pill: View? = null
    private var kind = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val a = i?.action.orEmpty()
        when (a) {
            "pill" -> pill(i?.getStringExtra("t").orEmpty())
            "red", "lock" -> if (a != kind) show(a)
            else -> { clear(); stopSelf() }
        }
        return START_NOT_STICKY   // ModeManager.tick re-sends the overlay if we get killed
    }

    override fun onDestroy() = clear()

    private fun lp(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, TYPE_APPLICATION_OVERLAY,
        flags or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
    )

    private fun show(k: String) {
        clear(); kind = k
        val lock = k == "lock"
        val root = FrameLayout(this).apply { setBackgroundColor(if (lock) Color.BLACK else 0x99B00000.toInt()) }
        if (lock) {
            root.addView(TextView(this).apply {
                text = "SLEEP LOCK\nRest. Your phone returns at wake time."
                setTextColor(0xFFFF1744.toInt()); textSize = 20f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FILL, FILL))
            root.addView(Button(this).apply { text = "EMERGENCY CALL"; setOnClickListener { dial(root) } },
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 220 })
        }
        wm.addView(root, lp(FILL, FILL, if (lock) 0 else FLAG_NOT_TOUCHABLE))
        main = root
    }

    /** Opens the dialer (112 default) above the lock, then re-arms the lock after 90s. */
    private fun dial(root: View) {
        root.visibility = View.GONE
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.postDelayed({ root.visibility = View.VISIBLE }, 90_000)
    }

    private fun pill(t: String) {
        pill?.let { runCatching { wm.removeView(it) } }
        val v = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 12f; setPadding(36, 14, 36, 14)
            background = GradientDrawable().apply { setColor(0xDD111111.toInt()); cornerRadius = 60f; setStroke(2, 0xFF00E5FF.toInt()) }
        }
        wm.addView(v, lp(WRAP, WRAP, FLAG_NOT_TOUCHABLE).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 90 })
        pill = v
        ui.postDelayed({
            if (pill === v) { runCatching { wm.removeView(v) }; pill = null }
            if (main == null && pill == null) stopSelf()
        }, 3500)
    }

    private fun clear() {
        listOf(main, pill).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        main = null; pill = null; kind = ""
    }
}
