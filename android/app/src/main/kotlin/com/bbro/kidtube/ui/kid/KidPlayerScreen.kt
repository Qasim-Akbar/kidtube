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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
import com.bbro.kidtube.ui.components.BigWhiteButton
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
    // Foreground/background comes from the process lifecycle (KidTubeApp binds it), not from this nav entry's lifecycle.
}

@Composable
fun KidPlayerScreen(onAllVideos: () -> Unit, viewModel: KidPlayerViewModel = hiltViewModel()) {
    LockPortrait()
    KidForegroundEffect(viewModel.childId)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val view = LocalView.current

    LaunchedEffect(viewModel) { viewModel.exits.collect { onAllVideos() } }
    LaunchedEffect(state) { if (state is PlayerUiState.Gone) onAllVideos() }
    BackHandler { viewModel.leave() } // flushes tracking first, then the exit above pops to the grid
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onBackground() }

    // The video strip is dark (behind the status bar too): light status-bar icons while this screen is shown.
    val window = view.context.findActivity()?.window
    DisposableEffect(window) {
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
    // The screen stays on while a video plays.
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
            progress = if (state.canTogglePlay && !state.covered) state.progress else null,
            control = if (state.canTogglePlay && !state.covered) ({ PlayPauseButton(state.isPlaying, onTogglePlay) }) else null,
            covered = state.covered,
        ) { surface(state.surface, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun PlayerLayout(
    title: String?,
    onAllVideos: () -> Unit,
    progress: Float?,
    control: (@Composable () -> Unit)?,
    covered: Boolean = false,
    video: @Composable () -> Unit,
) {
    val colors = KidTubeTheme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.kidGround)) {
        // A dark strip (it also sits behind the status bar) with the full-width 16:9 video area in it.
        Box(Modifier.fillMaxWidth().background(colors.ink).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                video()
                if (covered) VideoCover(Modifier.matchParentSize())
                if (control != null) Box(Modifier.align(Alignment.Center)) { control() }
                if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(KidTubeDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
        ) {
            if (title != null) {
                LatinAwareText(title, style = MaterialTheme.typography.displaySmall, color = colors.ink, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f))
            BigWhiteButton(text = stringResource(R.string.kid_player_all_videos), onClick = onAllVideos, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Plain pastel with a small spinner over the embed until it really plays (its own chrome flashes while loading). Swallows touches. */
@Composable
private fun VideoCover(modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    Box(
        modifier.background(colors.thumbnailPastels[0]).pointerInput(Unit) {},
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(32.dp).testTag("player_cover_spinner"), color = colors.ink, strokeWidth = 3.dp)
    }
}

/** A thin yellow progress line along the bottom edge of the video area (not interactive: no seeking). */
@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val colors = KidTubeTheme.colors
    Box(modifier.fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(colors.sun))
    }
}

/** The big round white Play/Pause button over the video area: the icon shows what pressing does. */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val colors = KidTubeTheme.colors
    val label = stringResource(if (isPlaying) R.string.kid_player_pause else R.string.kid_player_play)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val w = size.width
            if (isPlaying) {
                val bar = Size(w * 0.28f, w)
                drawRoundRect(colors.ink, Offset(w * 0.06f, 0f), bar, CornerRadius(w * 0.08f))
                drawRoundRect(colors.ink, Offset(w * 0.66f, 0f), bar, CornerRadius(w * 0.08f))
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
                drawPath(path, colors.ink)
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
