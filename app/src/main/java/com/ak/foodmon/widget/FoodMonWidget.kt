package com.ak.foodmon.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ak.foodmon.FoodMonApp
import com.ak.foodmon.MainActivity
import com.ak.foodmon.health.HealthDay
import com.ak.foodmon.ui.activeProfile
import com.ak.foodmon.ui.today.TodayState
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

class FoodMonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FoodMonWidget()
}

/** Home screen widget: today's remaining calories for the active profile, plus quick-log buttons. */
class FoodMonWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadToday(context)
        provideContent {
            GlanceTheme { WidgetContent(state) }
        }
    }

    private suspend fun loadToday(context: Context): TodayState {
        val c = (context.applicationContext as FoodMonApp).container
        val profile = c.activeProfile().first() ?: return TodayState(loading = false)
        val today = LocalDate.now()
        val foods = c.db.food().observeDay(profile.id, today.toString()).first()
        val exercises = c.db.exercise().observeDay(profile.id, today.toString()).first()
        // Watch calories come from the app's last Health Connect read today.
        val healthKcal = c.settings.healthCache.first()
            ?.takeIf { profile.useHealthConnect && it.profileId == profile.id && it.date == today.toString() }
            ?.activeKcal
        return TodayState(
            loading = false,
            profile = profile,
            date = today,
            foods = foods,
            exercises = exercises,
            health = healthKcal?.let { HealthDay(steps = 0, activeKcal = it, sessions = emptyList()) },
        )
    }

    companion object {
        private val COMPACT = DpSize(110.dp, 110.dp)
        private val WIDE = DpSize(250.dp, 110.dp)
    }
}

private fun openApp(context: Context, destination: String? = null): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (destination != null) putExtra(MainActivity.EXTRA_DESTINATION, destination) }
    )

@Composable
private fun WidgetContent(state: TodayState) {
    val context = androidx.glance.LocalContext.current
    val wide = LocalSize.current.width >= 250.dp
    val colors = GlanceTheme.colors

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(openApp(context)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val profile = state.profile
        if (profile == null) {
            Text("FoodMon", style = TextStyle(color = colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp))
            Text("Tap to create a profile", style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp))
            return@Column
        }

        val over = state.remaining < 0
        val budget = state.target + state.burned
        Text(profile.name, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                abs(state.remaining).roundToInt().toString(),
                style = TextStyle(
                    color = if (over) colors.error else colors.primary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                if (over) "kcal over" else "kcal left",
                style = TextStyle(color = colors.onSurface, fontSize = 13.sp),
                modifier = GlanceModifier.padding(bottom = 5.dp),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = if (budget > 0) (state.eaten / budget).toFloat().coerceIn(0f, 1f) else 0f,
            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            color = if (over) colors.error else colors.primary,
            backgroundColor = colors.surfaceVariant,
        )
        Spacer(GlanceModifier.height(6.dp))
        if (wide) {
            Text(
                "Ate ${state.eaten.roundToInt()} · Burned ${state.burned.roundToInt()} · Target ${state.target}",
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(8.dp))
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Button(text = "Log food", onClick = openApp(context, MainActivity.DEST_SCAN))
            if (wide) {
                Spacer(GlanceModifier.width(8.dp))
                Button(text = "Workout", onClick = openApp(context, MainActivity.DEST_EXERCISE))
            }
        }
    }
}

