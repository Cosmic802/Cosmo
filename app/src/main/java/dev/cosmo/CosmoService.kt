package dev.cosmo

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.hardware.*
import android.net.*
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import kotlin.math.abs

private const val TICK = 4 * 60_000L                 // batched check interval (AlarmManager-driven, no wake-lock held)
private const val MODEL = "gemini-2.5-flash"         // swap for whatever is current
private val BRUSH = listOf(8 * 60, 22 * 60)          // brush-teeth windows (minutes since midnight)
private val NUDGES = listOf(
    "20+ minutes of scrolling. Put the phone down and move.",
    "Discipline is doing it when you don't feel like it. Close the app.",
    "Your future self is watching this scroll. Do the next habit instead."
)
private var fg: String? = null
private val mu = Mutex()

class CosmoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        getSystemService(NotificationManager::class.java).run {
            createNotificationChannel(NotificationChannel("core", "Cosmo core", NotificationManager.IMPORTANCE_MIN))
            createNotificationChannel(NotificationChannel("nudge", "Cosmo nudges", NotificationManager.IMPORTANCE_HIGH))
        }
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { s ->
            sm.registerListener(object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val raw = e.values[0]; val last = prefs.getFloat("lastRaw", raw)
                    prefs.edit().putFloat("lastRaw", raw).apply()
                    val d = (if (raw >= last) raw - last else raw).toInt()   // raw < last => reboot reset
                    if (d > 0) scope.launch { db(this@CosmoService).dao().run { ensure(DayLog(today())); addSteps(today(), d) } }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }, s, SensorManager.SENSOR_DELAY_NORMAL, 600_000_000)   // HW-batched up to 10 min = near-zero wakeups
        }
        // event-driven offline queue: flush the moment internet shows up
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                scope.launch { runCatching { sync(this@CosmoService) } }
            }
        })
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        ServiceCompat.startForeground(
            this, 1, Notification.Builder(this, "core").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("Cosmo online").build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        scope.launch { runCatching { tick(this@CosmoService) }; schedule() }
        return START_STICKY
    }

    private fun schedule() = getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + TICK,
        PendingIntent.getForegroundService(this, 0, Intent(this, CosmoService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    )

    override fun onDestroy() = scope.cancel()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { c.startForegroundService(Intent(c, CosmoService::class.java)) }
}

private suspend fun tick(c: Context) {
    val p = c.prefs; val dao = db(c).dao(); val now = System.currentTimeMillis()
    dao.ensure(DayLog(today()))
    ModeManager.tick(c)

    // Study focus/break minutes (elapsed-time based, so tick jitter doesn't matter)
    if (ModeManager.current(c) == Mode.STUDY) {
        var last = p.getLong("lastTick", now); var dt = ((now - last) / 60_000).toInt()
        if (dt > 10) { dt = 0; last = now }
        if (dt >= 1) { if (p.getBoolean("onBreak", false)) dao.addBreak(today(), dt) else dao.addFocus(today(), dt); last += dt * 60_000L }
        p.edit().putLong("lastTick", last).apply()
    }

    // Doomscroll + brush nudges
    fg = fgPkg(c, now) ?: fg
    val white = p.getStringSet("white", emptySet())!!
    val scrolling = c.getSystemService(PowerManager::class.java).isInteractive && fg?.let {
        it !in white && it != c.packageName &&
            runCatching { (c.packageManager.getApplicationInfo(it, 0).flags and ApplicationInfo.FLAG_SYSTEM) == 0 }.getOrDefault(false)
    } == true
    if (!scrolling) p.edit().putLong("scrollSince", 0).apply()
    else {
        val s = p.getLong("scrollSince", 0).takeIf { it > 0 } ?: now
        val over = now - s > 20 * 60_000
        p.edit().putLong("scrollSince", if (over) now else s).apply()
        if (over) nudge(c, "Doomscroll alert", NUDGES.random())
    }
    val min = LocalTime.now().let { it.hour * 60 + it.minute }
    val slot = BRUSH.indexOfFirst { abs(min - it) <= 10 }
    if (scrolling && slot >= 0 && p.getString("brush", "") != "${today()}-$slot") {
        nudge(c, "Brush time", "Phone down. Two minutes. Teeth.")
        p.edit().putString("brush", "${today()}-$slot").apply()
    }
    sync(c)
}

/** Last app resumed within the recent window (needs GET_USAGE_STATS, granted via root). */
private fun fgPkg(c: Context, now: Long): String? {
    val ev = UsageEvents.Event(); var last: String? = null
    val q = c.getSystemService(UsageStatsManager::class.java).queryEvents(now - 2 * TICK, now)
    while (q.hasNextEvent()) { q.getNextEvent(ev); if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = ev.packageName }
    return last
}

private fun nudge(c: Context, t: String, m: String) = c.getSystemService(NotificationManager::class.java).notify(
    t.hashCode(), Notification.Builder(c, "nudge").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(t).setContentText(m).setAutoCancel(true).build()
)

private fun online(c: Context) = c.getSystemService(ConnectivityManager::class.java).let { cm ->
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}

/** Refreshes user_context.json, then drains the offline queue through Gemini. Output is stored as text only, never executed. */
private suspend fun sync(c: Context) = mu.withLock {
    val dao = db(c).dao(); val d = dao.dayNow(today()) ?: DayLog(today())
    val ctx = JSONObject().put("date", d.date).put("water_ml", d.water).put("steps", d.steps).put("focus_min", d.focus)
        .put("habits_done", d.done).put("recent", JSONArray(dao.recent().map { it.text })).toString()
    File(c.filesDir, "user_context.json").writeText(ctx)
    val key = c.prefs.getString("gkey", "").orEmpty()
    if (key.isBlank() || !online(c)) return@withLock
    dao.pending().forEach { n ->
        withContext(Dispatchers.IO) {
            ask(key, "You are Cosmo, a terse personal butler. User context: $ctx\nItem: ${n.text}\n" +
                "If it is a task or event, restate it as one checklist line with a suggested time. " +
                "If it is a question, answer in under 80 words. Plain text only.")
        }?.let { dao.upd(n.copy(done = true, answer = it.trim())) }
    }
}

private fun ask(key: String, prompt: String): String? = runCatching {
    val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
    with(URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection) {
        requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", key)
        outputStream.use { it.write(body.toString().toByteArray()) }
        JSONObject(inputStream.bufferedReader().readText()).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }
}.getOrNull()
