package com.bbro.kidtube.ui.kid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.R
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.FeedRepository
import com.bbro.kidtube.ui.children.AvatarColor
import com.bbro.kidtube.ui.components.ClockIcon
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.FaceCircle
import com.bbro.kidtube.ui.components.KidAvatar
import com.bbro.kidtube.ui.components.KidTile
import com.bbro.kidtube.ui.components.LatinAwareText
import com.bbro.kidtube.ui.components.LeftTodayDots
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.components.TimeLeftBar
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One child's kid grid / status screen, driven by [FeedRepository.observe]. The repository re-evaluates when the snapshot or
 * the local events change and when the child's local date changes (it polls the injected Clock every [dayCheckInterval]),
 * so the screen moves on at local midnight by itself.
 */
@HiltViewModel
class KidGridViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    feeds: FeedRepository,
    children: ChildrenRepository,
    dayCheckInterval: Duration,
) : ViewModel() {
    @Inject
    constructor(savedStateHandle: SavedStateHandle, feeds: FeedRepository, children: ChildrenRepository) :
        this(savedStateHandle, feeds, children, DEFAULT_DAY_CHECK)

    val childId: String = checkNotNull(savedStateHandle[ARG_CHILD_ID]) { "missing $ARG_CHILD_ID" }

    val state: StateFlow<KidScreenState> = combine(
        children.observeChildren().map { list -> list.firstOrNull { it.id == childId } },
        feeds.observe(childId, projectionDays = 0, dayCheckInterval = dayCheckInterval),
    ) { child, feed -> KidScreenModel.map(child, feed) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KidScreenState.Loading)

    companion object {
        const val ARG_CHILD_ID = "childId"
        val DEFAULT_DAY_CHECK: Duration = 30.seconds
    }
}

@Composable
fun KidGridScreen(
    onOpenVideo: (itemId: String) -> Unit,
    onSwitchKid: () -> Unit,
    viewModel: KidGridViewModel = hiltViewModel(),
) {
    LockPortrait()
    KidForegroundEffect(viewModel.childId)
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The child was removed by a pull: nothing to show for them, back to "Who is watching?".
    LaunchedEffect(state) { if (state is KidScreenState.Gone) onSwitchKid() }
    KidGridContent(state, onOpenVideo, onSwitchKid)
}

@Composable
fun KidGridContent(state: KidScreenState, onOpenVideo: (String) -> Unit, onSwitchKid: () -> Unit) {
    val colors = KidTubeTheme.colors
    when (state) {
        KidScreenState.Loading, KidScreenState.Gone -> Box(Modifier.fillMaxSize().background(colors.kidGround))
        is KidScreenState.Grid -> GridBody(state, onOpenVideo)
        is KidScreenState.Status -> StatusBody(state, onSwitchKid)
    }
}

@Composable
private fun GridBody(state: KidScreenState.Grid, onOpenVideo: (String) -> Unit) {
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.kidGround) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(KidTubeDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12)) {
                KidAvatar(state.child.avatar, size = 56.dp)
                LatinAwareText(state.child.name, style = MaterialTheme.typography.headlineMedium, color = colors.ink, modifier = Modifier.weight(1f))
                if (state.dotsTotal != null) LeftTodayDots(total = state.dotsTotal, left = state.dotsLeft)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
                verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16),
            ) {
                state.first?.let { first ->
                    item(key = "first-${first.itemId}", span = { GridItemSpan(maxLineSpan) }) {
                        KidTile(
                            first.title, first.thumbnailUrl, first.seed, first.viewedToday, onClick = { onOpenVideo(first.itemId) },
                            isFirst = true, modifier = Modifier.fillMaxWidth().height(FIRST_TILE_HEIGHT),
                        )
                    }
                }
                items(state.tiles, key = { it.itemId }) { tile ->
                    KidTile(
                        tile.title, tile.thumbnailUrl, tile.seed, tile.viewedToday, onClick = { onOpenVideo(tile.itemId) },
                        modifier = Modifier.fillMaxWidth().height(TILE_HEIGHT),
                    )
                }
            }
            state.timeLeft?.let { TimeLeftBar(it.secondsLeft, it.limitSeconds) }
        }
    }
}

private val TILE_HEIGHT = KidTubeDimens.kidTileMinHeight + 10.dp // never below the 150dp minimum
private val FIRST_TILE_HEIGHT = 220.dp

@Composable
private fun StatusBody(state: KidScreenState.Status, onSwitchKid: () -> Unit) {
    val colors = KidTubeTheme.colors
    val name = state.child.name
    val background: Color = when (state.kind) {
        StatusKind.DONE, StatusKind.DAY_OFF -> colors.thumbnailPastels[2] // mint
        StatusKind.TIME_UP -> colors.thumbnailPastels[1] // blue
        StatusKind.NOTHING -> colors.kidGround
    }
    val title = stringResource(
        when (state.kind) {
            StatusKind.DONE -> R.string.kid_done_title
            StatusKind.TIME_UP -> R.string.kid_time_up_title
            StatusKind.DAY_OFF -> R.string.kid_day_off_title
            StatusKind.NOTHING -> R.string.kid_empty_title
        },
    )
    val subtitle = when (state.kind) {
        StatusKind.DONE -> stringResource(R.string.kid_done_subtitle, name)
        StatusKind.TIME_UP -> stringResource(R.string.kid_time_up_subtitle, name)
        StatusKind.DAY_OFF -> stringResource(R.string.kid_day_off_subtitle, name)
        StatusKind.NOTHING -> stringResource(R.string.kid_empty_subtitle)
    }
    Scaffold(containerColor = background) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(KidTubeDimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap20, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.kind) {
                    StatusKind.DONE, StatusKind.DAY_OFF -> FaceCircle(colors.sun, size = 160.dp)
                    StatusKind.TIME_UP -> ClockIcon(size = 160.dp)
                    StatusKind.NOTHING -> KidAvatar(state.child.avatar, size = 140.dp)
                }
                LatinAwareText(title, style = MaterialTheme.typography.displayLarge, color = colors.ink, textAlign = TextAlign.Center)
                LatinAwareText(subtitle, style = MaterialTheme.typography.titleLarge, color = colors.ink, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(KidTubeDimens.gap16))
            PrimaryButton(text = stringResource(R.string.kid_switch), onClick = onSwitchKid, modifier = Modifier.fillMaxWidth())
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun KidStatusPreview() {
    PreviewSurface {
        Box(Modifier.height(560.dp)) {
            StatusBody(KidScreenState.Status(KidChild("1", "Ali", AvatarColor.CORAL), StatusKind.DONE), onSwitchKid = {})
        }
    }
}
