package com.ak.foodmon

import com.ak.foodmon.food.ProductLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductLookupTest {
    @Test
    fun parsesFullProduct() {
        val json = """
            {"status": 1, "product": {
              "product_name": "Greek Yogurt", "brands": "Fage,Fage Total",
              "serving_quantity": "170", "serving_quantity_unit": "g",
              "nutriments": {"energy-kcal_100g": 97, "proteins_100g": 9, "carbohydrates_100g": 3.9, "fat_100g": 5}
            }}
        """
        val p = ProductLookup.parse("123", json)!!
        assertEquals("Greek Yogurt (Fage)", p.name)
        assertEquals(97.0, p.kcalPer100g, 0.01)
        assertEquals(9.0, p.proteinPer100g, 0.01)
        assertEquals(3.9, p.carbsPer100g, 0.01)
        assertEquals(5.0, p.fatPer100g, 0.01)
        assertEquals(170.0, p.servingGrams!!, 0.01)
    }

    @Test
    fun convertsKilojoulesWhenKcalMissing() {
        val json = """{"status": 1, "product": {"product_name": "Cola", "nutriments": {"energy_100g": 180}}}"""
        val p = ProductLookup.parse("123", json)!!
        assertEquals(43.0, p.kcalPer100g, 0.1)
        assertEquals(0.0, p.fatPer100g, 0.01)
        assertNull(p.servingGrams)
    }

    @Test
    fun ignoresNonGramServingsAndNamesUnlabelledProducts() {
        val json = """
            {"status": 1, "product": {"serving_quantity": 2, "serving_quantity_unit": "oz",
              "nutriments": {"energy-kcal_100g": 500}}}
        """
        val p = ProductLookup.parse("5449000000996", json)!!
        assertEquals("Product 5449000000996", p.name)
        assertNull(p.servingGrams)
    }

    @Test
    fun returnsNullWhenUnknownOrWithoutCalories() {
        assertNull(ProductLookup.parse("123", """{"status": 0, "status_verbose": "product not found"}"""))
        assertNull(ProductLookup.parse("123", """{"status": 1, "product": {"product_name": "X", "nutriments": {}}}"""))
    }
}