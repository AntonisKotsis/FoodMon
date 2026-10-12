package com.ak.foodmon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ak.foodmon.data.VisionModel
import com.ak.foodmon.domain.Calories
import com.ak.foodmon.ui.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onEditProfile: (Long) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val profiles by c.db.profiles().observeAll().collectAsStateWithLifecycle(emptyList())
    val model by c.settings.model.collectAsStateWithLifecycle(VisionModel.OPUS)

    var apiKey by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        savedKey = c.settings.apiKey.first()
        apiKey = savedKey
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            SectionTitle("Profiles")
            profiles.forEach { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("Target ${Calories.target(p)} kcal/day") },
                    trailingContent = {
                        IconButton(onClick = { onEditProfile(p.id) }) { Icon(Icons.Default.Edit, contentDescription = "Edit ${p.name}") }
                    },
                )
            }
            TextButton(onClick = { onEditProfile(0) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add profile") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Photo analysis (Claude API)")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Photos are sent to Anthropic's Claude API using your own API key, so you only pay " +
                        "for what you use. Create a key at console.anthropic.com → API Keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Anthropic API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            savedKey.isBlank() -> "No key saved"
                            apiKey.trim() == savedKey -> "Key saved"
                            else -> "Unsaved changes"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = apiKey.trim() != savedKey, onClick = {
                        scope.launch {
                            c.settings.setApiKey(apiKey)
                            savedKey = apiKey.trim()
                        }
                    }) { Text("Save key") }
                }
            }

            Text("Model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            VisionModel.entries.forEach { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = model == m, onClick = { scope.launch { c.settings.setModel(m) } })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = model == m, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(m.label)
                        Text(m.costHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}