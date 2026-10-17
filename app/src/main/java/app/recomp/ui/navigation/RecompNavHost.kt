package app.recomp.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.recomp.ui.AppNav
import app.recomp.ui.MainViewModel
import app.recomp.ui.ResumePrompt
import app.recomp.ui.components.formatDuration
import app.recomp.ui.dev.DevScreen
import app.recomp.ui.exercises.ExerciseHistoryScreen
import app.recomp.ui.exercises.ExerciseScreen
import app.recomp.ui.exercises.LibraryScreen
import app.recomp.ui.onboarding.OnboardingScreen
import app.recomp.ui.routines.RoutineScreen
import app.recomp.ui.settings.SettingsScreen
import app.recomp.ui.setup.SetupScreen
import app.recomp.ui.today.TodayScreen
import app.recomp.ui.train.TrainScreen
import app.recomp.ui.workout.SummaryScreen
import app.recomp.ui.workout.WorkoutScreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.Serializable

@Serializable data object TodayRoute

@Serializable data object TrainRoute

@Serializable data object SettingsRoute

@Serializable data object DevRoute

/** Onboarding (PS-01); with [goalOnly], Settings → Goal runs the same goal flow (PRD §4.6). */
@Serializable data class OnboardingRoute(val goalOnly: Boolean = false)

@Serializable data object SetupRoute

@Serializable data object LibraryRoute

@Serializable data class WorkoutRoute(val sessionId: Long)

@Serializable data class SummaryRoute(val sessionId: Long)

@Serializable data class RoutineRoute(val routineId: Long)

@Serializable data class ExerciseRoute(val exerciseId: Long)

@Serializable data class ExerciseHistoryRoute(val exerciseId: Long)

private data class TopLevel(val label: String, val glyph: String, val route: Any)

private val topLevel = listOf(
    TopLevel("Today", "◉", TodayRoute),
    TopLevel("Train", "▤", TrainRoute),
    TopLevel("Settings", "⚙", SettingsRoute),
)

@Composable
fun RecompNavHost(main: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val prompt by main.prompt.collectAsStateWithLifecycle()
    val onboarded by main.onboarded.collectAsStateWithLifecycle()
    // Decided once: a changing start destination would rebuild the graph and drop the back stack.
    val start: Any = remember(onboarded != null) { if (onboarded == false) OnboardingRoute() else TodayRoute }
    if (onboarded == null) return
    val onTopLevel = topLevel.any { backStack?.destination?.hasRoute(it.route::class) == true }

    LaunchedEffect(Unit) {
        main.nav.collect { event ->
            when (event) {
                is AppNav.Workout -> nav.openWorkout(event.sessionId)
                is AppNav.Summary -> nav.navigate(SummaryRoute(event.sessionId)) { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (onTopLevel) {
                NavigationBar {
                    topLevel.forEach { item ->
                        val selected = backStack?.destination?.hasRoute(item.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(item.glyph) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Consumed so screens with their own Scaffold or inset padding don't add the system bars twice.
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable<OnboardingRoute> { entry ->
                val goalOnly = entry.toRoute<OnboardingRoute>().goalOnly
                OnboardingScreen(
                    onDone = {
                        if (goalOnly) nav.popBackStack()
                        else nav.navigate(TodayRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
                    },
                    onExit = { nav.popBackStack() },
                )
            }
            composable<TodayRoute> {
                TodayScreen(
                    onOpenWorkout = nav::openWorkout,
                    onOpenSummary = { nav.navigate(SummaryRoute(it)) },
                )
            }
            composable<TrainRoute> {
                TrainScreen(
                    onOpenRoutine = { nav.navigate(RoutineRoute(it)) },
                    onOpenLibrary = { nav.navigate(LibraryRoute) },
                    onOpenSetup = { nav.navigate(SetupRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenGoal = { nav.navigate(OnboardingRoute(goalOnly = true)) },
                    onOpenTraining = { nav.navigate(SetupRoute) },
                    onOpenDev = { nav.navigate(DevRoute) },
                )
            }
            composable<DevRoute> { DevScreen() }
            composable<SetupRoute> { SetupScreen(onDone = { nav.popBackStack() }) }
            composable<LibraryRoute> { LibraryScreen(onBack = { nav.popBackStack() }, onOpenExercise = { nav.navigate(ExerciseRoute(it)) }) }
            composable<ExerciseRoute> { ExerciseScreen(onBack = { nav.popBackStack() }, onOpenHistory = { nav.navigate(ExerciseHistoryRoute(it)) }) }
            composable<ExerciseHistoryRoute> { ExerciseHistoryScreen(onBack = { nav.popBackStack() }) }
            composable<RoutineRoute> {
                RoutineScreen(onBack = { nav.popBackStack() }, onOpenExercise = { nav.navigate(ExerciseRoute(it)) })
            }
            composable<WorkoutRoute> {
                WorkoutScreen(
                    onBack = { nav.popBackStack() },
                    onOpenExercise = { nav.navigate(ExerciseRoute(it)) },
                    onOpenHistory = { nav.navigate(ExerciseHistoryRoute(it)) },
                    onFinished = { id ->
                        nav.navigate(SummaryRoute(id)) { popUpTo<TodayRoute>() }
                    },
                    onDiscarded = { nav.backToHome() },
                )
            }
            composable<SummaryRoute> { SummaryScreen(onDone = { nav.backToHome() }) }
        }
    }

    prompt?.let { ResumeDialog(it, onResume = main::resume, onFinish = main::finish, onDiscard = main::discard, onDismiss = main::dismissPrompt) }
}

/** Back to Today, or to onboarding when a workout was resumed before onboarding finished. */
private fun NavHostController.backToHome() {
    if (!popBackStack<TodayRoute>(inclusive = false)) popBackStack<OnboardingRoute>(inclusive = false)
}

private fun NavHostController.openWorkout(sessionId: Long) = navigate(WorkoutRoute(sessionId)) {
    popUpTo<TodayRoute>()
    launchSingleTop = true
}

/** "Resume workout?" (TRD §6.1). After 4 hours it leads with finishing at the last ticked set. */
@Composable
private fun ResumeDialog(prompt: ResumePrompt, onResume: () -> Unit, onFinish: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(time)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prompt.stale) "Finish ${prompt.title}?" else "Resume workout?") },
        text = {
            Text(
                if (prompt.stale) {
                    "${prompt.title} started ${formatDuration(System.currentTimeMillis() - prompt.session.startedAt)} ago with ${prompt.setsDone} sets logged. " +
                        "Finishing ends it at your last set (${at(prompt.lastSetAt)})."
                } else {
                    "${prompt.title}, started ${at(prompt.session.startedAt)} · ${prompt.setsDone} sets logged."
                },
            )
        },
        confirmButton = {
            if (prompt.stale) TextButton(onClick = onFinish) { Text("Finish") } else TextButton(onClick = onResume) { Text("Resume") }
        },
        dismissButton = {
            if (prompt.stale) TextButton(onClick = onResume) { Text("Resume") } else TextButton(onClick = onFinish) { Text("Finish") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        },
    )
}
