package de.codevoid.wmsproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.codevoid.wmsproxy.ui.AppRoot
import de.codevoid.wmsproxy.ui.WmsProxyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WmsProxyTheme {
                AppRoot()
            }
        }
    }
}
