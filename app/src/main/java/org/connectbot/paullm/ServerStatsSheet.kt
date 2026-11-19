package org.connectbot.paullm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.service.TerminalBridge
import java.util.Locale

data class RemoteHostStats(
    val hostname: String = "",
    val osInfo: String = "",
    val uptime: String = "",
    val loadAvg: String = "",
    val cpuPercent: Float? = null,
    val cpuCores: Int? = null,
    val memUsedMb: Long? = null,
    val memTotalMb: Long? = null,
    val diskSummary: String = "",
    val diskPercent: Float? = null,
)

object RemoteStatsCollector {
    private val probeScript = """
        echo "HOST:$(hostname 2>/dev/null || uname -n)"
        echo "OS:$(uname -srm 2>/dev/null)"
        echo "UPTIME:$(uptime -p 2>/dev/null || uptime 2>/dev/null | sed 's/.*up //; s/, *[0-9]* user.*//')"
        if [ -r /proc/loadavg ]; then
          echo "LOAD:$(awk '{print $1" "$2" "$3}' /proc/loadavg)"
        else
          echo "LOAD:$(sysctl -n vm.loadavg 2>/dev/null | tr -d '{}')"
        fi
        echo "CORES:$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)"
        if [ -r /proc/stat ]; then
          read -r _ a1 b1 c1 d1 e1 f1 g1 _ < /proc/stat
          sleep 0.25
          read -r _ a2 b2 c2 d2 e2 f2 g2 _ < /proc/stat
          t1=$((a1+b1+c1+d1+e1+f1+g1))
          t2=$((a2+b2+c2+d2+e2+f2+g2))
          dt=$((t2-t1))
          di=$((d2-d1))
          if [ "${'$'}dt" -gt 0 ]; then
            echo "CPU:$(( (dt - di) * 100 / dt ))"
          fi
        fi
        if [ -r /proc/meminfo ]; then
          awk '/MemTotal:/{t=$2} /MemAvailable:/{a=$2} END{if(t>0) printf "MEM:%d:%d\n", (t-a)/1024, t/1024}' /proc/meminfo
        fi
        df -h / 2>/dev/null | awk 'NR==2{printf "DISK:%s / %s (%s):%s\n", $3, $2, $5, $5}'
    """.trimIndent()

    suspend fun sample(bridge: TerminalBridge): RemoteHostStats {
        val result = bridge.execCommand(PaullmShell.wrapLoginShell(probeScript), timeoutMs = 10_000L)
        if (result.error != null) {
            throw IllegalStateException(result.error)
        }
        val fields = mutableMapOf<String, String>()
        result.stdout.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        val memParts = fields["MEM"]?.split(":")
        val diskRaw = fields["DISK"].orEmpty()
        val diskLastColon = diskRaw.lastIndexOf(':')
        val diskSummary = if (diskLastColon > 0) diskRaw.substring(0, diskLastColon) else diskRaw
        val diskPct = if (diskLastColon > 0) {
            diskRaw.substring(diskLastColon + 1).removeSuffix("%").toFloatOrNull()?.div(100f)
        } else {
            null
        }
        return RemoteHostStats(
            hostname = fields["HOST"].orEmpty(),
            osInfo = fields["OS"].orEmpty(),
            uptime = fields["UPTIME"].orEmpty(),
            loadAvg = fields["LOAD"].orEmpty(),
            cpuPercent = fields["CPU"]?.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f),
            cpuCores = fields["CORES"]?.toIntOrNull(),
            memUsedMb = memParts?.getOrNull(0)?.toLongOrNull(),
            memTotalMb = memParts?.getOrNull(1)?.toLongOrNull(),
            diskSummary = diskSummary,
            diskPercent = diskPct?.coerceIn(0f, 1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatsSheet(
    bridge: TerminalBridge,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<RemoteHostStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(true) }

    suspend fun fetchOnce() {
        loading = stats == null
        runCatching { RemoteStatsCollector.sample(bridge) }
            .onSuccess {
                stats = it
                errorText = null
            }
            .onFailure {
                errorText = it.message ?: "Failed to sample remote host stats"
            }
        loading = false
    }

    LaunchedEffect(bridge, autoRefresh) {
        fetchOnce()
        while (autoRefresh) {
            delay(3_000L)
            fetchOnce()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MonitorHeart, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Server Stats", style = MaterialTheme.typography.titleLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = autoRefresh,
                        onClick = { autoRefresh = !autoRefresh },
                        label = { Text(if (autoRefresh) "Live 3s" else "Paused") },
                    )
                    IconButton(onClick = { scope.launch { fetchOnce() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh stats")
                    }
                }
            }

            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                stats?.let { s ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(s.hostname.ifBlank { bridge.host.hostname }, style = MaterialTheme.typography.titleMedium)
                            if (s.osInfo.isNotBlank()) {
                                Text(s.osInfo, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                            if (s.uptime.isNotBlank()) {
                                Text("Uptime: ${s.uptime}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (s.loadAvg.isNotBlank()) {
                                Text("Load: ${s.loadAvg}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    s.cpuPercent?.let { cpu ->
                        MetricCard(
                            label = "CPU${s.cpuCores?.let { " ($it cores)" }.orEmpty()}",
                            value = String.format(Locale.US, "%.0f%%", cpu * 100f),
                            progress = cpu,
                        )
                    }

                    if (s.memUsedMb != null && s.memTotalMb != null && s.memTotalMb > 0) {
                        val ratio = (s.memUsedMb.toFloat() / s.memTotalMb.toFloat()).coerceIn(0f, 1f)
                        MetricCard(
                            label = "Memory",
                            value = "${formatMb(s.memUsedMb)} / ${formatMb(s.memTotalMb)}",
                            progress = ratio,
                        )
                    }

                    if (s.diskSummary.isNotBlank()) {
                        MetricCard(
                            label = "Disk (/)",
                            value = s.diskSummary,
                            progress = s.diskPercent ?: 0f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, progress: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatMb(mb: Long): String =
    if (mb >= 1024) String.format(Locale.US, "%.1f GiB", mb / 1024.0) else "$mb MiB"
