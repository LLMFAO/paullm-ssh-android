package org.connectbot.paullm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.service.TerminalBridge
import java.util.Locale

data class DiskVolumeInfo(
    val mount: String,
    val usedLabel: String,
    val totalLabel: String,
    val percent: Float,
)

data class DashboardMetrics(
    val osKernel: String = "",
    val cpuTotalPct: Int = 0,
    val cpuUserPct: Int = 0,
    val cpuSysPct: Int = 0,
    val cpuIowaitPct: Int = 0,
    val cpuStealPct: Int = 0,
    val cpuIdlePct: Int = 100,
    val cpuCores: Int = 1,
    val uptimeCompact: String = "--",
    val loadAvg: String = "--",
    val memFreeGb: Double = 0.0,
    val memUsedGb: Double = 0.0,
    val memCachedGb: Double = 0.0,
    val memTotalGb: Double = 0.0,
    val memUsedPct: Int = 0,
    val netUpRateKb: Double = 0.0,
    val netDownRateKb: Double = 0.0,
    val netUpTotalGb: Double = 0.0,
    val netDownTotalGb: Double = 0.0,
    val volumes: List<DiskVolumeInfo> = emptyList(),
)

object HostDashboardCollector {
    private val script = """
        echo "OS:${'$'}(uname -srm 2>/dev/null)"
        echo "CORES:${'$'}(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1)"
        if [ -r /proc/uptime ]; then
          awk '{s=int(${'$'}1); d=int(s/86400); h=int((s%86400)/3600); m=int((s%3600)/60); if(d>0) printf "UPTIME:%d D\n", d; else if(h>0) printf "UPTIME:%d H\n", h; else printf "UPTIME:%d M\n", m}' /proc/uptime
        else
          echo "UPTIME:--"
        fi
        if [ -r /proc/loadavg ]; then
          awk '{printf "LOAD:%.1f,%.1f,%.1f\n", ${'$'}1, ${'$'}2, ${'$'}3}' /proc/loadavg
        fi
        if [ -r /proc/stat ] && [ -r /proc/net/dev ]; then
          read -r _ u1 n1 s1 i1 w1 q1 sq1 st1 _ < /proc/stat
          net1=${'$'}(awk 'NR>2 && ${'$'}1 !~ /^lo:/ {rx+=${'$'}2; tx+=${'$'}10} END {printf "%.0f %.0f", rx, tx}' /proc/net/dev)
          sleep 0.35
          read -r _ u2 n2 s2 i2 w2 q2 sq2 st2 _ < /proc/stat
          net2=${'$'}(awk 'NR>2 && ${'$'}1 !~ /^lo:/ {rx+=${'$'}2; tx+=${'$'}10} END {printf "%.0f %.0f", rx, tx}' /proc/net/dev)
          du=${'$'}(( (u2+n2) - (u1+n1) ))
          ds=${'$'}(( (s2+q2+sq2) - (s1+q1+sq1) ))
          di=${'$'}(( i2 - i1 ))
          dw=${'$'}(( w2 - w1 ))
          dst=${'$'}(( st2 - st1 ))
          dt=${'$'}(( du + ds + di + dw + dst ))
          if [ "${'$'}dt" -gt 0 ]; then
            echo "CPU:${'$'}(( (dt - di) * 100 / dt )):${'$'}(( du * 100 / dt )):${'$'}(( ds * 100 / dt )):${'$'}(( dw * 100 / dt )):${'$'}(( dst * 100 / dt )):${'$'}(( di * 100 / dt ))"
          fi
          rx1=${'$'}{net1%% *}; tx1=${'$'}{net1##* }
          rx2=${'$'}{net2%% *}; tx2=${'$'}{net2##* }
          echo "NET:${'$'}rx1:${'$'}tx1:${'$'}rx2:${'$'}tx2"
        fi
        if [ -r /proc/meminfo ]; then
          awk '
            /MemTotal:/ {t=${'$'}2}
            /MemFree:/ {f=${'$'}2}
            /MemAvailable:/ {a=${'$'}2}
            /^Buffers:/ {b=${'$'}2}
            /^Cached:/ {c=${'$'}2}
            /^SReclaimable:/ {sr=${'$'}2}
            END {
              if (t > 0) {
                used = t - a; if (used < 0) used = 0;
                cached = b + c + sr;
                printf "MEM:%.1f:%.1f:%.1f:%.1f:%d\n", f/1048576, used/1048576, cached/1048576, t/1048576, (used*100)/t
              }
            }' /proc/meminfo
        fi
        df -hP -x tmpfs -x devtmpfs -x squashfs -x overlay 2>/dev/null | awk 'NR>1 && ${'$'}1 ~ /^\// {printf "VOL:%s|%s|%s|%s\n", ${'$'}6, ${'$'}3, ${'$'}2, ${'$'}5}' | head -n 6
    """.trimIndent()

