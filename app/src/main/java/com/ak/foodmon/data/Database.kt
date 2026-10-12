package com.ak.foodmon.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: Long): Flow<Profile?>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): Profile?

    @Upsert
    suspend fun upsert(profile: Profile): Long

    @Delete
    suspend fun delete(profile: Profile)
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM food_entries WHERE profileId = :profileId AND date = :date ORDER BY timestamp")
    fun observeDay(profileId: Long, date: String): Flow<List<FoodEntry>>

    @Query(
        "SELECT date, SUM(kcal) AS kcal FROM food_entries " +
            "WHERE profileId = :profileId AND date BETWEEN :from AND :to GROUP BY date"
    )
    fun observeTotals(profileId: Long, from: String, to: String): Flow<List<DayTotal>>

    @Insert
    suspend fun insertAll(entries: List<FoodEntry>)

    @Delete
    suspend fun delete(entry: FoodEntry)
}

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercise_entries WHERE profileId = :profileId AND date = :date ORDER BY timestamp")
    fun observeDay(profileId: Long, date: String): Flow<List<ExerciseEntry>>

    @Query(
        "SELECT date, SUM(kcal) AS kcal FROM exercise_entries " +
            "WHERE profileId = :profileId AND date BETWEEN :from AND :to GROUP BY date"
    )
    fun observeTotals(profileId: Long, from: String, to: String): Flow<List<DayTotal>>

    @Insert
    suspend fun insert(entry: ExerciseEntry)

    @Delete
    suspend fun delete(entry: ExerciseEntry)
}

@androidx.room.Database(
    entities = [Profile::class, FoodEntry::class, ExerciseEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun food(): FoodDao
    abstract fun exercise(): ExerciseDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "foodmon.db").build()
    }
}