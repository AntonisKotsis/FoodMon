package com.ak.foodmon.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "male" or "female" — used only by the BMR formula. */
    val sex: String,
    val birthYear: Int,
    val heightCm: Double,
    val weightKg: Double,
    /** [com.ak.foodmon.domain.ActivityLevel] name. */
    val activityLevel: String,
    /** [com.ak.foodmon.domain.Goal] name. */
    val goal: String,
    /** Whether this profile pulls steps/workouts from Health Connect on this phone. */
    val useHealthConnect: Boolean = false,
)

@Entity(
    tableName = "food_entries",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId", "date")],
)
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** ISO date, yyyy-MM-dd. */
    val date: String,
    val timestamp: Long,
    val meal: String,
    val name: String,
    val grams: Double,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val photoPath: String? = null,
)

@Entity(
    tableName = "exercise_entries",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId", "date")],
)
data class ExerciseEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val date: String,
    val timestamp: Long,
    val name: String,
    val minutes: Int,
    val kcal: Double,
)

data class DayTotal(val date: String, val kcal: Double)