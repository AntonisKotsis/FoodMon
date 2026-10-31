package com.ak.foodmon

import com.ak.foodmon.recipes.SpoonacularClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoonacularParseTest {
    private val search = """
        {"results":[{
          "id":715,"title":"Greek Chicken Souvlaki","image":"https://img/715.jpg","servings":6,
          "readyInMinutes":40,"sourceUrl":"https://example.com/souvlaki",
          "spoonacularSourceUrl":"https://spoonacular.com/souvlaki-715","cuisines":["Greek","Mediterranean"],
          "nutrition":{"nutrients":[{"name":"Calories","amount":412.3,"unit":"kcal"},{"name":"Protein","amount":38,"unit":"g"}]},
          "analyzedInstructions":[{"name":"","steps":[{"number":1,"step":"Marinate the chicken."},{"number":2,"step":"Grill it."}]}],
          "missedIngredients":[
            {"id":5062,"name":"chicken breast","amount":600,"unit":"g","unitShort":"g","aisle":"Meat"},
            {"id":9152,"name":"lemon juice","amount":2,"unit":"tablespoons","unitShort":"Tbsp","aisle":"Produce;Beverages"}
          ],
          "usedIngredients":[]
        },{"id":0,"title":"broken"}],"totalResults":1}
    """.trimIndent()

    private val bulk = """
        [{"id":716,"title":"Fasolakia","servings":4,"cuisines":[],
          "instructions":"<ol><li>Chop beans.</li><li>Simmer.</li></ol>",
          "analyzedInstructions":[],
          "extendedIngredients":[
            {"id":11052,"name":"green beans","amount":1,"unit":"pound","aisle":"Produce",
             "measures":{"us":{"amount":1,"unitShort":"lb"},"metric":{"amount":453.592,"unitShort":"g"}}}
          ]}]
    """.trimIndent()

    @Test
    fun parsesSearchResults() {
        val list = SpoonacularClient.parseSearch(search)
        assertEquals(1, list.size)
        val r = list.single().recipe
        assertEquals(715L, r.id)
        assertEquals("Greek Chicken Souvlaki", r.title)
        assertEquals(6, r.servings)
        assertEquals(412.3, r.kcalPerServing!!, 1e-9)
        assertEquals(listOf("Marinate the chicken.", "Grill it."), r.steps)
        assertEquals(2, r.ingredients.size)
        assertEquals("Tbsp", r.ingredients[1].unit)
        assertTrue(list.single().json.contains("Souvlaki"))
    }

    @Test
    fun parsesBulkWithMetricMeasuresAndHtmlInstructions() {
        val r = SpoonacularClient.parseBulk(bulk).single().recipe
        assertNull(r.kcalPerServing)
        assertEquals(453.592, r.ingredients.single().amount, 1e-9)
        assertEquals("g", r.ingredients.single().unit)
        assertEquals(listOf("Chop beans.", "Simmer."), r.steps)
    }

    @Test
    fun cachedJsonParsesBackToSameRecipe() {
        val fetched = SpoonacularClient.parseSearch(search).single()
        assertEquals(fetched.recipe, SpoonacularClient.parseRecipe(org.json.JSONObject(fetched.json)))
    }

    @Test
    fun costsMatchFreePlanPricing() {
        assertEquals(4.55, SpoonacularClient.searchCost(30), 1e-9)
        assertEquals(3.5, SpoonacularClient.bulkCost(6), 1e-9)
        assertEquals(1.0, SpoonacularClient.bulkCost(1), 1e-9)
    }
}
