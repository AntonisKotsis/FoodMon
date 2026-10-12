package com.ak.foodmon.ui.today

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ak.foodmon.data.ExerciseEntry
import com.ak.foodmon.data.FoodEntry
import com.ak.foodmon.ui.containerViewModel
import com.ak.foodmon.ui.fmt
import com.ak.foodmon.ui.kcal
import com.ak.foodmon.ui.label
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

val MEALS = listOf("Breakfast", "Lunch", "Dinner", "Snack")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onScanFood: (LocalDate) -> Unit,
    onAddExercise: (LocalDate) -> Unit,
    onEditProfile: (Long) -> Unit,
) {
    val vm = containerViewModel { TodayViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(vm.healthPermissionContract()) { vm.refreshHealth() }

    // Pick up new watch data when coming back to the app.
    LifecycleResumeEffect(Unit) {
        vm.refreshHealth()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                ProfileSwitcher(state, onSelect = vm::selectProfile, onAdd = { onEditProfile(0) })
            })
        },
        floatingActionButton = {
            if (state.profile != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = { onAddExercise(state.date) }) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = "Log workout")
                    }
                    ExtendedFloatingActionButton(
                        onClick = { onScanFood(state.date) },
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                        text = { Text("Log food") },
                    )
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Unit
            state.profile == null -> NoProfile(Modifier.padding(padding), onCreate = { onEditProfile(0) })
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { DayPicker(state.date, onShift = vm::shiftDay) }
                item { SummaryCard(state) }
                if (state.healthStatus != HealthStatus.OFF) {
                    item {
                        HealthCard(state, onConnect = { permissionLauncher.launch(vm.healthPermissions) })
                    }
                }
                MEALS.forEach { meal ->
                    val entries = state.foods.filter { it.meal == meal }
                    if (entries.isNotEmpty()) {
                        item(key = "meal-$meal") { SectionHeader(meal, entries.sumOf { it.kcal }) }
                        items(entries, key = { "food-${it.id}" }) { FoodRow(it, onDelete = { vm.deleteFood(it) }) }
                    }
                }
                if (state.foods.isEmpty()) {
                    item {
                        Text(
                            "Nothing logged yet. Tap \"Log food\" and take a photo of your meal.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                val sessions = state.health?.sessions.orEmpty()
                if (state.exercises.isNotEmpty() || sessions.isNotEmpty()) {
                    item(key = "exercise-header") { SectionHeader("Exercise", state.exercises.sumOf { it.kcal } + sessions.sumOf { it.kcal }) }
                    items(sessions) { s ->
                        ExerciseRow(s.title, "${s.minutes} min · from your watch", s.kcal, onDelete = null)
                    }
                    items(state.exercises, key = { "ex-${it.id}" }) { e ->
                        ExerciseRow(e.name, "${e.minutes} min", e.kcal, onDelete = { vm.deleteExercise(e) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSwitcher(state: TodayState, onSelect: (Long) -> Unit, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (state.profiles.isEmpty()) {
        Text("FoodMon")
        return
    }
    Box {
        TextButton(onClick = { open = true }) {
            Text(state.profile?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch profile")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.profiles.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; onSelect(p.id) })
            }
            DropdownMenuItem(text = { Text("+ Add profile") }, onClick = { open = false; onAdd() })
        }
    }
}

@Composable
private fun NoProfile(modifier: Modifier, onCreate: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Restaurant, contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Welcome to FoodMon", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create a profile so FoodMon can work out your daily calorie budget.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onCreate) { Text("Create profile") }
    }
}

@Composable
private fun DayPicker(date: LocalDate, onShift: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        Text(date.label(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { onShift(1) }, enabled = date.isBefore(LocalDate.now())) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@Composable
private fun SummaryCard(state: TodayState) {
    val budget = state.target + state.burned
    val progress = if (budget > 0) (state.eaten / budget).toFloat() else 0f
    val over = state.remaining < 0
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(120.dp),
                    strokeWidth = 10.dp,
                    strokeCap = StrokeCap.Round,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        abs(state.remaining).roundToInt().toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(if (over) "kcal over" else "kcal left", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SummaryLine("Target", "${state.target}")
                SummaryLine("+ Exercise", "${state.burned.roundToInt()}")
                SummaryLine("− Food", "${state.eaten.roundToInt()}")
                Spacer(Modifier.height(4.dp))
                Text(
                    "P ${state.protein.roundToInt()}g · C ${state.carbs.roundToInt()}g · F ${state.fat.roundToInt()}g",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.width(140.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HealthCard(state: TodayState, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Health Connect", style = MaterialTheme.typography.titleSmall)
                val text = when (state.healthStatus) {
                    HealthStatus.CONNECTED -> state.health?.let {
                        "${it.steps} steps · ${it.activeKcal.roundToInt()} active kcal"
                    } ?: "Loading…"
                    HealthStatus.NEEDS_PERMISSION -> "Allow access to add your watch's activity."
                    HealthStatus.UNAVAILABLE -> "Health Connect isn't installed or available on this phone."
                    HealthStatus.ERROR -> "Couldn't read data from Health Connect."
                    HealthStatus.OFF -> ""
                }
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            if (state.healthStatus == HealthStatus.NEEDS_PERMISSION) {
                OutlinedButton(onClick = onConnect) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, kcal: Double) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(kcal.kcal(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FoodRow(entry: FoodEntry, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (entry.photoPath != null) {
            AsyncImage(
                model = File(entry.photoPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Restaurant, contentDescription = null)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${entry.grams.fmt()} g · P ${entry.proteinG.roundToInt()} · C ${entry.carbsG.roundToInt()} · F ${entry.fatG.roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(entry.kcal.kcal(), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete ${entry.name}") }
    }
}

@Composable
private fun ExerciseRow(name: String, detail: String, kcal: Double, onDelete: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(kcal.kcal(), style = MaterialTheme.typography.bodyMedium)
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete $name") }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}