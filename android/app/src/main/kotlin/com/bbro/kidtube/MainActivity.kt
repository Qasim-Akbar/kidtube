package com.bbro.kidtube

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bbro.kidtube.ui.nav.AppStartViewModel
import com.bbro.kidtube.ui.nav.KidTubeNavHost
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.AndroidEntryPoint

/** AppCompatActivity (not ComponentActivity) so AppCompatDelegate.setApplicationLocales also works below API 33. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val startViewModel: AppStartViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light theme only: dark system-bar icons on the light ground, whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            KidTubeTheme {
                val start by startViewModel.start.collectAsStateWithLifecycle()
                // The start screen depends on stored state (role, mode, PIN), which is read asynchronously: a blank
                // ground-coloured frame until it is known.
                start?.let { KidTubeNavHost(startDestination = it) }
                    ?: Box(Modifier.fillMaxSize().background(KidTubeTheme.colors.ground))
            }
        }
    }
}
