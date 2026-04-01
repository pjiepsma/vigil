package com.vigil.wear.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.vigil.wear.R
import com.vigil.wear.session.SessionViewModel
import com.vigil.wear.session.VigilMode

/**
 * Wear Material 3–style landing: headline + mode picker in the scroll region; settings on the
 * curved [EdgeButton] (system-aligned, centered on the bottom of the round display).
 * The app mark lives in the launcher icon only — not repeated here.
 */
@Composable
fun HomeScreen(
    viewModel: SessionViewModel,
    onModeChosen: (VigilMode) -> Unit,
    onOpenActiveSession: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    val transformationSpec = rememberTransformationSpec()
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                EdgeButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.content_desc_settings),
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        ) { contentPadding ->
            TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                item {
                    ListHeader(
                        modifier =
                            Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.home_title),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = stringResource(R.string.home_pick_mode),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                if (ui.isSessionRunning) {
                    item {
                        ListHeader {
                            Text(stringResource(R.string.home_session_running))
                        }
                    }
                    item {
                        Button(
                            onClick = onOpenActiveSession,
                            modifier = Modifier.fillMaxWidth(),
                            icon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = stringResource(R.string.content_desc_open_session),
                                )
                            },
                        ) {
                            Text(
                                stringResource(R.string.home_open_session),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { viewModel.endSession() },
                            modifier = Modifier.fillMaxWidth(),
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.StopCircle,
                                    contentDescription = stringResource(R.string.content_desc_end_session),
                                )
                            },
                        ) {
                            Text(
                                stringResource(R.string.home_end_session),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                item {
                    ModeCapsuleRow(
                        onChooseMode = onModeChosen,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeCapsuleRow(
    onChooseMode: (VigilMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        ModeCapsule(
            onClick = { onChooseMode(VigilMode.Passive) },
            icon = Icons.Rounded.Person,
            contentDescription = stringResource(R.string.content_desc_mode_passive),
        )
        ModeCapsule(
            onClick = { onChooseMode(VigilMode.Active) },
            icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
            contentDescription = stringResource(R.string.content_desc_mode_active),
        )
    }
}

@Composable
private fun ModeCapsule(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
) {
    val width = 42.dp
    val height = 80.dp
    val iconSize = 28.dp
    Box(
        modifier =
            Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
