package com.vigil.wear.presentation.session

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.vigil.wear.metrics.MetricReading
import com.vigil.wear.metrics.MetricStatus
import com.vigil.wear.session.SessionViewModel
import kotlinx.coroutines.delay

@Composable
fun ActiveSessionScreen(
    viewModel: SessionViewModel,
    onEndSession: () -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    if (ui.isStartingSession) {
        AppScaffold {
            ScreenScaffold {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Starting session…", textAlign = TextAlign.Center)
                }
            }
        }
        return
    }
    if (!ui.isSessionRunning) {
        LaunchedEffect(Unit) { onEndSession() }
        return
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            viewModel.syncFromServiceState()
        }
    }

    val metrics = ui.metrics
    val startIndex = metrics.indexOfFirst { it.metricId == "heart_rate" }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { metrics.size.coerceAtLeast(1) }

    AppScaffold {
        ScreenScaffold {
            Box(modifier = Modifier.fillMaxSize()) {
                if (metrics.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        MetricPage(
                            metric = metrics[page],
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Waiting for metrics…")
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ui.metricsSnapshot.alertReason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB74D),
                        )
                    }
                    RowControls(
                        paused = ui.isPaused,
                        onPauseResume = {
                            if (ui.isPaused) viewModel.resumeSession() else viewModel.pauseSession()
                        },
                        onEnd = onEndSession,
                    )
                }

                if (ui.alertRingVisible) {
                    BlinkingAlertRing(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun MetricPage(metric: MetricReading, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = metric.provider.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = metric.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = metric.value,
            style = MaterialTheme.typography.displaySmall,
            color = statusColor(metric.status),
            modifier = Modifier.padding(top = 4.dp),
        )
        if (metric.unit.isNotBlank()) {
            Text(text = metric.unit, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = metric.serviceName,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = metric.sourceKey,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (metric.classifierContribution.isNotBlank()) {
            Text(
                text = metric.classifierContribution,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (metric.availability.isNotBlank()) {
            Text(
                text = metric.availability,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RowControls(
    paused: Boolean,
    onPauseResume: () -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPauseResume, modifier = Modifier.size(46.dp)) {
            Icon(
                imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
            )
        }
        Button(onClick = onEnd, modifier = Modifier.size(46.dp)) {
            Icon(imageVector = Icons.Rounded.StopCircle, contentDescription = null)
        }
    }
}

@Composable
private fun BlinkingAlertRing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "alert-ring")
    val alpha by
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
            label = "alpha",
        )
    Canvas(modifier = modifier) {
        val stroke = 8.dp.toPx()
        drawCircle(
            color = Color(0xFFFF9800).copy(alpha = alpha),
            radius = size.minDimension / 2f - stroke,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
    }
}

private fun statusColor(status: MetricStatus): Color =
    when (status) {
        MetricStatus.Normal -> Color(0xFFA5D6A7)
        MetricStatus.Warning -> Color(0xFFFFB74D)
        MetricStatus.Critical -> Color(0xFFEF9A9A)
        MetricStatus.Inactive -> Color(0xFFB0BEC5)
        MetricStatus.Unavailable -> Color(0xFF90A4AE)
    }
