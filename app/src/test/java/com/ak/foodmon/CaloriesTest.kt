package com.ak.foodmon

import com.ak.foodmon.data.Profile
import com.ak.foodmon.domain.Activity
import com.ak.foodmon.domain.Calories
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CaloriesTest {
    private fun profile(sex: String, age: Int, goal: String = "MAINTAIN") = Profile(
        name = "Test", sex = sex, birthYear = LocalDate.now().year - age,
        heightCm = 180.0, weightKg = 80.0, activityLevel = "SEDENTARY", goal = goal,
    )

    @Test
    fun bmrMatchesMifflinStJeor() {
        // 10*80 + 6.25*180 - 5*30 = 1775
        assertEquals(1780.0, Calories.bmr(profile("male", 30)), 0.01)
        assertEquals(1614.0, Calories.bmr(profile("female", 30)), 0.01)
    }

    @Test
    fun targetAppliesActivityFactorAndGoal() {
        assertEquals(2136, Calories.target(profile("male", 30)))
        assertEquals(1636, Calories.target(profile("male", 30, goal = "LOSE")))
    }

    @Test
    fun workoutCaloriesUseMets() {
        // 9.8 MET * 80 kg * 0.5 h
        assertEquals(392.0, Activity.RUNNING.kcal(80.0, 30), 0.01)
    }
}