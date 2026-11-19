package org.connectbot.paullm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.connectbot.service.TerminalBridge

data class TmuxSessionInfo(
    val name: String,
    val attachedCount: Int,
    val windowCount: Int,
    val currentPath: String?,
)

enum class TmuxLaunchPreset(
    val label: String,
    val defaultName: String,
    val command: String?,
) {
    SHELL("Shell", "main", null),
    CLAUDE("Claude", "claude", "claude"),
    CODEX("Codex", "codex", "codex"),
    OPENCODE("OpenCode", "opencode", "opencode"),
    ANTIGRAVITY("Antigravity", "agy", "agy"),
}

object TmuxRemoteController {
    suspend fun discover(bridge: TerminalBridge): Pair<Boolean, List<TmuxSessionInfo>> {
        val probe = bridge.execCommand(
            PaullmShell.wrapLoginShell("command -v tmux >/dev/null 2>&1 && echo __TMUX_READY__"),
            timeoutMs = 8_000L,
        )
        if (!probe.stdout.contains("__TMUX_READY__")) {
            return false to emptyList()
        }

        val listResult = bridge.execCommand(
            PaullmShell.wrapLoginShell(
                "tmux list-sessions -F '#{session_name}\t#{session_attached}\t#{session_windows}\t#{pane_current_path}' 2>/dev/null || true",
            ),
            timeoutMs = 10_000L,
        )
        val parsed = listResult.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                val name = parts.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) {
                    null
                } else {
                    TmuxSessionInfo(
                        name = name,
                        attachedCount = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                        windowCount = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1,
                        currentPath = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
            }
            .sortedBy { it.name.lowercase() }
            .toList()
        return true to parsed
    }

    fun attachExact(bridge: TerminalBridge, sessionName: String) {
        val target = PaullmShell.quote("=$sessionName")
        bridge.injectString("tmux attach-session -t $target\r")
    }

    fun createAndAttach(bridge: TerminalBridge, rawName: String, preset: TmuxLaunchPreset) {
        val cleanName = rawName.trim()
            .ifEmpty { preset.defaultName }
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
        val quotedName = PaullmShell.quote(cleanName)
        val launchCmd = preset.command
        val full = if (launchCmd != null) {
            "tmux new-session -A -s $quotedName ${PaullmShell.quote("$launchCmd; exec \${SHELL:-sh}")}"
        } else {
            "tmux new-session -A -s $quotedName"
        }
        bridge.injectString("$full\r")
    }

    fun detachCurrentClient(bridge: TerminalBridge) {
        // Sends standard tmux prefix + d to detach only this client without touching other clients or killing the session.
        bridge.injectString("\u0002d")
    }

    fun sendInstallCommand(bridge: TerminalBridge) {
        val script = "if command -v apt-get >/dev/null 2>&1; then sudo apt-get update && sudo apt-get install -y tmux; " +
            "elif command -v pacman >/dev/null 2>&1; then sudo pacman -Sy --noconfirm tmux; " +
            "elif command -v dnf >/dev/null 2>&1; then sudo dnf install -y tmux; " +
            "elif command -v brew >/dev/null 2>&1; then brew install tmux; " +
            "else echo 'Install tmux with your system package manager'; fi"
        bridge.injectString("$script\r")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxManagerSheet(
    bridge: TerminalBridge,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var tmuxInstalled by remember { mutableStateOf(true) }
    var sessions by remember { mutableStateOf<List<TmuxSessionInfo>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var selectedPreset by remember { mutableStateOf(TmuxLaunchPreset.SHELL) }
    var newSessionName by remember { mutableStateOf(TmuxLaunchPreset.SHELL.defaultName) }

    fun refresh() {
        scope.launch {
            loading = true
            errorText = null
            runCatching { TmuxRemoteController.discover(bridge) }
                .onSuccess { (installed, list) ->
                    tmuxInstalled = installed
                    sessions = list
                }
                .onFailure { err ->
                    errorText = err.message ?: "Unable to query tmux"
                }
            loading = false
        }
    }

    LaunchedEffect(bridge) {
        refresh()
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
                    Icon(Icons.Default.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("tmux Sessions", style = MaterialTheme.typography.titleLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            TmuxRemoteController.detachCurrentClient(bridge)
                            onMessage("Sent client-only detach (Ctrl-B d)")
                            onDismiss()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Detach")
                    }
                    IconButton(onClick = ::refresh, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh sessions")
                    }
                }
            }

            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (!tmuxInstalled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("tmux is not installed on this host", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Install tmux to keep shells and AI coding sessions running across mobile disconnects.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                TmuxRemoteController.sendInstallCommand(bridge)
                                onMessage("Running tmux installer in terminal")
                                onDismiss()
                            },
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Install tmux")
                        }
                    }
                }
            } else {
                errorText?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (sessions.isEmpty()) {
                    Text(
                        "No active tmux sessions yet. Launch one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((sessions.size.coerceAtMost(4) * 76).dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sessions, key = { it.name }) { session ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        TmuxRemoteController.attachExact(bridge, session.name)
                                        onMessage("Attaching to tmux session '${session.name}'")
                                        onDismiss()
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            session.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        session.currentPath?.let { path ->
                                            Text(
                                                path,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${session.windowCount}w · ${session.attachedCount}c",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (session.attachedCount > 0) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text("New Session", style = MaterialTheme.typography.titleSmall)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TmuxLaunchPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = {
                                selectedPreset = preset
                                newSessionName = preset.defaultName
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newSessionName,
                        onValueChange = { newSessionName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Session name") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            TmuxRemoteController.createAndAttach(bridge, newSessionName, selectedPreset)
                            onMessage("Launching '${newSessionName.ifBlank { selectedPreset.defaultName }}'")
                            onDismiss()
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Start")
                    }
                }
            }
        }
    }
}
