package com.ak.foodmon.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ak.foodmon.AppContainer
import com.ak.foodmon.FoodMonApp
import com.ak.foodmon.data.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as FoodMonApp).container

@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel { create(container) }
}

/** The selected profile, falling back to the first one if none has been picked yet. */
fun AppContainer.activeProfile(): Flow<Profile?> =
    combine(settings.activeProfileId, db.profiles().observeAll()) { id, all ->
        all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            if (new.all { it.isDigit() || (decimal && (it == '.' || it == ',')) }) onValueChange(new)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

fun String.toNumber(): Double? = replace(',', '.').toDoubleOrNull()

fun Double.kcal(): String = "${roundToInt()} kcal"

/** Whole numbers without decimals, everything else with one. */
fun Double.fmt(): String = if (this % 1.0 == 0.0) toInt().toString() else String.format(Locale.US, "%.1f", this)

fun LocalDate.label(): String = when (this) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}