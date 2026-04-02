package com.vigil.wear.presentation.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.Text
import com.vigil.wear.R
import com.vigil.wear.session.SessionViewModel
import com.vigil.wear.session.VigilMode
import java.time.ZoneId
import java.time.ZonedDateTime

private sealed class SessionGoal {
    data object Open : SessionGoal()

    data class Minutes(val minutes: Int) : SessionGoal()

    data class EndAtWallClock(
        val hour: Int,
        val minute: Int,
    ) : SessionGoal()
}

private fun wallClockToEndEpochMs(
    hour: Int,
    minute: Int,
): Long {
    val zone = ZoneId.systemDefault()
    var zdt =
        ZonedDateTime.now(zone)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
    if (!zdt.isAfter(ZonedDateTime.now(zone).plusMinutes(1))) {
        zdt = zdt.plusDays(1)
    }
    return zdt.toInstant().toEpochMilli()
}

@Composable
fun SessionSetupScreen(
    mode: VigilMode,
    viewModel: SessionViewModel,
    onStarted: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    var goal by remember { mutableStateOf<SessionGoal>(SessionGoal.Open) }
    var endHour by remember { mutableIntStateOf(20) }
    var endMinute by remember { mutableIntStateOf(0) }

    val modeLabel =
        when (mode) {
            VigilMode.Passive -> stringResource(R.string.mode_passive)
            VigilMode.Active -> stringResource(R.string.mode_active)
        }

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
                        Text(
                            text = stringResource(R.string.setup_title),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Text(
                        text = modeLabel,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(
                        text =
                            if (ui.hasRequiredPermissions) {
                                stringResource(R.string.permissions_ready)
                            } else {
                                stringResource(R.string.permissions_session_required)
                            },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                if (!ui.hasRequiredPermissions && ui.missingPermissions.isNotEmpty()) {
                    item {
                        Text(
                            text =
                                stringResource(
                                    R.string.permissions_missing_format,
                                    ui.missingPermissions.joinToString(),
                                ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (ui.optionalPermissions.isNotEmpty()) {
                    item {
                        Text(
                            text =
                                stringResource(
                                    R.string.permissions_optional_format,
                                    ui.optionalPermissions.joinToString(),
                                ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item {
                    ListHeader { Text(stringResource(R.string.setup_goal_label)) }
                }
                item {
                    Button(
                        onClick = { goal = SessionGoal.Open },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.setup_duration_open),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item {
                    Button(
                        onClick = { goal = SessionGoal.Minutes(15) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_duration_15))
                    }
                }
                item {
                    Button(
                        onClick = { goal = SessionGoal.Minutes(30) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_duration_30))
                    }
                }
                item {
                    Button(
                        onClick = { goal = SessionGoal.Minutes(45) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_duration_45))
                    }
                }
                item {
                    Button(
                        onClick = { goal = SessionGoal.Minutes(60) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_duration_60))
                    }
                }
                item {
                    Button(
                        onClick = {
                            goal =
                                SessionGoal.EndAtWallClock(
                                    hour = endHour,
                                    minute = endMinute,
                                )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_end_at))
                    }
                }
                if (goal is SessionGoal.EndAtWallClock) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Text(
                                stringResource(R.string.setup_end_at),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Stepper(
                                value = endHour,
                                onValueChange = { h ->
                                    endHour = h
                                    goal = SessionGoal.EndAtWallClock(hour = h, minute = endMinute)
                                },
                                valueProgression = 0..23,
                                decreaseIcon = {
                                    Icon(Icons.Rounded.Remove, contentDescription = null)
                                },
                                increaseIcon = {
                                    Icon(Icons.Rounded.Add, contentDescription = null)
                                },
                            ) {
                                Text("$endHour h")
                            }
                            Stepper(
                                value = endMinute,
                                onValueChange = { m ->
                                    endMinute = m
                                    goal = SessionGoal.EndAtWallClock(hour = endHour, minute = m)
                                },
                                valueProgression = 0..59,
                                decreaseIcon = {
                                    Icon(Icons.Rounded.Remove, contentDescription = null)
                                },
                                increaseIcon = {
                                    Icon(Icons.Rounded.Add, contentDescription = null)
                                },
                            ) {
                                Text("$endMinute min")
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            goal = SessionGoal.Open
                            endHour = 20
                            endMinute = 0
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.setup_clear_end_time),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            val targetEnd =
                                when (val g = goal) {
                                    SessionGoal.Open -> null
                                    is SessionGoal.Minutes ->
                                        System.currentTimeMillis() + g.minutes * 60_000L
                                    is SessionGoal.EndAtWallClock ->
                                        wallClockToEndEpochMs(g.hour, g.minute)
                                }
                            if (viewModel.startSession(mode, targetEnd)) {
                                onStarted()
                            }
                        },
                        enabled = ui.hasRequiredPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.content_desc_start_session),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.setup_start))
                    }
                }
            }
        }
    }
}
