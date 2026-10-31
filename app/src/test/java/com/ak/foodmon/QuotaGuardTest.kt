package com.ak.foodmon

import com.ak.foodmon.data.SpoonacularQuota
import com.ak.foodmon.recipes.QuotaExhaustedException
import com.ak.foodmon.recipes.QuotaGuard
import com.ak.foodmon.recipes.QuotaResponse
import com.ak.foodmon.recipes.RecipeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class QuotaGuardTest {
    private var clock = Instant.parse("2026-11-02T10:00:00Z").toEpochMilli()
    private var stored: SpoonacularQuota? = null
    private val sleeps = mutableListOf<Long>()
    private val guard = QuotaGuard(
        load = { stored },
        save = { stored = it },
        now = { clock },
        sleep = { sleeps += it; clock += it },
    )

    @Test
    fun spacesRequestsAtLeast1100msApart() = runBlocking {
        guard.run(1.0) { QuotaResponse("a", null, null) }
        clock += 300
        guard.run(1.0) { QuotaResponse("b", null, null) }
        assertEquals(listOf(800L), sleeps)
    }

    @Test
    fun noWaitWhenEnoughTimePassed() = runBlocking {
        guard.run(1.0) { QuotaResponse("a", null, null) }
        clock += 5_000
        guard.run(1.0) { QuotaResponse("b", null, null) }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun savesQuotaHeaders() = runBlocking {
        guard.run(4.6) { QuotaResponse("a", 12.5, 37.5) }
        assertEquals(SpoonacularQuota("2026-11-02", 12.5, 37.5), stored)
    }

    @Test
    fun estimatesWhenHeadersMissing() = runBlocking {
        guard.run(4.5) { QuotaResponse("a", null, null) }
        assertEquals(4.5, stored!!.used, 1e-9)
        assertNull(stored!!.left)
    }

    @Test
    fun refusesWithoutSendingWhenPointsTooLow() = runBlocking {
        stored = SpoonacularQuota("2026-11-02", 45.0, 5.0)
        var sent = false
        try {
            guard.run(4.6) { sent = true; QuotaResponse("a", null, null) }
            fail("should refuse")
        } catch (e: RecipeException) {
            // 5 left minus the 3-point reserve is not enough for 4.6.
        }
        assertFalse(sent)
        assertTrue(guard.canAfford(2.0))
    }

    @Test
    fun resetsOnNewUtcDay() = runBlocking {
        stored = SpoonacularQuota("2026-11-01", 50.0, 0.0)
        assertEquals(47.0, guard.spendable(), 1e-9)
        assertEquals("2026-11-02", guard.today().date)
    }

    @Test
    fun exhaustedResponseMarksQuotaEmpty() = runBlocking {
        try {
            guard.run<String>(1.0) { throw QuotaExhaustedException("used up") }
            fail("should throw")
        } catch (e: RecipeException) {
            assertEquals("used up", e.message)
        }
        assertEquals(0.0, stored!!.left!!, 1e-9)
        assertFalse(guard.canAfford(0.5))
    }
}
