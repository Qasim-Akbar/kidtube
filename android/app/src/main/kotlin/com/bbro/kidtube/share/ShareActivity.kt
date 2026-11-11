package com.bbro.kidtube.share

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * "Share to KidTube": the target of ACTION_SEND text/plain (a YouTube link, or a whole WhatsApp message containing one).
 * Listed only on parent phones: the manifest ships it disabled and [ShareTargetManager] enables it for the PARENT role.
 * It has its own task (empty affinity), so finishing it returns to the app the link was shared from. Everything works
 * offline: the link is saved to the local outbox and synced later.
 */
@AndroidEntryPoint
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        setContent {
            KidTubeTheme {
                ShareScreen(sharedText = sharedText, onClose = { finish() })
            }
        }
    }
}
