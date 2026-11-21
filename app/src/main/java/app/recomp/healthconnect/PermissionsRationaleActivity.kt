package app.recomp.healthconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.ui.theme.RecompTheme

/** Shown by Health Connect when you open Recomp's privacy information from its permission screen. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecompTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("How Recomp uses health data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Recomp reads only what you turn on in Settings → Health Connect: your daily steps, your sleep, " +
                                "workouts other apps recorded, and the InBody app's weight, body fat and BMR. It writes nothing " +
                                "to Health Connect. What it reads stays on this phone and is never uploaded.",
                        )
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
