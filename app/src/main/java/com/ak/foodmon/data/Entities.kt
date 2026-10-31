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
/** One week's plan for a profile. [weekStart] is the ISO date of that week's Monday. */
@Entity(
    tableName = "meal_plans",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId", "weekStart", unique = true)],
)
data class MealPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val weekStart: String,
    val createdAt: Long,
)

/**
 * One recipe in a plan. Only the Spoonacular id, title and calories are kept, because their
 * terms allow caching recipe data for one hour only; full details are refetched when needed.
 */
@Entity(
    tableName = "planned_meals",
    foreignKeys = [ForeignKey(MealPlan::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId", "block", "slot", unique = true)],
)
data class PlannedMeal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    /** 0 = Mon–Tue, 1 = Wed–Thu, 2 = Fri–Sat. */
    val block: Int,
    /** [com.ak.foodmon.recipes.MealSlot] name. */
    val slot: String,
    val recipeId: Long,
    val title: String,
    val kcalPerServing: Double?,
)

/** Candidate recipes from the last search, used for free swaps. Ids and titles only. */
@Entity(
    tableName = "recipe_pool",
    primaryKeys = ["planId", "recipeId"],
    foreignKeys = [ForeignKey(MealPlan::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
)
data class PoolRecipe(
    val planId: Long,
    val recipeId: Long,
    val title: String,
    val kcalPerServing: Double?,
)

/** The user's own shopping list, built once from the plan's recipes. */
@Entity(
    tableName = "shopping_items",
    foreignKeys = [ForeignKey(MealPlan::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId")],
)
data class ShoppingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val name: String,
    val amount: Double,
    val unit: String,
    val aisle: String,
    val checked: Boolean = false,
)

/** Recipes the user swapped away; never suggested to that profile again. */
@Entity(
    tableName = "disliked_recipes",
    primaryKeys = ["profileId", "recipeId"],
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
)
data class DislikedRecipe(val profileId: Long, val recipeId: Long)

/** Spoonacular recipe JSON, deleted after one hour as their terms require. */
@Entity(tableName = "recipe_cache")
data class CachedRecipe(
    @PrimaryKey val recipeId: Long,
    val json: String,
    val fetchedAt: Long,
)
