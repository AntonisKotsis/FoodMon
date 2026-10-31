package com.ak.foodmon.ui.planner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ak.foodmon.AppContainer
import com.ak.foodmon.data.MealPlan
import com.ak.foodmon.data.PlannedMeal
import com.ak.foodmon.data.Profile
import com.ak.foodmon.data.ShoppingItem
import com.ak.foodmon.data.SpoonacularQuota
import com.ak.foodmon.domain.Calories
import com.ak.foodmon.recipes.CalorieCaps
import com.ak.foodmon.recipes.MealPlanner
import com.ak.foodmon.recipes.MealSlot
import com.ak.foodmon.recipes.Recipe
import com.ak.foodmon.recipes.RecipeException
import com.ak.foodmon.recipes.ShoppingLine
import com.ak.foodmon.ui.activeProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Runs [block], turning Spoonacular errors into a message for the user. */
private suspend fun attempt(onError: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: RecipeException) {
        onError(e.message ?: "Something went wrong with Spoonacular.")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModel(private val c: AppContainer) : ViewModel() {
    val weekStart: LocalDate = MealPlanner.weekStart(LocalDate.now())

    val profile: StateFlow<Profile?> = c.activeProfile().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val plan: StateFlow<MealPlan?> = profile
        .flatMapLatest { p -> if (p == null) flowOf(null) else c.mealPlans.observePlan(p.id, weekStart) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val meals: StateFlow<List<PlannedMeal>> = plan
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else c.mealPlans.observeMeals(p.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hasKey: StateFlow<Boolean> = c.settings.spoonacularKey.map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val quota: StateFlow<SpoonacularQuota?> = c.settings.spoonacularQuota.map { c.quotaGuard.today() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What is running right now, e.g. "Finding recipes…"; null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var freeSwaps by mutableStateOf<Map<MealSlot, Int>>(emptyMap())
        private set

    val caps: CalorieCaps get() = MealPlanner.caps(profile.value?.let(Calories::target))

    init {
        viewModelScope.launch { meals.collect { refreshFreeSwaps() } }
    }

    private suspend fun refreshFreeSwaps() {
        val p = profile.value
        val planId = plan.value?.id
        freeSwaps = if (p == null || planId == null) emptyMap() else c.mealPlans.freeSwaps(p.id, planId, caps)
    }

    fun generate() = launchBusy("Finding Greek recipes…") {
        val p = profile.value ?: return@launchBusy run { message = "Add a profile first, on the Today tab." }
        c.mealPlans.generate(p.id, weekStart, caps, existingPlanId = plan.value?.id)
    }

    fun swap(meal: PlannedMeal) = launchBusy("Finding another recipe…") {
        val p = profile.value ?: return@launchBusy
        c.mealPlans.swap(p.id, meal, caps)
        refreshFreeSwaps()
        message = "Swapped. \"${meal.title}\" won't be suggested again."
    }

    private fun launchBusy(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = label
            try {
                attempt({ message = it }, block)
            } finally {
                busy = null
            }
        }
    }
}

class RecipeViewModel(private val c: AppContainer, private val recipeId: Long) : ViewModel() {
    var recipe by mutableStateOf<Recipe?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            attempt({ error = it }) {
                recipe = c.mealPlans.recipe(recipeId) ?: throw RecipeException("Spoonacular couldn't find this recipe.")
            }
            loading = false
        }
    }
}

class ShoppingListViewModel(private val c: AppContainer, private val planId: Long) : ViewModel() {
    val items: StateFlow<List<ShoppingItem>> = c.mealPlans.observeShoppingList(planId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var weekStart: LocalDate = MealPlanner.weekStart(LocalDate.now())

    init {
        viewModelScope.launch { c.mealPlans.planWeek(planId)?.let { weekStart = it } }
        build(force = false)
    }

    /** Rebuilding refetches the recipes if they're no longer cached (about 3.5 points). */
    fun build(force: Boolean) {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            attempt({ error = it }) { c.mealPlans.ensureShoppingList(planId, force) }
            loading = false
        }
    }

    fun toggle(item: ShoppingItem) {
        viewModelScope.launch { c.mealPlans.setChecked(item, !item.checked) }
    }

    /** Only the items not ticked off yet; everything if they're all ticked. */
    fun whatsAppText(): String {
        val all = items.value
        val toBuy = all.filterNot { it.checked }.ifEmpty { all }
        return MealPlanner.formatForWhatsApp(toBuy.map { ShoppingLine(it.name, it.amount, it.unit, it.aisle) }, weekStart)
    }
}
