package com.ak.foodmon

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.updateAll
import com.ak.foodmon.ai.FoodAnalyzer
import com.ak.foodmon.data.AppDatabase
import com.ak.foodmon.data.SettingsStore
import com.ak.foodmon.food.ProductLookup
import com.ak.foodmon.health.HealthConnectManager
import com.ak.foodmon.recipes.MealPlanRepository
import com.ak.foodmon.recipes.QuotaGuard
import com.ak.foodmon.recipes.SpoonacularClient
import com.ak.foodmon.ui.activeProfile
import com.ak.foodmon.widget.FoodMonWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** Simple manual dependency container shared by all screens. */
class AppContainer(context: Context) {
    val db = AppDatabase.create(context)
    val settings = SettingsStore(context)
    val analyzer = FoodAnalyzer()
    val products = ProductLookup()
    val health = HealthConnectManager(context)
    val quotaGuard = QuotaGuard(load = { settings.spoonacularQuota.first() }, save = { settings.setSpoonacularQuota(it) })
    val spoonacular = SpoonacularClient(quotaGuard) { settings.currentSpoonacularKey() }
    val mealPlans = MealPlanRepository(db.planner(), spoonacular)
    val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
    val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
}

class FoodMonApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        keepWidgetInSync()
        // Spoonacular data may only be kept for an hour.
        appScope.launch { container.mealPlans.purgeExpiredCache() }
    }

    /** Redraw the home screen widget whenever today's numbers for the active profile change. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun keepWidgetInSync() = appScope.launch {
        container.activeProfile().flatMapLatest { profile ->
            if (profile == null) return@flatMapLatest flowOf(null)
            val today = LocalDate.now().toString()
            combine(
                container.db.food().observeDay(profile.id, today),
                container.db.exercise().observeDay(profile.id, today),
                container.settings.healthCache,
            ) { food, exercise, health -> Triple(food, exercise, health) to profile }
        }
            .debounce(300)
            .collect { FoodMonWidget().updateAll(this@FoodMonApp) }
    }
}