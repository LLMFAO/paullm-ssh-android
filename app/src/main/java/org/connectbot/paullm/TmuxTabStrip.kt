package org.connectbot.paullm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.service.TerminalBridge

@Composable
fun TmuxSessionTabStrip(
    bridges: List<TerminalBridge>,
    currentIndex: Int,
    remoteTmuxSessions: List<TmuxSessionInfo>,
    activeHostId: java.util.UUID?,
    onSelectTab: (Int) -> Unit,
    onDetachTab: (TerminalBridge) -> Unit,
    onAttachRemoteSession: (String) -> Unit,
    onOpenTmuxSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openTmuxForActiveHost = bridges
        .filter { it.host.id == activeHostId }
        .mapNotNull { it.tmuxSessionName }
        .toSet()

    val unattachedRemote = remoteTmuxSessions.filter { it.name !in openTmuxForActiveHost }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            bridges.forEachIndexed { index, bridge ->
                val isSelected = index == currentIndex
                val isTmux = bridge.tmuxSessionName != null
                val vendor = AiVendor.detectFromName(
                    name = bridge.tmuxSessionName ?: bridge.host.nickname,
                    isTmux = isTmux,
                )
                val tabLabel = if (bridge.tmuxSessionName != null) {
                    bridge.tmuxSessionName
                } else {
                    "${bridge.host.nickname} (sh)"
                }
                val dotColor = when {
                    bridge.isDisconnected -> MaterialTheme.colorScheme.error
                    bridge.isConnecting -> Color(0xFFFBBF24)
                    else -> MaterialTheme.colorScheme.tertiary
                }

                Surface(
                    onClick = { onSelectTab(index) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) vendor.badgeBg else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) vendor.accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, end = if (isSelected && (bridges.size > 1 || isTmux)) 2.dp else 8.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(Modifier.width(5.dp))
                        AiVendorLogo(vendor = vendor, size = 14.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = tabLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (isSelected && (bridges.size > 1 || isTmux)) {
                            IconButton(
                                onClick = { onDetachTab(bridge) },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = if (isTmux) "Detach client" else "Close tab",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Show live remote tmux sessions not yet opened as tabs for 1-tap attach!
            unattachedRemote.forEach { remote ->
                val vendor = AiVendor.detectFromName(remote.name, isTmux = true)
                Surface(
                    onClick = { onAttachRemoteSession(remote.name) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, vendor.accent.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AiVendorLogo(vendor = vendor, size = 13.dp, tint = vendor.accent.copy(alpha = 0.75f))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = remote.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            Surface(
                onClick = onOpenTmuxSheet,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New tmux session",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "tmux",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}
