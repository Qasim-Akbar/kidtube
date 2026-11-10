package com.bbro.kidtube

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bbro.kidtube.ui.nav.KidTubeNavHost
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.AndroidEntryPoint

/** AppCompatActivity (not ComponentActivity) so AppCompatDelegate.setApplicationLocales also works below API 33. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light theme only: dark system-bar icons on the light ground, whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            KidTubeTheme {
                KidTubeNavHost()
            }
        }
    }
}
