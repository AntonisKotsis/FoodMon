package com.ak.foodmon.ui.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ak.foodmon.data.PlannedMeal
import com.ak.foodmon.recipes.MealPlanRepository
import com.ak.foodmon.recipes.MealPlanner
import com.ak.foodmon.recipes.MealSlot
import com.ak.foodmon.recipes.SpoonacularClient
import com.ak.foodmon.recipes.oneDecimal
import com.ak.foodmon.ui.containerViewModel
import com.ak.foodmon.ui.kcal
import java.time.format.DateTimeFormatter
import java.util.Locale

const val SPOONACULAR_URL = "https://spoonacular.com/food-api"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    onOpenRecipe: (Long) -> Unit,
    onOpenShoppingList: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = containerViewModel { PlannerViewModel(it) }
    val plan by vm.plan.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val quota by vm.quota.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmRegenerate by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    val week = vm.weekStart.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    val searchCost = MealPlanRepository.SEARCH_COST.oneDecimal()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Meal planner")
                        Text("Week of $week", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    if (plan != null) {
                        IconButton(enabled = vm.busy == null && hasKey, onClick = { confirmRegenerate = true }) {
                            Icon(Icons.Default.Refresh, contentDescription = "New plan for this week")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            vm.busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!hasKey) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Add your Spoonacular API key", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Recipes come from Spoonacular. Their free plan is enough for weekly planning.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
                            }
                        }
                    }
                }

                if (plan == null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Greek, weight-loss friendly recipes for the week. You cook every two days " +
                                    "(Mon–Tue, Wed–Thu, Fri–Sat): one lunch and one dinner, each for " +
                                    "${MealPlanner.PEOPLE} people × ${MealPlanner.DAYS_PER_BATCH} days.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Up to ${vm.caps.lunch} kcal per lunch serving and ${vm.caps.dinner} per dinner serving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(enabled = hasKey && vm.busy == null, onClick = vm::generate) {
                                Text("Plan this week (~$searchCost points)")
                            }
                        }
                    }
                } else {
                    MealPlanner.BLOCKS.forEachIndexed { block, label ->
                        item(key = "block$block") {
                            BatchCard(
                                label = label,
                                meals = meals.filter { it.block == block },
                                freeSwaps = vm.freeSwaps,
                                swapCost = searchCost,
                                enabled = vm.busy == null,
                                onOpen = onOpenRecipe,
                                onSwap = vm::swap,
                            )
                        }
                    }
                    item {
                        Button(onClick = { plan?.let { onOpenShoppingList(it.id) } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null)
                            Text("Shopping list", Modifier.padding(start = 8.dp))
                        }
                    }
                }

                item { QuotaFooter(used = quota?.used ?: 0.0, limit = 50.0) }
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("New plan for this week?") },
            text = { Text("This replaces all 6 recipes and the shopping list. It uses about $searchCost points.") },
            confirmButton = {
                TextButton(onClick = { confirmRegenerate = false; vm.generate() }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BatchCard(
    label: String,
    meals: List<PlannedMeal>,
    freeSwaps: Map<MealSlot, Int>,
    swapCost: String,
    enabled: Boolean,
    onOpen: (Long) -> Unit,
    onSwap: (PlannedMeal) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Cook once · ${MealPlanner.SERVINGS} servings of each",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            meals.sortedBy { MealSlot.valueOf(it.slot).ordinal }.forEachIndexed { i, meal ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                val slot = MealSlot.valueOf(meal.slot)
                val free = (freeSwaps[slot] ?: 0) > 0
                ListItem(
                    overlineContent = { Text(slot.label) },
                    headlineContent = { Text(meal.title) },
                    supportingContent = { meal.kcalPerServing?.let { Text("${it.kcal()} per serving") } },
                    trailingContent = {
                        TextButton(enabled = enabled, onClick = { onSwap(meal) }) {
                            Text(if (free) "Swap" else "Swap · ~$swapCost pts")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpen(meal.recipeId) },
                )
            }
        }
    }
}

@Composable
private fun QuotaFooter(used: Double, limit: Double) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Spoonacular points used today: ${used.oneDecimal()} / ${limit.toInt()} · resets at ${SpoonacularClient.localResetTime()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Recipes powered by Spoonacular",
            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { uri.openUri(SPOONACULAR_URL) },
        )
    }
}
