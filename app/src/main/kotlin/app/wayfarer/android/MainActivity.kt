package app.wayfarer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.wayfarer.android.ui.LoadingScreen
import app.wayfarer.android.ui.WayfarerApp
import app.wayfarer.android.ui.theme.WayfarerTheme
import app.wayfarer.core.Wayfarer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val wayfarerApp = application as WayfarerApplication

        setContent {
            WayfarerTheme {
                val wayfarer by produceState<Wayfarer?>(initialValue = null) { value = wayfarerApp.wayfarer() }

                when (val ready = wayfarer) {
                    null -> LoadingScreen("Starting up…")
                    else -> WayfarerApp(ready, wayfarerApp.scope)
                }
            }
        }
    }
}
