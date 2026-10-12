package com.ak.foodmon.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ak.foodmon.data.Profile
import com.ak.foodmon.domain.ActivityLevel
import com.ak.foodmon.domain.Calories
import com.ak.foodmon.domain.Goal
import com.ak.foodmon.ui.NumberField
import com.ak.foodmon.ui.appContainer
import com.ak.foodmon.ui.fmt
import com.ak.foodmon.ui.toNumber
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Create (profileId = 0) or edit a profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(profileId: Long, onDone: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()

    var loaded by rememberSaveable { mutableStateOf(profileId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf("male") }
    var birthYear by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var activity by rememberSaveable { mutableStateOf(ActivityLevel.SEDENTARY.name) }
    var goal by rememberSaveable { mutableStateOf(Goal.MAINTAIN.name) }
    var useHealth by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (!loaded) {
            c.db.profiles().get(profileId)?.let { p ->
                name = p.name; sex = p.sex; birthYear = p.birthYear.toString()
                height = p.heightCm.fmt(); weight = p.weightKg.fmt()
                activity = p.activityLevel; goal = p.goal; useHealth = p.useHealthConnect
            }
            loaded = true
        }
    }

    val year = birthYear.toIntOrNull()
    val draft = if (
        name.isNotBlank() && year != null && year in 1900..LocalDate.now().year - 10 &&
        (height.toNumber() ?: 0.0) in 100.0..250.0 && (weight.toNumber() ?: 0.0) in 25.0..400.0
    ) {
        Profile(
            id = profileId, name = name.trim(), sex = sex, birthYear = year,
            heightCm = height.toNumber()!!, weightKg = weight.toNumber()!!,
            activityLevel = activity, goal = goal, useHealthConnect = useHealth,
        )
    } else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profileId == 0L) "New profile" else "Edit profile") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (profileId != 0L) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete profile") }
                    }
                    TextButton(enabled = draft != null, onClick = {
                        scope.launch {
                            val id = c.db.profiles().upsert(draft!!)
                            // A new profile becomes the active one.
                            if (profileId == 0L) c.settings.setActiveProfile(id)
                            onDone()
                        }
                    }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Sex (for the metabolism formula)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sex == "male", onClick = { sex = "male" }, label = { Text("Male") })
                FilterChip(selected = sex == "female", onClick = { sex = "female" }, label = { Text("Female") })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(birthYear, { birthYear = it }, "Birth year", Modifier.weight(1f), decimal = false)
                NumberField(height, { height = it }, "Height (cm)", Modifier.weight(1f))
                NumberField(weight, { weight = it }, "Weight (kg)", Modifier.weight(1f))
            }

            Text("Everyday activity (not counting workouts)", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityLevel.entries.forEach { a ->
                    FilterChip(selected = activity == a.name, onClick = { activity = a.name }, label = { Text(a.label) })
                }
            }

            Text("Goal", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Goal.entries.forEach { g ->
                    FilterChip(selected = goal == g.name, onClick = { goal = g.name }, label = { Text(g.label) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use Health Connect", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Add steps and workouts from your watch or fitness app on this phone. Turn it on " +
                            "for the person who owns the phone, and keep everyday activity at Sedentary so " +
                            "walking isn't counted twice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useHealth, onCheckedChange = { useHealth = it })
            }

            if (draft != null) {
                Text(
                    "Metabolism (BMR): ${Calories.bmr(draft).toInt()} kcal · Daily target: ${Calories.target(draft)} kcal + exercise",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $name?") },
            text = { Text("This removes the profile and all of its food and workout history.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        c.db.profiles().get(profileId)?.let { c.db.profiles().delete(it) }
                        onDone()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}