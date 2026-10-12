package com.ak.foodmon.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.Period

data class HealthSession(val title: String, val minutes: Int, val kcal: Double)

data class HealthDay(val steps: Long, val activeKcal: Double, val sessions: List<HealthSession>)

/** Reads steps, active calories and workouts that watches and fitness apps write to Health Connect. */
class HealthConnectManager(private val context: Context) {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermissions(): Boolean =
        isAvailable && client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun readDay(date: LocalDate): HealthDay {
        val range = TimeRangeFilter.between(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
        val totals = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                timeRangeFilter = range,
            )
        )
        val sessions = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records.map { s ->
            val kcal = client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime),
                )
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
            HealthSession(
                title = s.title ?: exerciseName(s.exerciseType),
                minutes = Duration.between(s.startTime, s.endTime).toMinutes().toInt(),
                kcal = kcal,
            )
        }
        return HealthDay(
            steps = totals[StepsRecord.COUNT_TOTAL] ?: 0L,
            activeKcal = totals[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            sessions = sessions,
        )
    }

    /** Active kcal per day for [from]..[to] inclusive. */
    suspend fun activeKcalByDay(from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        ).associate { bucket ->
            bucket.startTime.toLocalDate() to
                (bucket.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0)
        }

    private fun exerciseName(type: Int): String =
        ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[type]
            ?.replace('_', ' ')
            ?.replaceFirstChar { it.uppercase() }
            ?: "Workout"
}