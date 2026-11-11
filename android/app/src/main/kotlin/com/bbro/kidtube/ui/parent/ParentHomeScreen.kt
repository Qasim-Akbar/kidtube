package com.bbro.kidtube.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bbro.kidtube.R
import com.bbro.kidtube.share.SharePreset
import com.bbro.kidtube.ui.children.AddChildDialog
import com.bbro.kidtube.ui.components.BadgeStatus
import com.bbro.kidtube.ui.components.Chip
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.components.PrimaryButton
import com.bbro.kidtube.ui.components.VideoRow
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeShapes
import com.bbro.kidtube.ui.theme.KidTubeTheme

/** Test handle for the video list. */
internal const val LIBRARY_LIST_TAG = "library_list"

private enum class HomeTab(val label: Int, val icon: ImageVector) {
    LIBRARY(R.string.nav_library, Icons.Filled.PlayArrow),
    PLANS(R.string.nav_plans, Icons.Filled.DateRange),
    REPORTS(R.string.nav_reports, Icons.Filled.Info),
}

/** Parent Home: the Library tab (screen 3 in docs/DESIGN.md) with Plans and Reports still "coming soon". */
@Composable
fun ParentHomeScreen(
    onKidMode: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ParentHomeViewModel = hiltViewModel(),
) {
    val state = viewModel.library.collectAsStateWithLifecycle().value
    var tab by rememberSaveable { mutableIntStateOf(HomeTab.LIBRARY.ordinal) }
    var addingChild by remember { mutableStateOf(false) }
    val colors = KidTubeTheme.colors

    Scaffold(
        containerColor = colors.ground,
        bottomBar = {
            NavigationBar(containerColor = colors.card) {
                HomeTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry.ordinal,
                        onClick = { tab = entry.ordinal },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.label), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.onPrimary,
                            selectedTextColor = colors.ink,
                            indicatorColor = colors.primary,
                            unselectedIconColor = colors.muted,
                            unselectedTextColor = colors.muted,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (HomeTab.entries[tab]) {
                HomeTab.LIBRARY -> LibraryContent(
                    state = state,
                    onSelectChild = viewModel::selectChild,
                    onRefresh = viewModel::refresh,
                    onAddChild = { addingChild = true },
                    onKidMode = { viewModel.enterKidMode(onKidMode) },
                    onSettings = onSettings,
                )
                HomeTab.PLANS -> com.bbro.kidtube.ui.plans.PlansTab(onAddChild = { addingChild = true })
                HomeTab.REPORTS -> ComingSoon(R.string.nav_reports)
            }
        }
    }
    if (addingChild) AddChildDialog(onDismiss = { addingChild = false })
}

