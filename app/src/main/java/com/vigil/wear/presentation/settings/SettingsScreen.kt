package com.vigil.wear.presentation.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.vigil.wear.R
import com.vigil.wear.session.SessionViewModel

@Composable
fun SettingsScreen(
    viewModel: SessionViewModel,
    onNavigateBack: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                EdgeButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.nav_back),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        ) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                item {
                    ListHeader {
                        Text(stringResource(R.string.settings_title))
                    }
                }
                item {
                    Button(
                        onClick = { viewModel.testBuzz() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Vibration,
                                contentDescription = stringResource(R.string.content_desc_test_buzz),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.home_test_buzz))
                    }
                }
                item {
                    SwitchButton(
                        checked = ui.hapticsEnabled,
                        onCheckedChange = { viewModel.setHapticsEnabled(it) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Vibration,
                                    contentDescription = stringResource(R.string.content_desc_haptics),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.home_haptics_enabled),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