    suspend fun collect(bridge: TerminalBridge): DashboardMetrics {
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = 10_000L)
        if (res.error != null) throw IllegalStateException(res.error)

        var osKernel = ""
        var cores = 1
        var uptime = "--"
        var load = "--"
        var cpuTotal = 0
        var cpuUser = 0
        var cpuSys = 0
        var cpuIowait = 0
        var cpuSteal = 0
        var cpuIdle = 100
        var memFree = 0.0
        var memUsed = 0.0
        var memCached = 0.0
        var memTotal = 0.0
        var memPct = 0
        var upRateKb = 0.0
        var downRateKb = 0.0
        var upTotalGb = 0.0
        var downTotalGb = 0.0
        val vols = mutableListOf<DiskVolumeInfo>()

        res.stdout.lineSequence().forEach { line ->
            when {
                line.startsWith("OS:") -> osKernel = line.removePrefix("OS:").trim()
                line.startsWith("CORES:") -> cores = line.removePrefix("CORES:").trim().toIntOrNull() ?: 1
                line.startsWith("UPTIME:") -> uptime = line.removePrefix("UPTIME:").trim()
                line.startsWith("LOAD:") -> load = line.removePrefix("LOAD:").trim()
                line.startsWith("CPU:") -> {
                    val p = line.removePrefix("CPU:").split(":")
                    cpuTotal = p.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    cpuUser = p.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    cpuSys = p.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    cpuIowait = p.getOrNull(3)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    cpuSteal = p.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    cpuIdle = p.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 100) ?: (100 - cpuTotal)
                }

                line.startsWith("MEM:") -> {
                    val p = line.removePrefix("MEM:").split(":")
                    memFree = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                    memUsed = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    memCached = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                    memTotal = p.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                    memPct = p.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                }

                line.startsWith("NET:") -> {
                    val p = line.removePrefix("NET:").split(":")
                    val rx1 = p.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                    val tx1 = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    val rx2 = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                    val tx2 = p.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                    downRateKb = ((rx2 - rx1).coerceAtLeast(0.0) / 0.35) / 1024.0
                    upRateKb = ((tx2 - tx1).coerceAtLeast(0.0) / 0.35) / 1024.0
                    downTotalGb = rx2 / (1024.0 * 1024.0 * 1024.0)
                    upTotalGb = tx2 / (1024.0 * 1024.0 * 1024.0)
                }

                line.startsWith("VOL:") -> {
                    val p = line.removePrefix("VOL:").split("|")
                    if (p.size >= 4) {
                        val pct = p[3].trim().removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: 0f
                        vols.add(DiskVolumeInfo(mount = p[0], usedLabel = p[1], totalLabel = p[2], percent = pct))
                    }
                }
            }
        }

        return DashboardMetrics(
            osKernel = osKernel,
            cpuTotalPct = cpuTotal,
            cpuUserPct = cpuUser,
            cpuSysPct = cpuSys,
            cpuIowaitPct = cpuIowait,
            cpuStealPct = cpuSteal,
            cpuIdlePct = cpuIdle,
            cpuCores = cores,
            uptimeCompact = uptime,
            loadAvg = load,
            memFreeGb = memFree,
            memUsedGb = memUsed,
            memCachedGb = memCached,
            memTotalGb = memTotal,
            memUsedPct = memPct,
            netUpRateKb = upRateKb,
            netDownRateKb = downRateKb,
            netUpTotalGb = upTotalGb,
            netDownTotalGb = downTotalGb,
            volumes = vols,
        )
    }
}

private val DashCardBg = Color(0xFF141720)
private val DashCardBorder = Color(0xFF252B3B)
private val DashGreen = Color(0xFF22C55E)
private val DashBlue = Color(0xFF2563EB)
private val DashRed = Color(0xFFEF4444)
private val DashAmber = Color(0xFFF59E0B)
private val DashPurple = Color(0xFFA855F7)

