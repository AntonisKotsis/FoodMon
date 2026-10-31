package com.ak.foodmon.ui.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ak.foodmon.recipes.MealPlanner
import com.ak.foodmon.recipes.ShoppingLine
import com.ak.foodmon.ui.containerViewModel
import com.ak.foodmon.ui.kcal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(recipeId: Long, onBack: () -> Unit) {
    val vm = containerViewModel { RecipeViewModel(it, recipeId) }
    val recipe = vm.recipe
    val uri = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.title ?: "Recipe", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        when {
            vm.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            recipe == null -> Column(
                Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(vm.error ?: "Couldn't load this recipe.")
                OutlinedButton(onClick = vm::load) { Text("Try again") }
            }
            else -> LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recipe.image?.let { image ->
                    item {
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                item {
                    val facts = listOfNotNull(
                        recipe.readyInMinutes?.let { "Ready in $it min" },
                        recipe.kcalPerServing?.let { "${it.kcal()} per serving" },
                        "Scaled to ${MealPlanner.SERVINGS} servings (recipe makes ${recipe.servings})",
                    )
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                item { Text("Ingredients", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(MealPlanner.scale(recipe)) { ing ->
                    val line = ShoppingLine(ing.name, MealPlanner.roundAmount(ing.amount, ing.unit), ing.unit, ing.aisle)
                    Text("• ${MealPlanner.lineText(line)}")
                }
                if (recipe.steps.isNotEmpty()) {
                    item { Text("Steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    itemsIndexed(recipe.steps) { i, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recipe.sourceUrl?.let { url -> Link("View the original recipe") { uri.openUri(url) } }
                        Link("Powered by Spoonacular") { uri.openUri(recipe.spoonacularUrl ?: SPOONACULAR_URL) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
