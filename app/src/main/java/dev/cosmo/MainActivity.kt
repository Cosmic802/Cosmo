package dev.cosmo

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

val Cyan = Color(0xFF00E5FF); val Crimson = Color(0xFFFF1744); val Panel = Color(0xFF0B0B10); val Dim = Color(0xFF8A8A99)
val HABITS = listOf("Skincare AM", "Skincare PM", "Hair care", "Brush teeth AM", "Brush teeth PM", "Looksmax routine", "Workout")

fun doneSet(d: DayLog) = d.done.split(",").filter { it in HABITS }.toSet()
fun score(d: DayLog) = (40f * doneSet(d).size / HABITS.size + 20f * minOf(1f, d.water / 3000f) +
    20f * minOf(1f, d.steps / 8000f) + 20f * minOf(1f, d.focus / 120f)).toInt()

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge(); handle(intent)
        setContent {
            MaterialTheme(darkColorScheme(primary = Cyan, secondary = Crimson, background = Color.Black, surface = Panel)) {
                Surface(color = Color.Black, contentColor = Color.White) { App() }
            }
        }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    /** Voice/Tasker/root hook: am start -n dev.cosmo/.MainActivity --es mode GAME */
    private fun handle(i: Intent) {
        val m = i.getStringExtra("mode")?.let { runCatching { Mode.valueOf(it.uppercase()) }.getOrNull() } ?: return
        lifecycleScope.launch { ModeManager.set(this@MainActivity, m) }
    }
}

@Composable fun App() {
    val c = LocalContext.current; val date = remember { today() }; val dao = remember { db(c).dao() }
    val day by remember { dao.day(date) }.collectAsState(null)
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        dao.ensure(DayLog(date)); ModeManager.grantAll(c)
        c.startForegroundService(Intent(c, CosmoService::class.java)); Mp3.load()
    }
    val d = day ?: DayLog(date)
    Scaffold(containerColor = Color.Black, bottomBar = {
        NavigationBar(containerColor = Panel) {
            listOf("HUD", "Habits", "Study", "Queue").forEachIndexed { i, t ->
                NavigationBarItem(tab == i, { tab = i }, { Text("●") }, label = { Text(t) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), Arrangement.spacedBy(10.dp)) {
            when (tab) { 0 -> Hud(d); 1 -> Habits(d, dao); 2 -> Study(d); else -> Queue(dao) }
        }
    }
}

@Composable fun Hud(d: DayLog) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ModeManager.current(c)) }
    val s = score(d)
    Text("COSMO // DISCIPLINE", color = Dim, fontSize = 12.sp, letterSpacing = 3.sp)
    Text("$s", color = if (s >= 60) Cyan else Crimson, fontSize = 72.sp, fontWeight = FontWeight.Black)
    Bar("WATER", d.water / 3000f, "${d.water} / 3000 ml")
    Bar("STEPS", d.steps / 8000f, "${d.steps} / 8000")
    val info = mapOf(
        Mode.STUDY to Triple("STUDY", "DND · whitelist · focus", Cyan), Mode.GAME to Triple("GAME", "120Hz · RAM · vivid", Crimson),
        Mode.SLEEP to Triple("SLEEP", "red shift · hard lock", Crimson), Mode.NORMAL to Triple("NORMAL", "restore everything", Cyan)
    )
    info.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (m, t) ->
                ModeCard(t.first, t.second, t.third, mode == m, Modifier.weight(1f)) { mode = m; scope.launch { ModeManager.set(c, m) } }
            }
        }
    }
    if (mode == Mode.SLEEP) {
        val alarm = c.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        Text("Next alarm: " + (alarm?.let { SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it)) } ?: "none"), color = Crimson)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TimeBtn("Bed", "bed", 23 * 60 + 30); TimeBtn("Wake", "wake", 7 * 60) }
    }
    AppPicker("Game app", "game", false, "com.mobile.legends")
}

@Composable fun Habits(d: DayLog, dao: CosmoDao) {
    val scope = rememberCoroutineScope(); val done = doneSet(d)
    Bar("WATER", d.water / 3000f, "${d.water} / 3000 ml")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(250, 500, -250).forEach { n -> OutlinedButton({ scope.launch { dao.addWater(d.date, n) } }) { Text("%+d".format(n)) } }
    }
    Bar("STEPS", d.steps / 8000f, "${d.steps} / 8000")
    Bar("ROUTINES", done.size / HABITS.size.toFloat(), "${done.size}/${HABITS.size}", Crimson)
    HABITS.forEach { h ->
        Row(Modifier.fillMaxWidth().clickable {
            scope.launch { dao.setDone(d.date, (if (h in done) done - h else done + h).joinToString(",")) }
        }, verticalAlignment = Alignment.CenterVertically) { Checkbox(h in done, null); Text(h, Modifier.padding(start = 8.dp)) }
    }
}

