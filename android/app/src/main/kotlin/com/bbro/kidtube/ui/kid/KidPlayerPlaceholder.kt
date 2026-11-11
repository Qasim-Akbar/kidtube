package com.bbro.kidtube.ui.kid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bbro.kidtube.R
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme

/**
 * Stand-in for the real player (K3 replaces it, same route). The only way out is the "All videos" button or system back,
 * both of which return to the grid. It knows the child and plan item so K3 can start without changing navigation.
 */
@Composable
fun KidPlayerPlaceholder(onAllVideos: () -> Unit) {
    LockPortrait()
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.kidGround) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(KidTubeDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.kid_player_placeholder), style = MaterialTheme.typography.displayLarge, color = colors.ink, modifier = Modifier.weight(1f))
            PrimaryButton(text = stringResource(R.string.kid_player_all_videos), onClick = onAllVideos, modifier = Modifier.fillMaxWidth())
        }
    }
}
