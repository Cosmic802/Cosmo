package dev.cosmo

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import android.content.Intent
import java.time.LocalTime

enum class Mode { NORMAL, STUDY, GAME, SLEEP }

/** Every shell string here is hardcoded; the only interpolated values are regex-validated package names. */
object ModeManager {
    private val PKG = Regex("[A-Za-z0-9._]+")
    private val SAFE = setOf("com.topjohnwu.magisk", "me.weishu.kernelsu")   // never suspend root managers
    private const val HZ = "settings put system peak_refresh_rate 120.0; settings put system min_refresh_rate 120.0"
    private const val HZ_RESET = "settings delete system peak_refresh_rate; settings delete system min_refresh_rate"
    private const val SAT = "service call SurfaceFlinger 1022 f"   // global saturation; ROM/device dependent

    fun current(c: Context) = Mode.valueOf(c.prefs.getString("mode", "NORMAL")!!)

    /** One-time root grants so the app never needs manual permission screens. */
    suspend fun grantAll(c: Context) {
        val p = c.packageName
        RootShell.exec(listOf(
            "pm grant $p android.permission.POST_NOTIFICATIONS", "pm grant $p android.permission.ACTIVITY_RECOGNITION",
            "appops set $p SYSTEM_ALERT_WINDOW allow", "appops set $p GET_USAGE_STATS allow",
            "appops set $p MANAGE_EXTERNAL_STORAGE allow", "cmd notification allow_dnd $p", "cmd deviceidle whitelist +$p"
        ).joinToString("; "))
    }

    suspend fun set(c: Context, m: Mode) {
        val p = c.prefs
        reset(c)
        when (m) {
            Mode.NORMAL -> {}
            Mode.STUDY -> { dnd(c, true); suspendApps(c, p.getStringSet("white", emptySet())!!) }
            Mode.GAME -> {
                dnd(c, true)
                RootShell.exec("sync; echo 3 > /proc/sys/vm/drop_caches; $HZ; $SAT 1.3")
                p.edit().putInt("nBase", notifCount()).putLong("lastPill", System.currentTimeMillis()).apply()
                p.getString("game", "com.mobile.legends")!!.takeIf { PKG.matches(it) }
                    ?.let { RootShell.exec("monkey -p $it -c android.intent.category.LAUNCHER 1") }
            }
            Mode.SLEEP -> dnd(c, true, starred = true)
        }
        p.edit().putString("mode", m.name).putLong("lastTick", System.currentTimeMillis()).putBoolean("wasLocked", false).apply()
        tick(c)
    }

    /** Called by the service every few minutes: schedule-driven overlays (Sleep) and pill alerts (Game). */
    suspend fun tick(c: Context) {
        val p = c.prefs; val now = System.currentTimeMillis()
        when (current(c)) {
            Mode.SLEEP -> {
                val t = LocalTime.now().let { it.hour * 60 + it.minute }
                val bed = p.getInt("bed", 23 * 60 + 30); val wake = p.getInt("wake", 7 * 60)
                val toBed = (bed - t + 1440) % 1440
                val night = (t - bed + 1440) % 1440 < (wake - bed + 1440) % 1440
                val lock = toBed <= 40 || night
                if (!lock && toBed > 120 && p.getBoolean("wasLocked", false)) { set(c, Mode.NORMAL); return }  // morning: auto-restore
                if (lock) p.edit().putBoolean("wasLocked", true).apply()
                overlay(c, if (lock) "lock" else if (toBed <= 120) "red" else "off")
            }
            Mode.GAME -> if (now - p.getLong("lastPill", 0) >= 7.5 * 60_000) {
                val n = notifCount(); val base = p.getInt("nBase", 0)
                if (n > base) overlay(c, "pill", "${n - base} new notification(s)")
                p.edit().putInt("nBase", n).putLong("lastPill", now).apply()
            }
            else -> {}
        }
    }

    fun overlay(c: Context, a: String, text: String = "") {
        if (a != "pill") {
            if (a == "off" && c.prefs.getString("ov", "off") == "off") return
            c.prefs.edit().putString("ov", a).apply()
        }
        c.startService(Intent(c, ScreenOverlayService::class.java).setAction(a).putExtra("t", text))
    }

    private suspend fun reset(c: Context) {
        RootShell.exec("$HZ_RESET; $SAT 1.0")
        dnd(c, false)
        val s = c.prefs.getStringSet("suspended", emptySet())!!.filter { PKG.matches(it) }
        if (s.isNotEmpty()) RootShell.exec(s.joinToString("; ") { "pm unsuspend --user 0 $it" })
        c.prefs.edit().remove("suspended").apply()
        overlay(c, "off")
    }

    private suspend fun dnd(c: Context, on: Boolean, starred: Boolean = false) {
        RootShell.exec("cmd notification allow_dnd ${c.packageName}")
        if (on) runCatching {   // policy API throws if DND access isn't granted yet; the cmd below still works
            val cat = Policy.PRIORITY_CATEGORY_ALARMS or (if (starred) Policy.PRIORITY_CATEGORY_CALLS or Policy.PRIORITY_CATEGORY_MESSAGES else 0)
            val who = if (starred) Policy.PRIORITY_SENDERS_STARRED else 0
            c.getSystemService(NotificationManager::class.java).notificationPolicy = Policy(cat, who, who)
        }
        RootShell.exec("cmd notification set_dnd ${if (on) "priority" else "off"}")
    }

    /** Suspends (reversible, data-safe) every user-installed app except whitelist, keyboard, launcher and root managers. */
    private suspend fun suspendApps(c: Context, keep: Set<String>) {
        val ime = RootShell.exec("settings get secure default_input_method").substringBefore('/').trim()
        val home = c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val targets = RootShell.exec("pm list packages -3").lines().map { it.removePrefix("package:").trim() }
            .filter { PKG.matches(it) && it !in keep && it !in SAFE && it != c.packageName && it != ime && it != home }
        if (targets.isEmpty()) return
        RootShell.exec(targets.joinToString("; ") { "pm suspend --user 0 $it" })
        c.prefs.edit().putStringSet("suspended", targets.toSet()).apply()
    }

    private suspend fun notifCount() =
        RootShell.exec("dumpsys notification | grep -c 'NotificationRecord('").trim().toIntOrNull() ?: 0
}
