package com.ak.foodmon.domain

import com.ak.foodmon.data.Profile
import java.time.LocalDate
import kotlin.math.roundToInt

/** Everyday activity outside of logged workouts. Multiplies BMR. */
enum class ActivityLevel(val label: String, val factor: Double) {
    SEDENTARY("Sedentary (desk job)", 1.2),
    LIGHT("Lightly active (on feet some of the day)", 1.375),
    MODERATE("Moderately active (on feet most of the day)", 1.55),
    VERY("Very active (physical job)", 1.725);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: SEDENTARY
    }
}

enum class Goal(val label: String, val dailyAdjustment: Int) {
    LOSE("Lose ~0.5 kg/week", -500),
    LOSE_SLOW("Lose ~0.25 kg/week", -250),
    MAINTAIN("Maintain weight", 0),
    GAIN("Gain weight", 300);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: MAINTAIN
    }
}

object Calories {
    fun age(profile: Profile, today: LocalDate = LocalDate.now()) = today.year - profile.birthYear

    /** Mifflin-St Jeor basal metabolic rate, kcal/day. */
    fun bmr(profile: Profile): Double {
        val base = 10 * profile.weightKg + 6.25 * profile.heightCm - 5 * age(profile)
        return if (profile.sex == "male") base + 5 else base - 161
    }

    /** Calories burned on a day with no workouts. */
    fun baseline(profile: Profile): Double = bmr(profile) * ActivityLevel.of(profile.activityLevel).factor

    /** Daily intake target before adding exercise. */
    fun target(profile: Profile): Int =
        (baseline(profile) + Goal.of(profile.goal).dailyAdjustment).roundToInt()
}

/** Compendium-of-Physical-Activities MET values for manual workout logging. */
enum class Activity(val label: String, val met: Double) {
    WALKING("Walking (brisk)", 4.3),
    RUNNING("Running (~10 km/h)", 9.8),
    CYCLING("Cycling (moderate)", 7.5),
    SWIMMING("Swimming", 7.0),
    STRENGTH("Strength training", 5.0),
    HIIT("HIIT / circuit", 8.0),
    YOGA("Yoga / stretching", 2.5),
    HIKING("Hiking", 6.0),
    FOOTBALL("Football", 7.0),
    BASKETBALL("Basketball", 6.5),
    TENNIS("Tennis", 7.3),
    DANCING("Dancing", 5.0),
    ROWING("Rowing machine", 7.0),
    ELLIPTICAL("Elliptical", 5.0);

    /** kcal = MET × body weight (kg) × hours. */
    fun kcal(weightKg: Double, minutes: Int): Double = met * weightKg * minutes / 60.0
}