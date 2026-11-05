package com.bbro.kidtube

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bbro.kidtube.ui.nav.KidTubeNavHost
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KidTubeTheme {
                KidTubeNavHost()
            }
        }
    }
}