@Composable
fun HostDashboardView(
    bridge: TerminalBridge,
    disconnected: Boolean,
    openTmuxNames: Set<String>,
    sessions: List<TmuxSessionInfo>,
    onSessionsUpdated: (List<TmuxSessionInfo>) -> Unit,
    onAttachTmuxSession: (String) -> Unit,
    onDetachTmuxSession: (String) -> Unit,
    onOpenNewSessionPicker: () -> Unit,
    onDisconnectHost: () -> Unit,
    onReconnectHost: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var metrics by remember(bridge.host.id) { mutableStateOf<DashboardMetrics?>(null) }
    var loading by remember(bridge.host.id) { mutableStateOf(true) }
    var errorText by remember(bridge.host.id) { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<TmuxSessionInfo?>(null) }

    suspend fun refreshAll() {
        if (disconnected || !bridge.supportsRemoteTools()) {
            loading = false
            return
        }
        runCatching {
            val m = HostDashboardCollector.collect(bridge)
            val (installed, list) = TmuxRemoteController.discover(bridge)
            m to (if (installed) list else emptyList())
        }.onSuccess { (m, list) ->
            metrics = m
            onSessionsUpdated(list)
            errorText = null
        }.onFailure { err ->
            errorText = err.message
        }
        loading = false
    }

    LaunchedEffect(bridge.host.id, disconnected, bridge.isSessionOpen) {
        if (!disconnected && bridge.supportsRemoteTools()) {
            refreshAll()
            while (true) {
                delay(4_000L)
                refreshAll()
            }
        } else {
            loading = false
        }
    }

    actionTarget?.let { target ->
        val isOpenInApp = target.name in openTmuxNames
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("Manage '${target.name}'") },
            text = {
                Text(
                    if (isOpenInApp) {
                        "Detach closes only your phone tab while keeping '${target.name}' running on ${bridge.host.nickname}. Kill permanently terminates the remote tmux session."
                    } else {
                        "Kill permanently terminates '${target.name}' and its running processes on ${bridge.host.nickname}."
                    },
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = DashRed),
                    onClick = {
                        actionTarget = null
                        scope.launch {
                            TmuxRemoteController.killSession(bridge, target.name)
                                .onSuccess {
                                    onMessage("Killed tmux session '${target.name}'")
                                    refreshAll()
                                }
                                .onFailure { onMessage(it.message ?: "Kill failed") }
                        }
                    },
                ) { Text("Kill Session") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isOpenInApp) {
                        OutlinedButton(
                            onClick = {
                                actionTarget = null
                                onDetachTmuxSession(target.name)
                            },
                        ) { Text("Detach Tab") }
                    }
                    TextButton(onClick = { actionTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070A10)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Host Header Card with Connect / Disconnect action
        item(key = "host_header") {
            DashCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bridge.host.nickname,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = metrics?.osKernel?.takeIf { it.isNotBlank() }
                                ?: "${bridge.host.username}@${bridge.host.hostname}:${bridge.host.port}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF94A3B8),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (disconnected) {
                        Button(
                            onClick = onReconnectHost,
                            colors = ButtonDefaults.buttonColors(containerColor = DashBlue),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Connect")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDisconnectHost,
                            border = BorderStroke(1.dp, DashRed.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(DashGreen),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Disconnect", color = Color(0xFFFCA5A5))
                        }
                    }
                }
            }
        }

        // 2. tmux Sessions Card (with LobeHub logos + Blue Attach -> + Red x Kill/Detach)
        item(key = "tmux_sessions") {
            DashCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiVendorLogo(vendor = AiVendor.TMUX, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "tmux Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onOpenNewSessionPicker,
                            enabled = !disconnected,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New session", tint = Color(0xFF38BDF8))
                        }
                        IconButton(
                            onClick = { scope.launch { refreshAll() } },
                            enabled = !disconnected,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFFCBD5E1))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (sessions.isEmpty()) {
                    Text(
                        text = if (disconnected) {
                            "Disconnected — tap Connect above to discover tmux sessions."
                        } else if (loading) {
                            "Discovering tmux sessions..."
                        } else {
                            "No active tmux sessions. Tap + to start Claude, Codex, Grok, OpenCode, Antigravity, or Shell."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        sessions.forEach { session ->
                            val vendor = session.detectedVendor
                            val isLoaded = session.name in openTmuxNames
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAttachTmuxSession(session.name) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AiVendorBadge(vendor = vendor, size = 34.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = session.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isLoaded) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = DashGreen.copy(alpha = 0.2f),
                                            ) {
                                                Text(
                                                    "Loaded",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = DashGreen,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = buildString {
                                            session.activeCommand?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                                            append("${session.windowCount}w")
                                            session.currentPath?.let { append(" · $it") }
                                            if (session.attachedCount > 0) append(" · attached")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    onClick = { onAttachTmuxSession(session.name) },
                                    shape = CircleShape,
                                    color = DashBlue,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Attach to ${session.name}",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    onClick = { actionTarget = session },
                                    shape = CircleShape,
                                    color = DashRed,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Detach or kill ${session.name}",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (loading && metrics == null && !disconnected) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }

        errorText?.let { err ->
            item(key = "err") {
                Text(err, color = DashRed, style = MaterialTheme.typography.bodySmall)
            }
        }

        metrics?.let { m ->
            // 3. CPU Card with Circular Donut Gauge
            item(key = "cpu_card") {
                DashCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                LegendStat(DashRed, "SYS", "${m.cpuSysPct} %")
                                LegendStat(DashGreen, "USER", "${m.cpuUserPct} %")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                LegendStat(DashAmber, "IOWAIT", "${m.cpuIowaitPct} %")
                                LegendStat(DashPurple, "STEAL", "${m.cpuStealPct} %")
                            }
                        }
                        DonutGauge(
                            percent = m.cpuTotalPct / 100f,
                            centerText = "${m.cpuTotalPct}%",
                            ringColor = DashGreen,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = DashCardBorder,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        FooterStat("CORES", "${m.cpuCores}")
                        FooterStat("IDLE", "${m.cpuIdlePct} %")
                        FooterStat("UPTIME", m.uptimeCompact)
                        FooterStat("LOAD", m.loadAvg)
                    }
                }
            }

            // 4. Memory Card with Circular Donut Gauge
            item(key = "mem_card") {
                DashCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LegendStat(Color(0xFF64748B), "FREE", formatGb(m.memFreeGb))
                                LegendStat(DashGreen, "USED", formatGb(m.memUsedGb))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LegendStat(DashBlue, "CACHED", formatGb(m.memCachedGb))
                                LegendStat(Color(0xFF94A3B8), "TOTAL", formatGb(m.memTotalGb))
                            }
                        }
                        DonutGauge(
                            percent = m.memUsedPct / 100f,
                            centerText = "${m.memUsedPct}%",
                            ringColor = DashGreen,
                        )
                    }
                }
            }

            // 5. Network Card
            item(key = "net_card") {
                DashCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LegendStat(DashGreen, "↑/S", formatRateKb(m.netUpRateKb))
                                LegendStat(DashAmber, "↓/S", formatRateKb(m.netDownRateKb))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LegendStat(DashGreen, "↑ TOTAL", formatGb(m.netUpTotalGb))
                                LegendStat(DashAmber, "↓ TOTAL", formatGb(m.netDownTotalGb))
                            }
                        }
                        DualRingGauge(
                            outerPct = (m.netDownRateKb / 1024.0).toFloat().coerceIn(0.05f, 1f),
                            innerPct = (m.netUpRateKb / 1024.0).toFloat().coerceIn(0.05f, 1f),
                        )
                    }
                }
            }

            // 6. Volumes Card
            if (m.volumes.isNotEmpty()) {
                item(key = "volumes_card") {
                    DashCard {
                        Text(
                            text = "Volumes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            m.volumes.forEach { vol ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                Icons.Default.Storage,
                                                contentDescription = null,
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = vol.mount,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = "${vol.usedLabel}/${vol.totalLabel}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF94A3B8),
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { vol.percent },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = if (vol.percent > 0.85f) DashRed else DashGreen,
                                        trackColor = Color(0xFF232836),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, DashCardBorder),
        colors = CardDefaults.cardColors(containerColor = DashCardBg),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun LegendStat(dotColor: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
        )
    }
}

@Composable
private fun FooterStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF64748B),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
        )
    }
}

