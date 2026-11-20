package dev.cosmo

import android.content.Context
import android.content.SharedPreferences
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

val Context.prefs: SharedPreferences get() = getSharedPreferences("cosmo", Context.MODE_PRIVATE)
fun today(): String = LocalDate.now().toString()

@Entity data class DayLog(
    @PrimaryKey val date: String, val water: Int = 0, val steps: Int = 0,
    val done: String = "", val focus: Int = 0, val brk: Int = 0
)
@Entity data class Note(@PrimaryKey(autoGenerate = true) val id: Int = 0, val text: String, val answer: String = "", val done: Boolean = false)

@Dao interface CosmoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensure(d: DayLog)
    @Query("SELECT * FROM DayLog WHERE date=:d") fun day(d: String): Flow<DayLog?>
    @Query("SELECT * FROM DayLog WHERE date=:d") suspend fun dayNow(d: String): DayLog?
    @Query("UPDATE DayLog SET water=MAX(0,water+:n) WHERE date=:d") suspend fun addWater(d: String, n: Int)
    @Query("UPDATE DayLog SET steps=steps+:n WHERE date=:d") suspend fun addSteps(d: String, n: Int)
    @Query("UPDATE DayLog SET focus=focus+:n WHERE date=:d") suspend fun addFocus(d: String, n: Int)
    @Query("UPDATE DayLog SET brk=brk+:n WHERE date=:d") suspend fun addBreak(d: String, n: Int)
    @Query("UPDATE DayLog SET done=:s WHERE date=:d") suspend fun setDone(d: String, s: String)
    @Insert suspend fun add(n: Note)
    @Update suspend fun upd(n: Note)
    @Query("DELETE FROM Note WHERE id=:id") suspend fun del(id: Int)
    @Query("SELECT * FROM Note ORDER BY id DESC") fun notes(): Flow<List<Note>>
    @Query("SELECT * FROM Note WHERE done=0") suspend fun pending(): List<Note>
    @Query("SELECT * FROM Note WHERE done=1 ORDER BY id DESC LIMIT 5") suspend fun recent(): List<Note>
}

@Database(entities = [DayLog::class, Note::class], version = 1, exportSchema = false)
abstract class DB : RoomDatabase() { abstract fun dao(): CosmoDao }

private var inst: DB? = null
fun db(c: Context): DB = inst ?: synchronized(DB::class) {
    inst ?: Room.databaseBuilder(c.applicationContext, DB::class.java, "cosmo").build().also { inst = it }
}
