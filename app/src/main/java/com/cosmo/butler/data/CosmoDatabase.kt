package com.cosmo.butler.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

// Cosmo-ийн өдөр тутмын Habit болон эрүүл мэндийн дата бааз
@Entity(tableName = "daily_metrics")
data class DailyMetrics(
    @PrimaryKey val date: String, // Формат: YYYY-MM-DD
    val waterIntakeMl: Int = 0,
    val steps: Int = 0,
    val screenTimeMinutes: Int = 0
)

@Dao
interface CosmoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateMetrics(metrics: DailyMetrics)

    @Query("SELECT * FROM daily_metrics WHERE date = :date LIMIT 1")
    suspend fun getMetrics(date: String): DailyMetrics?
}

@Database(entities = [DailyMetrics::class], version = 1, exportSchema = false)
abstract class CosmoDatabase : RoomDatabase() {
    abstract fun cosmoDao(): CosmoDao

    companion object {
        @Volatile
        private var INSTANCE: CosmoDatabase? = null

        fun getDatabase(context: Context): CosmoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CosmoDatabase::class.java,
                    "cosmo_brain.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
