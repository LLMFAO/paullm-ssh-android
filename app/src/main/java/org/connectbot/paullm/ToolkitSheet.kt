package org.connectbot.paullm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.connectbot.service.TerminalBridge

enum class ToolGroup(val label: String) {
    ALL("All"),
    AI_CLI("AI CLIs"),
    TEMPLATES("Templates"),
}

data class VibeToolSpec(
    val id: String,
    val name: String,
    val group: ToolGroup,
    val summary: String,
    val detectCmd: String,
    val installCmd: String,
    val authCmd: String? = null,
    val launchPreset: TmuxLaunchPreset? = null,
    val launchBinary: String? = null,
)

object VibeToolRegistry {
    val items = listOf(
        VibeToolSpec(
            id = "claude",
            name = "Claude Code",
            group = ToolGroup.AI_CLI,
            summary = "Anthropic terminal coding agent (npm).",
            detectCmd = "command -v claude >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g @anthropic-ai/claude-code",
            authCmd = "claude auth login",
            launchPreset = TmuxLaunchPreset.CLAUDE,
            launchBinary = "claude",
        ),
        VibeToolSpec(
            id = "codex",
            name = "Codex CLI",
            group = ToolGroup.AI_CLI,
            summary = "OpenAI terminal coding agent (npm).",
            detectCmd = "command -v codex >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g @openai/codex",
            authCmd = "codex login --device-auth",
            launchPreset = TmuxLaunchPreset.CODEX,
            launchBinary = "codex",
        ),
        VibeToolSpec(
            id = "opencode",
            name = "OpenCode CLI",
            group = ToolGroup.AI_CLI,
            summary = "Open-source multi-model coding CLI (npm).",
            detectCmd = "command -v opencode >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g opencode-ai",
            authCmd = "opencode auth login",
            launchPreset = TmuxLaunchPreset.OPENCODE,
            launchBinary = "opencode",
        ),
        VibeToolSpec(
            id = "antigravity",
            name = "Antigravity CLI",
            group = ToolGroup.AI_CLI,
            summary = "Google Antigravity agent CLI (agy).",
            detectCmd = "command -v agy >/dev/null 2>&1 && echo __OK__",
            installCmd = "curl -fsSL https://antigravity.google/cli/install.sh | bash",
            authCmd = "agy",
            launchPreset = TmuxLaunchPreset.ANTIGRAVITY,
            launchBinary = "agy",
        ),
        VibeToolSpec(
            id = "aider",
            name = "Aider",
            group = ToolGroup.AI_CLI,
            summary = "Git-native AI pair programmer (pip).",
            detectCmd = "command -v aider >/dev/null 2>&1 && echo __OK__",
            installCmd = "pip install aider-chat",
            launchBinary = "aider",
        ),
        VibeToolSpec(
            id = "goose",
            name = "Goose",
            group = ToolGroup.AI_CLI,
            summary = "Extensible developer agent CLI.",
            detectCmd = "command -v goose >/dev/null 2>&1 && echo __OK__",
            installCmd = "curl -fsSL https://github.com/block/goose/releases/latest/download/install.sh | bash",
            launchBinary = "goose",
        ),
        VibeToolSpec(
            id = "agents-md",
            name = "Project AGENTS.md",
            group = ToolGroup.TEMPLATES,
            summary = "Creates an AGENTS.md guide in the current folder.",
            detectCmd = "test -f AGENTS.md && echo __OK__",
            installCmd = "cat > AGENTS.md <<'EOF'\n# Project Agent Guide\n\n## Stack\n- \n\n## Conventions\n- Keep commits focused.\n- Run tests after edits.\nEOF",
        ),
        VibeToolSpec(
            id = "memory-vault",
            name = "AI Memory Folder",
            group = ToolGroup.TEMPLATES,
            summary = "Creates docs/ai/context.md for durable project notes.",
            detectCmd = "test -f docs/ai/context.md && echo __OK__",
            installCmd = "mkdir -p docs/ai && cat > docs/ai/context.md <<'EOF'\n# Project Context\n\n## Architecture\n- \n\n## Decisions\n- \nEOF",
        ),
    )

    suspend fun scanAll(bridge: TerminalBridge): Map<String, Boolean> = coroutineScope {
        items.map { tool ->
            async {
                val res = bridge.execCommand(PaullmShell.wrapLoginShell(tool.detectCmd), timeoutMs = 8_000L)
                tool.id to res.stdout.contains("__OK__")
            }
        }.awaitAll().toMap()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolkitSheet(
    bridge: TerminalBridge,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedGroup by remember { mutableStateOf(ToolGroup.ALL) }
    var scanning by remember { mutableStateOf(true) }
    var installedMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var confirmInstall by remember { mutableStateOf<VibeToolSpec?>(null) }

    fun scan() {
        scope.launch {
            scanning = true
            installedMap = runCatching { VibeToolRegistry.scanAll(bridge) }.getOrDefault(emptyMap())
            scanning = false
        }
    }

    LaunchedEffect(bridge) {
        scan()
    }

    confirmInstall?.let { tool ->
        AlertDialog(
            onDismissRequest = { confirmInstall = null },
            title = { Text("Run installer in terminal?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This will run the following command in your active SSH session:")
                    Text(
                        tool.installCmd,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmInstall = null
                        bridge.injectString("${tool.installCmd}\r")
                        onMessage("Running ${tool.name} setup in terminal")
                        onDismiss()
                    },
                ) {
                    Text("Run")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    val visibleTools = remember(selectedGroup) {
        if (selectedGroup == ToolGroup.ALL) {
            VibeToolRegistry.items
        } else {
            VibeToolRegistry.items.filter { it.group == selectedGroup }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("AI Vibe Toolkit", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = ::scan, enabled = !scanning) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan host")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolGroup.entries.forEach { group ->
                    FilterChip(
                        selected = selectedGroup == group,
                        onClick = { selectedGroup = group },
                        label = { Text(group.label) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleTools, key = { it.id }) { tool ->
                    val installed = installedMap[tool.id] == true
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(tool.name, style = MaterialTheme.typography.titleMedium)
                                if (installed) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Installed",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Installed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    Text(
                                        if (scanning) "Checking..." else "Not detected",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Text(
                                tool.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (installed && (tool.launchPreset != null || tool.launchBinary != null)) {
                                    Button(
                                        onClick = {
                                            if (tool.launchPreset != null) {
                                                TmuxRemoteController.createAndAttach(
                                                    bridge,
                                                    tool.launchPreset.defaultName,
                                                    tool.launchPreset,
                                                )
                                            } else {
                                                bridge.injectString("${tool.launchBinary}\r")
                                            }
                                            onMessage("Launching ${tool.name}")
                                            onDismiss()
                                        },
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Launch")
                                    }
                                }

                                OutlinedButton(onClick = { confirmInstall = tool }) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (installed) "Reinstall" else "Install")
                                }

                                tool.authCmd?.let { auth ->
                                    OutlinedButton(
                                        onClick = {
                                            bridge.injectString("$auth\r")
                                            onMessage("Running ${tool.name} login")
                                            onDismiss()
                                        },
                                    ) {
                                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Auth")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
