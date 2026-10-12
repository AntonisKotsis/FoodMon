package com.ak.foodmon.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ak.foodmon.data.ExerciseEntry
import com.ak.foodmon.domain.Activity
import com.ak.foodmon.ui.NumberField
import com.ak.foodmon.ui.activeProfile
import com.ak.foodmon.ui.appContainer
import com.ak.foodmon.ui.toNumber
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CUSTOM = "CUSTOM"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExerciseScreen(date: String, onDone: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val profile by c.activeProfile().collectAsStateWithLifecycle(null)

    var activity by rememberSaveable { mutableStateOf(Activity.WALKING.name) }
    var minutes by rememberSaveable { mutableStateOf("30") }
    var customName by rememberSaveable { mutableStateOf("") }
    var customKcal by rememberSaveable { mutableStateOf("") }

    val isCustom = activity == CUSTOM
    val mins = minutes.toNumber()?.roundToInt() ?: 0
    val kcal = when {
        isCustom -> customKcal.toNumber() ?: 0.0
        else -> profile?.let { Activity.valueOf(activity).kcal(it.weightKg, mins) } ?: 0.0
    }
    val name = if (isCustom) customName.trim() else Activity.valueOf(activity).label
    val valid = profile != null && mins > 0 && kcal > 0 && name.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log workout") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(enabled = valid, onClick = {
                        scope.launch {
                            c.db.exercise().insert(
                                ExerciseEntry(
                                    profileId = profile!!.id,
                                    date = date,
                                    timestamp = System.currentTimeMillis(),
                                    name = name,
                                    minutes = mins,
                                    kcal = kcal,
                                )
                            )
                            onDone()
                        }
                    }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Activity", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Activity.entries.forEach { a ->
                    FilterChip(selected = activity == a.name, onClick = { activity = a.name }, label = { Text(a.label) })
                }
                FilterChip(selected = isCustom, onClick = { activity = CUSTOM }, label = { Text("Other…") })
            }
            if (isCustom) {
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Activity name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            NumberField(minutes, { minutes = it }, "Duration (minutes)", Modifier.fillMaxWidth(), decimal = false)
            if (isCustom) {
                NumberField(customKcal, { customKcal = it }, "Calories burned", Modifier.fillMaxWidth())
            }
            Text(
                "≈ ${kcal.roundToInt()} kcal burned",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Estimated from standard intensity values (METs) and your weight. If your watch already " +
                    "recorded this workout in Health Connect, don't log it again here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}