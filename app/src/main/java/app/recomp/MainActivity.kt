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
        setContent {
            RecompTheme {
                RecompNavHost(mainViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_WORKOUT) mainViewModel.openActive()
    }

    companion object {
        /** The workout notification opens the active workout directly. */
        const val ACTION_OPEN_WORKOUT = "app.recomp.action.OPEN_WORKOUT"
    }
}
