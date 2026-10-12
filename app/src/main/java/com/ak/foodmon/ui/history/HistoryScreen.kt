package com.ak.foodmon.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ak.foodmon.AppContainer
import com.ak.foodmon.data.Profile
import com.ak.foodmon.domain.Calories
import com.ak.foodmon.ui.activeProfile
import com.ak.foodmon.ui.containerViewModel
import com.ak.foodmon.ui.label
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.roundToInt

private const val DAYS = 30L

data class DaySummary(val date: LocalDate, val eaten: Double, val manualExercise: Double)

data class HistoryState(val profile: Profile? = null, val days: List<DaySummary> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<HistoryState> = c.activeProfile().flatMapLatest { profile ->
        if (profile == null) return@flatMapLatest flowOf(HistoryState())
        val to = LocalDate.now()
        val from = to.minusDays(DAYS - 1)
        combine(
            c.db.food().observeTotals(profile.id, from.toString(), to.toString()),
            c.db.exercise().observeTotals(profile.id, from.toString(), to.toString()),
        ) { food, exercise ->
            val eaten = food.associate { it.date to it.kcal }
            val burned = exercise.associate { it.date to it.kcal }
            val days = (0 until DAYS).map { to.minusDays(it) }
                .map { d -> DaySummary(d, eaten[d.toString()] ?: 0.0, burned[d.toString()] ?: 0.0) }
                .filter { it.eaten > 0 || it.manualExercise > 0 }
            HistoryState(profile, days)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    /** Active kcal per day from Health Connect, or empty if it isn't enabled for this profile. */
    suspend fun healthKcal(profile: Profile): Map<LocalDate, Double> = try {
        if (profile.useHealthConnect && c.health.hasPermissions()) {
            c.health.activeKcalByDay(LocalDate.now().minusDays(DAYS - 1), LocalDate.now())
        } else emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val vm = containerViewModel { HistoryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val profile = state.profile
    val health by produceState(emptyMap<LocalDate, Double>(), profile) {
        value = profile?.let { vm.healthKcal(it) } ?: emptyMap()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("History · ${profile?.name ?: ""}") }) }) { padding ->
        if (profile == null) return@Scaffold
        val target = Calories.target(profile)
        val days = state.days
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (days.isNotEmpty()) {
                item {
                    val avg = days.sumOf { it.eaten } / days.size
                    Text(
                        "Average intake over ${days.size} logged days: ${avg.roundToInt()} kcal (target $target + exercise)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                item { Text("Nothing logged in the last $DAYS days.") }
            }
            items(days, key = { it.date.toString() }) { day ->
                val burned = day.manualExercise + (health[day.date] ?: 0.0)
                DayCard(day, target, burned)
            }
        }
    }
}

@Composable
private fun DayCard(day: DaySummary, target: Int, burned: Double) {
    val budget = target + burned
    val diff = day.eaten - budget
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day.date.label(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (diff > 0) "+${diff.roundToInt()} over" else "${(-diff).roundToInt()} under",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (diff > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = { if (budget > 0) (day.eaten / budget).toFloat().coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round,
                color = if (diff > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                "Ate ${day.eaten.roundToInt()} · Burned ${burned.roundToInt()} in exercise · Budget ${budget.roundToInt()} kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}