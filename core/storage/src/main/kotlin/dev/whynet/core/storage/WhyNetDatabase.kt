package dev.whynet.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM diagnostic_sessions ORDER BY createdAtEpochMs DESC LIMIT 100")
    fun observeRecent(): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity)

    @Query("DELETE FROM diagnostic_sessions WHERE sessionId = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM diagnostic_sessions")
    suspend fun clearAll()
}

@Database(
    entities = [SessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WhyNetDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var instance: WhyNetDatabase? = null

        fun getInstance(context: Context): WhyNetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WhyNetDatabase::class.java,
                    "whynet_history.db",
                ).build().also { instance = it }
            }
    }
}
