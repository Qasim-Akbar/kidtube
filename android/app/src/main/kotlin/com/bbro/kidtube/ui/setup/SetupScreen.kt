package com.bbro.kidtube.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bbro.kidtube.R
import com.bbro.kidtube.core.AppLanguage
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.ui.components.BigChoiceCard
import com.bbro.kidtube.ui.components.EnglishAndUrduPreviews
import com.bbro.kidtube.ui.components.KidTubeLogo
import com.bbro.kidtube.ui.components.LanguageToggle
import com.bbro.kidtube.ui.components.PreviewSurface
import com.bbro.kidtube.ui.theme.KidTubeDimens
import com.bbro.kidtube.ui.theme.KidTubeTheme
import com.bbro.kidtube.ui.theme.isUrduLocale

@Composable
fun SetupScreen(
    onContinue: (DeviceRole) -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    // The language in effect is whatever locale the activity is running in; it changes (via recreate) after a pick.
    val language = if (isUrduLocale()) AppLanguage.URDU else AppLanguage.ENGLISH
    SetupContent(
        language = language,
        onLanguageSelected = viewModel::onLanguageSelected,
        onRoleSelected = onContinue,
    )
}

@Composable
internal fun SetupContent(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onRoleSelected: (DeviceRole) -> Unit,
) {
    val colors = KidTubeTheme.colors
    Scaffold(containerColor = colors.ground) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(KidTubeDimens.screenPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KidTubeLogo(Modifier.weight(1f))
                LanguageToggle(selected = language, onSelected = onLanguageSelected)
            }
            Spacer(Modifier.height(48.dp))
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(KidTubeDimens.gap24))
            Column(verticalArrangement = Arrangement.spacedBy(KidTubeDimens.gap16)) {
                BigChoiceCard(
                    title = stringResource(R.string.setup_parent_title),
                    description = stringResource(R.string.setup_parent_desc),
                    icon = Icons.Filled.Person,
                    iconBackground = colors.primarySoft,
                    onClick = { onRoleSelected(DeviceRole.PARENT) },
                )
                BigChoiceCard(
                    title = stringResource(R.string.setup_kid_title),
                    description = stringResource(R.string.setup_kid_desc),
                    icon = Icons.Filled.Face,
                    iconBackground = colors.sun,
                    onClick = { onRoleSelected(DeviceRole.KID) },
                )
            }
        }
    }
}

@EnglishAndUrduPreviews
@Composable
private fun SetupContentPreview() {
    PreviewSurface {
        SetupContent(
            language = if (isUrduLocale()) AppLanguage.URDU else AppLanguage.ENGLISH,
            onLanguageSelected = {},
            onRoleSelected = {},
        )
    }
}
