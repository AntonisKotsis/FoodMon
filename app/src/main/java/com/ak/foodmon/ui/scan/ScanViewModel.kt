package com.ak.foodmon.ui.scan

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ak.foodmon.AppContainer
import com.ak.foodmon.ai.AnalysisException
import com.ak.foodmon.ai.AnalyzedItem
import com.ak.foodmon.ai.FoodAnalyzer
import com.ak.foodmon.data.FoodEntry
import com.ak.foodmon.ui.activeProfile
import com.ak.foodmon.ui.fmt
import com.ak.foodmon.ui.toNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.util.UUID
import kotlin.math.roundToInt

/** One editable line in the review list. Changing grams rescales calories and macros. */
class ItemDraft(name: String, grams: Double, kcal: Double, protein: Double, carbs: Double, fat: Double) {
    var name by mutableStateOf(name)
    var gramsText by mutableStateOf(grams.roundToInt().toString())
        private set
    var kcalText by mutableStateOf(kcal.roundToInt().toString())
        private set

    private var kcalPerGram = if (grams > 0) kcal / grams else 0.0
    private val proteinPerGram = if (grams > 0) protein / grams else 0.0
    private val carbsPerGram = if (grams > 0) carbs / grams else 0.0
    private val fatPerGram = if (grams > 0) fat / grams else 0.0

    val grams get() = gramsText.toNumber() ?: 0.0
    val kcal get() = kcalText.toNumber() ?: 0.0
    val protein get() = proteinPerGram * grams
    val carbs get() = carbsPerGram * grams
    val fat get() = fatPerGram * grams

    fun updateGrams(text: String) {
        gramsText = text
        text.toNumber()?.let { kcalText = (kcalPerGram * it).roundToInt().toString() }
    }

    fun updateKcal(text: String) {
        kcalText = text
        val value = text.toNumber()
        if (value != null && grams > 0) kcalPerGram = value / grams
    }

    companion object {
        fun from(item: AnalyzedItem) = ItemDraft(item.name, item.grams, item.kcal, item.proteinG, item.carbsG, item.fatG)
    }
}

sealed interface ScanStatus {
    data object Idle : ScanStatus
    data object Preparing : ScanStatus
    data object Analyzing : ScanStatus
    data class Error(val message: String) : ScanStatus
}

class ScanViewModel(private val c: AppContainer, private val date: String) : ViewModel() {
    /** Downsized, correctly rotated JPEG ready to send to Claude and keep as the entry thumbnail. */
    var photo by mutableStateOf<File?>(null)
        private set
    var hint by mutableStateOf("")
    var meal by mutableStateOf(defaultMeal())
    var notes by mutableStateOf("")
        private set
    var status by mutableStateOf<ScanStatus>(ScanStatus.Idle)
        private set
    val items = mutableStateListOf<ItemDraft>()

    val model = c.settings.model

    /** Where the camera app writes the full-size capture. */
    val captureFile = File(c.cameraDir, "capture.jpg")

    fun onPhotoCaptured() = preparePhoto { captureFile }

    fun onPhotoPicked(context: Context, uri: Uri) = preparePhoto {
        File(c.cameraDir, "picked.jpg").also { out ->
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use(input::copyTo) }
        }
    }

    private fun preparePhoto(source: suspend () -> File) = viewModelScope.launch {
        status = ScanStatus.Preparing
        try {
            val prepared = withContext(Dispatchers.IO) {
                val bytes = FoodAnalyzer.prepareJpeg(source())
                File(c.cameraDir, "prepared-${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }
            }
            photo?.delete()
            photo = prepared
            items.clear()
            notes = ""
            status = ScanStatus.Idle
            analyze()
        } catch (e: Exception) {
            status = ScanStatus.Error(e.message ?: "Couldn't read the photo.")
        }
    }

    fun analyze() {
        val file = photo ?: return
        viewModelScope.launch {
            status = ScanStatus.Analyzing
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val result = c.analyzer.analyze(c.settings.currentApiKey(), c.settings.currentModel(), bytes, hint)
                items.clear()
                items.addAll(result.items.map(ItemDraft::from))
                notes = result.notes
                status = ScanStatus.Idle
            } catch (e: AnalysisException) {
                status = ScanStatus.Error(e.message!!)
            } catch (e: Exception) {
                status = ScanStatus.Error("Analysis failed: ${e.message ?: e.javaClass.simpleName}. Check your internet connection.")
            }
        }
    }

    fun addManualItem() = items.add(ItemDraft("", 100.0, 0.0, 0.0, 0.0, 0.0))

    fun removeItem(item: ItemDraft) = items.remove(item)

    val canSave get() = items.isNotEmpty() && items.all { it.name.isNotBlank() && it.grams > 0 } && status !is ScanStatus.Analyzing

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        val profile = c.activeProfile().first() ?: return@launch
        val savedPhoto = photo?.let { tmp ->
            withContext(Dispatchers.IO) { tmp.copyTo(File(c.photoDir, tmp.name.removePrefix("prepared-")), overwrite = true) }
        }
        val now = System.currentTimeMillis()
        c.db.food().insertAll(
            items.map {
                FoodEntry(
                    profileId = profile.id,
                    date = date,
                    timestamp = now,
                    meal = meal,
                    name = it.name.trim(),
                    grams = it.grams,
                    kcal = it.kcal,
                    proteinG = it.protein,
                    carbsG = it.carbs,
                    fatG = it.fat,
                    photoPath = savedPhoto?.path,
                )
            }
        )
        onSaved()
    }

    override fun onCleared() {
        photo?.delete()
    }

    val totalKcal get() = items.sumOf { it.kcal }

    fun summary(item: ItemDraft) =
        "P ${item.protein.fmt()} g · C ${item.carbs.fmt()} g · F ${item.fat.fmt()} g"

    private fun defaultMeal(): String {
        val hour = LocalTime.now().hour
        return when {
            hour < 11 -> "Breakfast"
            hour < 15 -> "Lunch"
            hour in 17..21 -> "Dinner"
            else -> "Snack"
        }
    }
}