@Composable fun Study(d: DayLog) {
    val c = LocalContext.current
    var brk by remember { mutableStateOf(c.prefs.getBoolean("onBreak", false)) }
    Text("FOCUS ${d.focus} min  ·  BREAK ${d.brk} min", color = Cyan)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("On break"); Switch(brk, { brk = it; c.prefs.edit().putBoolean("onBreak", it).apply() }, Modifier.padding(start = 8.dp))
    }
    AppPicker("Whitelist", "white", true)
    Text("STUDY PLAYER", color = Cyan, fontSize = 12.sp, letterSpacing = 3.sp)
    if (Mp3.files.isEmpty()) Text("Drop .mp3 files into Music/CosmoStudy/", color = Dim)
    else {
        Text(Mp3.files[Mp3.idx].nameWithoutExtension, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ Mp3.play(Mp3.idx - 1) }) { Text("⏮") }
            Button({ Mp3.toggle() }) { Text(if (Mp3.playing) "⏸" else "▶") }
            TextButton({ Mp3.play(Mp3.idx + 1) }) { Text("⏭") }
        }
    }
}

@Composable fun Queue(dao: CosmoDao) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val notes by remember { dao.notes() }.collectAsState(emptyList())
    var txt by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(c.prefs.getString("gkey", "")!!) }
    OutlinedTextField(key, { key = it; c.prefs.edit().putString("gkey", it).apply() }, Modifier.fillMaxWidth(),
        label = { Text("Gemini API key (stored locally)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
    OutlinedTextField(txt, { txt = it }, Modifier.fillMaxWidth(), label = { Text("Task / event / research question") })
    Button({ if (txt.isNotBlank()) scope.launch { dao.add(Note(text = txt.trim())); txt = "" } }) { Text("Queue it") }
    notes.forEach { n ->
        Row(Modifier.fillMaxWidth().border(1.dp, Color(0xFF22222C), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text((if (n.done) "✓ " else "⏳ ") + n.text)
                if (n.done) Text(n.answer, color = Cyan, fontSize = 13.sp)
            }
            TextButton({ scope.launch { dao.del(n.id) } }) { Text("✕", color = Dim) }
        }
    }
}

@Composable fun Bar(label: String, v: Float, right: String, color: Color = Cyan) {
    Column {
        Row { Text(label, color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(right, color = color, fontSize = 12.sp) }
        LinearProgressIndicator({ v.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color, Color(0xFF1A1A22))
    }
}

@Composable fun ModeCard(t: String, s: String, col: Color, on: Boolean, m: Modifier, click: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(m.clip(shape).background(if (on) col.copy(alpha = .14f) else Panel)
        .border(1.dp, if (on) col else Color(0xFF22222C), shape).clickable(onClick = click).padding(16.dp)) {
        Text(t, color = col, fontWeight = FontWeight.Bold); Text(s, color = Dim, fontSize = 11.sp)
    }
}

@Composable fun TimeBtn(label: String, key: String, def: Int) {
    val c = LocalContext.current; var v by remember { mutableIntStateOf(c.prefs.getInt(key, def)) }
    OutlinedButton({
        TimePickerDialog(c, { _, h, m -> v = h * 60 + m; c.prefs.edit().putInt(key, v).apply() }, v / 60, v % 60, true).show()
    }) { Text("$label %02d:%02d".format(v / 60, v % 60)) }
}

@Composable fun AppPicker(title: String, key: String, multi: Boolean, def: String = "") {
    val c = LocalContext.current; var open by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(if (multi) c.prefs.getStringSet(key, emptySet())!! else setOf(c.prefs.getString(key, def)!!)) }
    OutlinedButton({ open = true }) { Text(if (multi) "$title (${sel.size})" else title) }
    if (open) {
        val apps = remember {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(c.packageManager).toString() to it.activityInfo.packageName }.sortedBy { it.first.lowercase() }
        }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
            confirmButton = { TextButton({ open = false }) { Text("Done") } },
            text = {
                LazyColumn {
                    items(apps) { (name, pkg) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            sel = if (!multi) setOf(pkg) else if (pkg in sel) sel - pkg else sel + pkg
                            c.prefs.edit().also { if (multi) it.putStringSet(key, sel) else it.putString(key, pkg) }.apply()
                        }, verticalAlignment = Alignment.CenterVertically) { Checkbox(pkg in sel, null); Text(name, Modifier.padding(8.dp)) }
                    }
                }
            })
    }
}

/** App-scoped MP3 player: survives activity recreation; plays /Music/CosmoStudy/*.mp3 */
object Mp3 {
    private var mp: MediaPlayer? = null
    val files = mutableStateListOf<File>()
    var idx by mutableIntStateOf(0); private set
    var playing by mutableStateOf(false); private set

    fun load() {
        files.clear(); idx = 0
        files += File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "CosmoStudy")
            .listFiles().orEmpty().filter { it.extension.equals("mp3", true) }.sortedBy { it.name }
    }
    fun play(i: Int) {
        if (files.isEmpty()) return
        idx = (i + files.size) % files.size; mp?.release()
        mp = MediaPlayer().apply { setDataSource(files[idx].path); setOnCompletionListener { play(idx + 1) }; prepare(); start() }
        playing = true
    }
    fun toggle() { val m = mp ?: return play(idx); if (m.isPlaying) m.pause() else m.start(); playing = m.isPlaying }
}
