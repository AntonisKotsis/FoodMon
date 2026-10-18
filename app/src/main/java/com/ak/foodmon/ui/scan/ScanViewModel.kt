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
import com.ak.foodmon.food.LookupException
import com.ak.foodmon.food.Product
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

/** Where an item came from. Re-analyzing the photo only replaces [PHOTO] items. */
enum class ItemSource { PHOTO, PACKAGED, MANUAL }

/** One editable line in the review list. Changing grams rescales calories and macros. */
class ItemDraft(
    name: String,
    grams: Double,
    kcal: Double,
    protein: Double,
    carbs: Double,
    fat: Double,
    val source: ItemSource = ItemSource.MANUAL,
) {
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
        fun from(item: AnalyzedItem) =
            ItemDraft(item.name, item.grams, item.kcal, item.proteinG, item.carbsG, item.fatG, ItemSource.PHOTO)

        /** Starts at one serving, or 100 g when the package doesn't say. */
        fun from(product: Product): ItemDraft {
            val grams = product.servingGrams ?: 100.0
            val factor = grams / 100
            return ItemDraft(
                product.name, grams, product.kcalPer100g * factor, product.proteinPer100g * factor,
                product.carbsPer100g * factor, product.fatPer100g * factor, ItemSource.PACKAGED,
            )
        }
    }
}

sealed interface ScanStatus {
    data object Idle : ScanStatus
    data object Preparing : ScanStatus
    data object Analyzing : ScanStatus
    data object LookingUp : ScanStatus
    data object ReadingLabel : ScanStatus
    /** Scanned product isn't in Open Food Facts; offer to photograph its nutrition label. */
    data class NotFound(val barcode: String, val error: String? = null) : ScanStatus
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
            items.removeAll { it.source == ItemSource.PHOTO }
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
                items.removeAll { it.source == ItemSource.PHOTO }
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

    fun onBarcode(barcode: String) = viewModelScope.launch {
        status = ScanStatus.LookingUp
        status = try {
            val product = c.products.lookup(barcode)
            if (product == null) {
                ScanStatus.NotFound(barcode)
            } else {
                items.add(ItemDraft.from(product))
                ScanStatus.Idle
            }
        } catch (e: LookupException) {
            ScanStatus.Error(e.message!!)
        } catch (e: Exception) {
            ScanStatus.Error("Product lookup failed: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    fun onScanFailed(e: Exception) {
        status = ScanStatus.Error("Barcode scanner unavailable: ${e.message ?: e.javaClass.simpleName}.")
    }

    /** The label photo was written to [captureFile]; it is only read, never kept as the meal photo. */
    fun onLabelCaptured() {
        val barcode = (status as? ScanStatus.NotFound)?.barcode ?: return
        viewModelScope.launch {
            status = ScanStatus.ReadingLabel
            status = try {
                val jpeg = withContext(Dispatchers.IO) { FoodAnalyzer.prepareJpeg(captureFile) }
                val product = c.analyzer.readLabel(c.settings.currentApiKey(), c.settings.currentModel(), jpeg)
                items.add(ItemDraft.from(product.copy(name = product.name.ifBlank { "Product $barcode" })))
                ScanStatus.Idle
            } catch (e: AnalysisException) {
                ScanStatus.NotFound(barcode, e.message)
            } catch (e: Exception) {
                ScanStatus.NotFound(barcode, "Reading the label failed: ${e.message ?: e.javaClass.simpleName}.")
            }
        }
    }

    fun dismissNotFound() {
        status = ScanStatus.Idle
    }

    fun addManualItem() = items.add(ItemDraft("", 100.0, 0.0, 0.0, 0.0, 0.0))

    fun removeItem(item: ItemDraft) = items.remove(item)

    /** A photo or network call is in flight; starting another would race over [status] and [items]. */
    val busy get() = status in setOf(ScanStatus.Preparing, ScanStatus.Analyzing, ScanStatus.LookingUp, ScanStatus.ReadingLabel)

    val canSave get() = items.isNotEmpty() && items.all { it.name.isNotBlank() && it.grams > 0 } && !busy

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
                    photoPath = if (it.source == ItemSource.PACKAGED) null else savedPhoto?.path,
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