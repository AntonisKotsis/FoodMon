package com.ak.foodmon.ui.scan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ak.foodmon.data.VisionModel
import com.ak.foodmon.ui.NumberField
import com.ak.foodmon.ui.containerViewModel
import com.ak.foodmon.ui.kcal
import com.ak.foodmon.ui.today.MEALS
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(date: String, onDone: () -> Unit) {
    val vm = containerViewModel { ScanViewModel(it, date) }
    val context = LocalContext.current
    val model by vm.model.collectAsStateWithLifecycle(VisionModel.OPUS)

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.onPhotoCaptured()
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.onPhotoPicked(context, uri)
    }
    fun launchCamera() {
        vm.captureFile.delete()
        takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", vm.captureFile))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log food") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { vm.save(onDone) }, enabled = vm.canSave) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoBox(vm.photo, busy = vm.status == ScanStatus.Preparing, onCamera = ::launchCamera)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::launchCamera) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.photo == null) "Take photo" else "Retake")
                }
                OutlinedButton(onClick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
            }

            OutlinedTextField(
                value = vm.hint,
                onValueChange = { vm.hint = it },
                label = { Text("Optional note, e.g. \"2 eggs fried in butter\"") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (vm.photo != null) {
                Button(onClick = vm::analyze, enabled = vm.status !is ScanStatus.Analyzing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.items.isEmpty()) "Analyze with ${model.label}" else "Re-analyze with note")
                }
            }

            when (val status = vm.status) {
                ScanStatus.Analyzing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Estimating portions and calories…")
                }
                is ScanStatus.Error -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(status.message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                else -> Unit
            }

            if (vm.notes.isNotBlank()) {
                Text(vm.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("Meal", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MEALS.forEach { meal ->
                    FilterChip(selected = vm.meal == meal, onClick = { vm.meal = meal }, label = { Text(meal) })
                }
            }

            if (vm.items.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Items", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("Total ${vm.totalKcal.kcal()}", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Check the portions. Changing grams updates calories and macros.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            vm.items.forEach { item -> ItemEditor(item, vm.summary(item), onRemove = { vm.removeItem(item) }) }

            TextButton(onClick = vm::addManualItem) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add item manually")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PhotoBox(photo: File?, busy: Boolean, onCamera: () -> Unit) {
    Card(onClick = onCamera, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator()
                photo != null -> AsyncImage(
                    model = photo,
                    contentDescription = "Meal photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)),
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(48.dp))
                    Text("Take a photo of your meal", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ItemEditor(item: ItemDraft, summary: String, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { item.name = it },
                    label = { Text("Food") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(item.gramsText, item::updateGrams, "Grams", Modifier.weight(1f))
                NumberField(item.kcalText, item::updateKcal, "kcal", Modifier.weight(1f))
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}