package com.example.torstream.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberBuffering
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkSurfaceElevated

@Composable
fun PieceMapVisualizer(
    totalPieces: Int,
    bufferedPieces: Set<Int>,
    activeDownloadingPieces: Set<Int>,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    Column(
        modifier = modifier.testTag("piece_map_visualizer"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showLabel) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SWARM SEQUENTIAL PIECE MAP",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${bufferedPieces.size}/$totalPieces pieces buffered",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = CyanPrimary
                )
            }
        }

        // Display pieces as a row of compact segments
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DarkSurfaceElevated),
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            val count = totalPieces.coerceIn(32, 64)
            for (i in 0 until count) {
                val isBuffered = bufferedPieces.contains(i)
                val isDownloading = activeDownloadingPieces.contains(i)

                val targetColor = when {
                    isBuffered -> CyanPrimary
                    isDownloading -> AmberBuffering
                    else -> Color(0xFF1E2838)
                }

                val animatedColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(durationMillis = 250),
                    label = "piece_color_$i"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(animatedColor)
                )
            }
        }
    }
}