/** The Library tab. Stateless so it can be rendered in tests and previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryContent(
    state: LibraryUiState,
    onSelectChild: (String?) -> Unit,
    onRefresh: () -> Unit,
    onAddChild: () -> Unit,
    onKidMode: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = KidTubeTheme.colors
    val listState = rememberLazyListState()
    // A LazyColumn with stable keys keeps the row that was first anchored where it is, so a row inserted ABOVE it (a newly
    // shared video, its "Waiting to send" row) would land off-screen above the viewport. If the parent was looking at the
    // top of the list before this update, stay at the top. Read here (during composition) the value is still the one from
    // before the new data is measured; a parent who scrolled down to read is left where they are.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    val stickToTop = atTop
    LaunchedEffect(state.items.firstOrNull()?.id) { if (stickToTop) listState.scrollToItem(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = KidTubeDimens.screenPadding, end = KidTubeDimens.gap8, top = KidTubeDimens.gap16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8),
        ) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Surface(
                onClick = onKidMode,
                shape = KidTubeShapes.chip,
                color = colors.ink,
                contentColor = colors.onPrimary,
            ) {
                Text(
                    text = stringResource(R.string.kid_mode_button),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(KidTubeDimens.minTouchTarget)) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title), tint = colors.ink)
            }
        }

        if (state.children.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = KidTubeDimens.screenPadding, vertical = KidTubeDimens.gap12),
                horizontalArrangement = Arrangement.spacedBy(KidTubeDimens.gap8),
            ) {
                Chip(
                    text = stringResource(R.string.library_filter_all),
                    selected = state.selectedChildId == null,
                    onClick = { onSelectChild(null) },
                    role = Role.RadioButton,
                )
                state.children.forEach { child ->
                    Chip(
                        text = child.name,
                        selected = state.selectedChildId == child.id,
                        onClick = { onSelectChild(child.id) },
                        role = Role.RadioButton,
                    )
                }
            }
        }

        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.items.isNotEmpty() -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = KidTubeDimens.screenPadding, vertical = KidTubeDimens.gap8),
                    verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12),
                    modifier = Modifier.fillMaxSize().testTag(LIBRARY_LIST_TAG),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        VideoRow(
                            title = item.title,
                            status = item.status,
                            thumbnailUrl = item.thumbnailUrl,
                            seed = item.id,
                            detail = detailText(item),
                            note = noteText(item.note),
                            extraNote = if (item.facebook) stringResource(R.string.facebook_parents_only) else null,
                        )
                    }
                }
                state.loaded -> EmptyLibrary(state, onAddChild)
            }
        }
    }
}

@Composable
private fun detailText(item: LibraryItem): String {
    if (item.assignments.isEmpty()) return stringResource(R.string.preset_library_only)
    val custom = stringResource(R.string.preset_custom)
    val labels = SharePreset.entries.associateWith { stringResource(it.label) }
    return item.assignments.joinToString(", ") { a -> "${a.childName} · ${a.preset?.let(labels::getValue) ?: custom}" }
}

@Composable
private fun noteText(note: LibraryNote?): String? = when (note) {
    LibraryNote.WAITING_TO_SEND -> stringResource(R.string.library_waiting_to_send)
    LibraryNote.WAITING_FOR_PLAN -> stringResource(R.string.library_waiting_for_plan)
    LibraryNote.NOT_ADDED -> stringResource(R.string.library_not_added)
    null -> null
}

/** Nothing to list: either the library is empty (how to share a first video) or the child filter matches nothing. */
@Composable
private fun EmptyLibrary(state: LibraryUiState, onAddChild: () -> Unit) {
    val colors = KidTubeTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(KidTubeDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.totalItems > 0) {
            Text(stringResource(R.string.library_empty_filtered), style = MaterialTheme.typography.bodyLarge, color = colors.muted, textAlign = TextAlign.Center)
            return@Column
        }
        Surface(shape = CircleShape, color = colors.primarySoft, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = colors.verifiedText, modifier = Modifier.size(40.dp))
            }
        }
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineSmall, color = colors.ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.library_empty_body), style = MaterialTheme.typography.bodyLarge, color = colors.muted, textAlign = TextAlign.Center)
        if (state.children.isEmpty()) {
            Text(stringResource(R.string.library_empty_no_children), style = MaterialTheme.typography.bodyMedium, color = colors.muted, textAlign = TextAlign.Center)
            PrimaryButton(text = stringResource(R.string.add_child_action), onClick = onAddChild)
        }
    }
}

@Composable
private fun ComingSoon(title: Int) {
    val colors = KidTubeTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(KidTubeDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap12, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, color = colors.ink)
        Text(stringResource(R.string.coming_soon), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
    }
}

@EnglishAndUrduPreviews
@Composable
private fun LibraryPreview() {
    PreviewSurface {
        LibraryContent(
            state = LibraryUiState(
                loaded = true,
                children = listOf(com.bbro.kidtube.data.entity.ChildEntity("1", "Ali", null, null)),
                items = listOf(
                    LibraryItem("1", "Counting to ten", null, BadgeStatus.VERIFIED, listOf(Assignment("1", "Ali", com.bbro.kidtube.share.SharePreset.ONCE_A_DAY))),
                    LibraryItem("2", "youtu.be/abc", null, BadgeStatus.PENDING, emptyList(), LibraryNote.WAITING_TO_SEND, fromOutbox = true),
                ),
                totalItems = 2,
            ),
            onSelectChild = {}, onRefresh = {}, onAddChild = {}, onKidMode = {}, onSettings = {},
        )
    }
}
