package app.recomp.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.recomp.MainActivity
import app.recomp.data.repo.TodaySnapshotRepository
import app.recomp.reminders.ReminderScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun snapshots(): TodaySnapshotRepository
    fun scheduler(): ReminderScheduler
}

/**
 * The home-screen widget (HM-05, PRD §12.8): 4×1, resizable down to 2×2. Tapping it opens Today, and *Log food*
 * opens the Food tab. It reads the same `TodaySnapshot` as the reminders.
 */
class RecompWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SQUARE, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshots = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).snapshots()
        val initial = withContext(Dispatchers.IO) { runCatching { WidgetState.of(snapshots.load()) }.getOrDefault(WidgetState.SETUP) }
        provideContent {
            // While this session is alive the widget follows the data directly; WidgetUpdater restarts it afterwards.
            val live = remember { snapshots.observe().map(WidgetState::of).distinctUntilChanged() }
            val state by live.collectAsState(initial)
            GlanceTheme { WidgetContent(state) }
        }
    }

    companion object {
        val WIDE = DpSize(250.dp, 40.dp)
        val SQUARE = DpSize(110.dp, 110.dp)
    }
}

private fun open(context: Context, action: String) =
    actionStartActivity(Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

@Composable
private fun WidgetContent(state: WidgetState) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= RecompWidget.WIDE.width
    val frame = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp)
        .clickable(open(context, MainActivity.ACTION_OPEN_TODAY))
    val label = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
    val value = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (wide) 22.sp else 26.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp)

    if (!state.ready) {
        Column(frame.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recomp", style = value)
            Text("Open the app to finish setting up.", style = body)
        }
        return
    }
    if (wide) {
        Row(frame.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(state.mainLabel, style = label, maxLines = 1)
                Text(state.mainValue, style = value, maxLines = 1)
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(state.second, style = body, maxLines = 1)
                state.workout?.let { Text(it, style = body, maxLines = 1) }
            }
            Button("Log food", onClick = open(context, MainActivity.ACTION_OPEN_FOOD), maxLines = 1)
        }
    } else {
        Column(frame.padding(12.dp)) {
            Text(state.mainLabel, style = label, maxLines = 1)
            Text(state.mainValue, style = value, maxLines = 1)
            Text(state.second, style = body, maxLines = 1)
            state.workout?.let { Text(it, style = body, maxLines = 1) }
            Spacer(GlanceModifier.defaultWeight())
            Button("Log food", onClick = open(context, MainActivity.ACTION_OPEN_FOOD), modifier = GlanceModifier.fillMaxWidth(), maxLines = 1)
        }
    }
}

class RecompWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecompWidget()

    /** The first widget needs the day-rollover alarm; the last one going may leave nothing to wake for. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        reschedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        reschedule(context)
    }

    private fun reschedule(context: Context) {
        val scheduler = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).scheduler()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runCatching { scheduler.reschedule() }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Keeps the widget current (HM-05): it's redrawn when the snapshot it shows changes (food logged, a workout
 * finished, the targets changed) while the app is alive, and by the scheduler's alarm at the day boundary.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshots: TodaySnapshotRepository,
) {
    suspend fun any(): Boolean = runCatching { GlanceAppWidgetManager(context).getGlanceIds(RecompWidget::class.java).isNotEmpty() }.getOrDefault(false)

    suspend fun update() {
        if (any()) RecompWidget().updateAll(context)
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            runCatching { snapshots.observe().map(WidgetState::of).distinctUntilChanged().collect { runCatching { update() } } }
        }
    }
}