@Composable
private fun DonutGauge(
    percent: Float,
    centerText: String,
    ringColor: Color,
    size: Dp = 68.dp,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 7.dp.toPx()
            drawArc(
                color = Color(0xFF1F2A24),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokePx),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = (percent.coerceIn(0.02f, 1f)) * 360f,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        Text(
            text = centerText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
        )
    }
}

@Composable
private fun DualRingGauge(
    outerPct: Float,
    innerPct: Float,
    size: Dp = 68.dp,
) {
    Canvas(modifier = Modifier.size(size)) {
        val outerStroke = 5.dp.toPx()
        val innerStroke = 5.dp.toPx()
        drawArc(
            color = Color(0xFF2B2118),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = outerStroke),
        )
        drawArc(
            color = DashAmber,
            startAngle = -90f,
            sweepAngle = outerPct * 360f,
            useCenter = false,
            style = Stroke(width = outerStroke, cap = StrokeCap.Round),
        )
        val inset = 9.dp.toPx()
        val innerSize = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
        val innerTopLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(
            color = Color(0xFF192B22),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = Stroke(width = innerStroke),
        )
        drawArc(
            color = DashGreen,
            startAngle = -90f,
            sweepAngle = innerPct * 360f,
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = Stroke(width = innerStroke, cap = StrokeCap.Round),
        )
    }
}

private fun formatGb(gb: Double): String = String.format(Locale.US, "%.1f G", gb)

private fun formatRateKb(kb: Double): String =
    if (kb >= 1024.0) String.format(Locale.US, "%.1f M/s", kb / 1024.0) else String.format(Locale.US, "%.0f K/s", kb)
