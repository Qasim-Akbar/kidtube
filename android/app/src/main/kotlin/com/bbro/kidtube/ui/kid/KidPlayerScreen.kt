package com.bbro.kidtube.ui.kid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bbro.kidtube.R
import com.bbro.kidtube.player.KidSessionManager
import com.bbro.kidtube.ui.components.FaceCircle
import com.bbro.kidtube.ui.components.LatinAwareText
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Hands the singleton [KidSessionManager] to composables (app time counts while a kid screen of a child is shown and resumed). */
@HiltViewModel
class KidForegroundViewModel @Inject constructor(val sessions: KidSessionManager) : ViewModel()

/** Reports "a kid screen for [childId] is shown" and the lifecycle (resumed = screen on and in the foreground) to the app-time tracker. */
@Composable
fun KidForegroundEffect(childId: String, viewModel: KidForegroundViewModel = hiltViewModel()) {
    val sessions = viewModel.sessions
    DisposableEffect(childId) {
        sessions.screenEntered(childId)
        onDispose { sessions.screenLeft(childId) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sessions.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { sessions.onBackground() }
}

@Composable
fun KidPlayerScreen(onAllVideos: () -> Unit, viewModel: KidPlayerViewModel = hiltViewModel()) {
    LockPortrait()
    KidForegroundEffect(viewModel.childId)
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.exits.collect { onAllVideos() } }
    LaunchedEffect(state) { if (state is PlayerUiState.Gone) onAllVideos() }
    BackHandler { viewModel.leave() } // flushes tracking first, then the exit above pops to the grid
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onBackground() }

    // The screen stays on while a video plays.
    val view = LocalView.current
    val playing = (state as? PlayerUiState.Ready)?.isPlaying == true
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    PlayerContent(
        state = state,
        onTogglePlay = viewModel::onTogglePlay,
        onAllVideos = viewModel::leave,
        surface = { surface, modifier ->
            when (surface) {
                is PlayerSurface.YouTube -> YouTubeSurface(surface.videoId, viewModel, viewModel::attachBridge, modifier)
                is PlayerSurface.Facebook -> FacebookSurface(surface.pluginUrl, modifier)
            }
        },
    )
}

/**
 * The Player screen of docs/DESIGN.md: video area (16:9), our own big Play/Pause button, a thin progress bar, a large title and one
 * big "All videos" button. No seek bar, no fullscreen, nothing else. [surface] draws the video (a stand-in in tests).
 */
@Composable
fun PlayerContent(
    state: PlayerUiState,
    onTogglePlay: () -> Unit,
    onAllVideos: () -> Unit,
    surface: @Composable (PlayerSurface, Modifier) -> Unit,
) {
    val colors = KidTubeTheme.colors
    when (state) {
        PlayerUiState.Loading, PlayerUiState.Gone -> Box(Modifier.fillMaxSize().background(colors.kidGround))
        is PlayerUiState.Error -> PlayerLayout(title = state.title, onAllVideos = onAllVideos, progress = null, control = null) { CannotPlayTile() }
        is PlayerUiState.Ready -> PlayerLayout(
            title = state.title,
            onAllVideos = onAllVideos,
            progress = if (state.canTogglePlay) state.progress else null,
            control = if (state.canTogglePlay) ({ PlayPauseButton(state.isPlaying, onTogglePlay) }) else null,
        ) { surface(state.surface, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun PlayerLayout(
    title: String?,
    onAllVideos: () -> Unit,
    progress: Float?,
    control: (@Composable () -> Unit)?,
    video: @Composable () -> Unit,
) {
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.kidGround) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(KidTubeDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(KidTubeShapes.kidTile).background(colors.ink),
            ) { video() }
            if (progress != null) ProgressLine(progress)
            if (control != null) control()
            if (title != null) {
                LatinAwareText(title, style = MaterialTheme.typography.headlineMedium, color = colors.ink, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f))
            PrimaryButton(text = stringResource(R.string.kid_player_all_videos), onClick = onAllVideos, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A thin, non-interactive progress bar (no seeking). */
@Composable
private fun ProgressLine(progress: Float) {
    val colors = KidTubeTheme.colors
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.card)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(CircleShape).background(colors.primary))
    }
}

/** The big round Play/Pause button: the icon shows what pressing does. */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val colors = KidTubeTheme.colors
    val label = stringResource(if (isPlaying) R.string.kid_player_pause else R.string.kid_player_play)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(colors.ink)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(36.dp)) {
            val w = size.width
            if (isPlaying) {
                val bar = Size(w * 0.28f, w)
                drawRoundRect(Color.White, Offset(w * 0.06f, 0f), bar, CornerRadius(w * 0.08f))
                drawRoundRect(Color.White, Offset(w * 0.66f, 0f), bar, CornerRadius(w * 0.08f))
            } else {
                // The play triangle points the reading direction (mirrored in the Urdu layouts, as in the mockups).
                val path = Path().apply {
                    if (rtl) {
                        moveTo(w * 0.9f, 0f); lineTo(w * 0.9f, w); lineTo(w * 0.1f, w / 2)
                    } else {
                        moveTo(w * 0.1f, 0f); lineTo(w * 0.1f, w); lineTo(w * 0.9f, w / 2)
                    }
                    close()
                }
                drawPath(path, Color.White)
            }
        }
    }
}

/** A friendly tile instead of a broken player: the video cannot play. The way back is the "All videos" button below it. */
@Composable
private fun CannotPlayTile() {
    val colors = KidTubeTheme.colors
    Box(Modifier.fillMaxSize().background(colors.thumbnailPastels[0]), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8)) {
            FaceCircle(colors.sun, size = 64.dp, happy = false)
            Text(
                stringResource(R.string.kid_player_cannot_play),
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}
