package com.ak.foodmon.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ak.foodmon.AppContainer
import com.ak.foodmon.data.ExerciseEntry
import com.ak.foodmon.data.FoodEntry
import com.ak.foodmon.data.HealthCache
import com.ak.foodmon.data.Profile
import com.ak.foodmon.domain.Calories
import com.ak.foodmon.health.HealthDay
import com.ak.foodmon.ui.activeProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

enum class HealthStatus { OFF, UNAVAILABLE, NEEDS_PERMISSION, CONNECTED, ERROR }

data class TodayState(
    val loading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val profile: Profile? = null,
    val date: LocalDate = LocalDate.now(),
    val foods: List<FoodEntry> = emptyList(),
    val exercises: List<ExerciseEntry> = emptyList(),
    val health: HealthDay? = null,
    val healthStatus: HealthStatus = HealthStatus.OFF,
) {
    val target: Int get() = profile?.let(Calories::target) ?: 0
    val eaten: Double get() = foods.sumOf { it.kcal }
    val burned: Double get() = exercises.sumOf { it.kcal } + (health?.activeKcal ?: 0.0)
    val remaining: Double get() = target + burned - eaten
    val protein: Double get() = foods.sumOf { it.proteinG }
    val carbs: Double get() = foods.sumOf { it.carbsG }
    val fat: Double get() = foods.sumOf { it.fatG }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val date = MutableStateFlow(LocalDate.now())
    private val health = MutableStateFlow<HealthDay?>(null)
    private val healthStatus = MutableStateFlow(HealthStatus.OFF)
    private val activeProfile = c.activeProfile()

    val healthPermissions get() = c.health.permissions
    fun healthPermissionContract() = c.health.permissionContract()

    private val day = combine(activeProfile, date) { profile, d -> profile to d }.flatMapLatest { (profile, d) ->
        if (profile == null) {
            flowOf(emptyList<FoodEntry>() to emptyList<ExerciseEntry>())
        } else {
            combine(
                c.db.food().observeDay(profile.id, d.toString()),
                c.db.exercise().observeDay(profile.id, d.toString()),
            ) { f, e -> f to e }
        }
    }

    val state: StateFlow<TodayState> = combine(
        c.db.profiles().observeAll(),
        combine(activeProfile, date, ::Pair),
        day,
        health,
        healthStatus,
    ) { profiles, (profile, d), (foods, exercises), h, hs ->
        TodayState(false, profiles, profile, d, foods, exercises, h, hs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    init {
        // Re-read Health Connect whenever the profile or day changes.
        viewModelScope.launch { combine(activeProfile, date, ::Pair).collect { refreshHealth() } }
    }

    fun refreshHealth() = viewModelScope.launch {
        val profile = activeProfile.first()
        health.value = null
        healthStatus.value = when {
            profile == null || !profile.useHealthConnect -> HealthStatus.OFF
            !c.health.isAvailable -> HealthStatus.UNAVAILABLE
            !c.health.hasPermissions() -> HealthStatus.NEEDS_PERMISSION
            else -> try {
                val day = c.health.readDay(date.value)
                health.value = day
                if (date.value == LocalDate.now()) {
                    c.settings.setHealthCache(HealthCache(profile.id, date.value.toString(), day.activeKcal))
                }
                HealthStatus.CONNECTED
            } catch (e: Exception) {
                HealthStatus.ERROR
            }
        }
    }

    fun shiftDay(days: Long) {
        val next = date.value.plusDays(days)
        if (!next.isAfter(LocalDate.now())) date.value = next
    }

    fun selectProfile(id: Long) = viewModelScope.launch { c.settings.setActiveProfile(id) }

    fun deleteFood(entry: FoodEntry) = viewModelScope.launch {
        c.db.food().delete(entry)
        entry.photoPath?.let { path ->
            // Several items can share one photo; only remove it when nothing references it anymore.
            val stillUsed = state.value.foods.any { it.id != entry.id && it.photoPath == path }
            if (!stillUsed) File(path).delete()
        }
    }

    fun deleteExercise(entry: ExerciseEntry) = viewModelScope.launch { c.db.exercise().delete(entry) }
}