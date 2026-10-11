package com.example.torstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.torstream.data.model.StreamTelemetry
import com.example.ui.theme.*

@Composable
fun SwarmTelemetryBar(
    telemetry: StreamTelemetry,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("swarm_telemetry_bar"),
        shape = RoundedCornerShape(12.dp),
        color = OverlayGlass,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download Speed
                TelemetryMetricItem(
                    icon = Icons.Default.ArrowDownward,
                    label = "DL SPEED",
                    value = telemetry.downloadSpeedFormatted,
                    accentColor = CyanPrimary
                )

                // Swarm Peers
                TelemetryMetricItem(
                    icon = Icons.Default.Groups,
                    label = "SWARM PEERS",
                    value = "${telemetry.connectedPeers} peers",
                    subValue = "(${telemetry.totalSeeds} seeds)",
                    accentColor = EmeraldAccent
                )

                // Buffer Health
                TelemetryMetricItem(
                    icon = Icons.Default.Speed,
                    label = "BUFFER HEALTH",
                    value = String.format("%.0fs", telemetry.bufferHealthSeconds),
                    accentColor = if (telemetry.bufferHealthSeconds > 15f) EmeraldAccent else AmberBuffering
                )
            }

            if (!isCompact) {
                PieceMapVisualizer(
                    totalPieces = telemetry.totalPieces,
                    bufferedPieces = telemetry.bufferedPieces,
                    activeDownloadingPieces = telemetry.activeDownloadingPieces,
                    showLabel = true
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String? = null,
    accentColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(14.dp)
            )
        }

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subValue != null) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = subValue,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
