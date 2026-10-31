package com.ak.foodmon.recipes

import com.ak.foodmon.data.SpoonacularQuota
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset

class RecipeException(message: String) : Exception(message)

/** What a Spoonacular call returned, including the quota headers it reported. */
class QuotaResponse<T>(val body: T, val quotaUsed: Double?, val quotaLeft: Double?)

/**
 * Keeps Spoonacular calls inside the free plan: 50 points a day, 1 request a second and
 * 2 concurrent requests. Calls run one at a time, at least [minSpacingMs] apart, and a call is
 * refused before it is sent if it would leave fewer than [reserve] points for the day.
 */
class QuotaGuard(
    private val load: suspend () -> SpoonacularQuota?,
    private val save: suspend (SpoonacularQuota) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    val dailyLimit: Double = 50.0,
    private val reserve: Double = 3.0,
    private val minSpacingMs: Long = 1_100,
) {
    private val mutex = Mutex()
    private var lastCallAt = 0L

    /** Quota for the current UTC day; a stored value from an earlier day counts as nothing used. */
    suspend fun today(): SpoonacularQuota {
        val date = utcDate()
        return load()?.takeIf { it.date == date } ?: SpoonacularQuota(date, 0.0, null)
    }

    /** Points still free to spend today, after keeping the reserve aside. */
    suspend fun spendable(): Double {
        val q = today()
        return (q.left ?: (dailyLimit - q.used)) - reserve
    }

    suspend fun canAfford(cost: Double) = spendable() >= cost

    suspend fun <T> run(estimatedCost: Double, call: suspend () -> QuotaResponse<T>): T = mutex.withLock {
        if (!canAfford(estimatedCost)) {
            throw RecipeException(
                "Not enough Spoonacular points left today (this needs about ${estimatedCost.oneDecimal()}). " +
                    "They reset at midnight UTC.",
            )
        }
        val wait = lastCallAt + minSpacingMs - now()
        if (wait > 0) sleep(wait)
        lastCallAt = now()

        val response = try {
            call()
        } catch (e: QuotaExhaustedException) {
            save(today().copy(left = 0.0))
            throw RecipeException(e.message ?: "Spoonacular's daily limit is used up.")
        } finally {
            lastCallAt = now()
        }
        val before = today()
        val used = response.quotaUsed ?: (before.used + estimatedCost)
        val left = response.quotaLeft ?: before.left?.minus(estimatedCost)
        save(SpoonacularQuota(utcDate(), used, left))
        response.body
    }

    private fun utcDate() = Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).toLocalDate().toString()
}

/** Thrown by the client when Spoonacular answers 402 (daily points used up). */
class QuotaExhaustedException(message: String) : Exception(message)

internal fun Double.oneDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)
