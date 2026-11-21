package app.recomp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.recomp.ui.MainViewModel
import app.recomp.ui.navigation.RecompNavHost
import app.recomp.ui.theme.RecompTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        mainViewModel.onLaunch(openWorkout = savedInstanceState == null && intent?.action == ACTION_OPEN_WORKOUT)
        if (savedInstanceState == null) mainViewModel.open(intent?.action)
        setContent {
            RecompTheme {
                RecompNavHost(mainViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_WORKOUT) mainViewModel.openActive() else mainViewModel.open(intent.action)
    }

    companion object {
        /** The workout notification opens the active workout directly. */
        const val ACTION_OPEN_WORKOUT = "app.recomp.action.OPEN_WORKOUT"

        /** Where a reminder or the widget opens the app (PRD §12.8). */
        const val ACTION_OPEN_TODAY = "app.recomp.action.OPEN_TODAY"
        const val ACTION_OPEN_FOOD = "app.recomp.action.OPEN_FOOD"
        const val ACTION_OPEN_BODY = "app.recomp.action.OPEN_BODY"
        const val ACTION_OPEN_CHECKIN = "app.recomp.action.OPEN_CHECKIN"
    }
}
