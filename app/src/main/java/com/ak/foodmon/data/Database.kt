package com.ak.foodmon.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.OnConflictStrategy
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
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

@Dao
interface PlannerDao {
    @Query("SELECT * FROM meal_plans WHERE profileId = :profileId AND weekStart = :weekStart")
    fun observePlan(profileId: Long, weekStart: String): Flow<MealPlan?>

    @Query("SELECT * FROM meal_plans WHERE id = :id")
    suspend fun plan(id: Long): MealPlan?

    @Query("SELECT * FROM planned_meals WHERE planId = :planId ORDER BY block, slot DESC")
    fun observeMeals(planId: Long): Flow<List<PlannedMeal>>

    @Query("SELECT * FROM planned_meals WHERE planId = :planId")
    suspend fun meals(planId: Long): List<PlannedMeal>

    @Query("SELECT * FROM recipe_pool WHERE planId = :planId")
    suspend fun pool(planId: Long): List<PoolRecipe>

    @Query("SELECT recipeId FROM disliked_recipes WHERE profileId = :profileId")
    suspend fun disliked(profileId: Long): List<Long>

    @Insert
    suspend fun insertPlan(plan: MealPlan): Long

    @Query("DELETE FROM meal_plans WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Insert
    suspend fun insertMeals(meals: List<PlannedMeal>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPool(pool: List<PoolRecipe>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDisliked(disliked: DislikedRecipe)

    @Query("UPDATE planned_meals SET recipeId = :recipeId, title = :title, kcalPerServing = :kcal WHERE id = :mealId")
    suspend fun updateMeal(mealId: Long, recipeId: Long, title: String, kcal: Double?)

    /** A new plan with its meals and swap pool, saved together. */
    @Transaction
    suspend fun savePlan(plan: MealPlan, meals: List<PlannedMeal>, pool: List<PoolRecipe>): Long {
        val id = insertPlan(plan)
        insertMeals(meals.map { it.copy(planId = id) })
        insertPool(pool.map { it.copy(planId = id) })
        return id
    }

    /** Replaces one meal and remembers that the old recipe isn't wanted. */
    @Transaction
    suspend fun swap(profileId: Long, meal: PlannedMeal, replacement: PoolRecipe, extraPool: List<PoolRecipe>) {
        insertDisliked(DislikedRecipe(profileId, meal.recipeId))
        insertPool(extraPool)
        updateMeal(meal.id, replacement.recipeId, replacement.title, replacement.kcalPerServing)
        clearShoppingList(meal.planId)
    }

    @Query("SELECT * FROM shopping_items WHERE planId = :planId ORDER BY aisle, name")
    fun observeShoppingList(planId: Long): Flow<List<ShoppingItem>>

    @Query("SELECT COUNT(*) FROM shopping_items WHERE planId = :planId")
    suspend fun shoppingListSize(planId: Long): Int

    @Insert
    suspend fun insertShoppingItems(items: List<ShoppingItem>)

    @Query("DELETE FROM shopping_items WHERE planId = :planId")
    suspend fun clearShoppingList(planId: Long)

    @Query("UPDATE shopping_items SET checked = :checked WHERE id = :id")
    suspend fun setChecked(id: Long, checked: Boolean)

    @Transaction
    suspend fun replaceShoppingList(planId: Long, items: List<ShoppingItem>) {
        clearShoppingList(planId)
        insertShoppingItems(items)
    }

    @Query("SELECT * FROM recipe_cache WHERE recipeId IN (:ids) AND fetchedAt >= :freshAfter")
    suspend fun cached(ids: List<Long>, freshAfter: Long): List<CachedRecipe>

    @Upsert
    suspend fun cache(recipes: List<CachedRecipe>)

    @Query("DELETE FROM recipe_cache WHERE fetchedAt < :freshAfter")
    suspend fun purgeCache(freshAfter: Long)
}

@androidx.room.Database(
    entities = [
        Profile::class, FoodEntry::class, ExerciseEntry::class,
        MealPlan::class, PlannedMeal::class, PoolRecipe::class, ShoppingItem::class,
        DislikedRecipe::class, CachedRecipe::class,
    ],
    version = 2,
    exportSchema = true,
    // Version 2 only adds the meal planner tables, so Room can migrate it on its own.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun food(): FoodDao
    abstract fun exercise(): ExerciseDao
    abstract fun planner(): PlannerDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "foodmon.db").build()
    }
}