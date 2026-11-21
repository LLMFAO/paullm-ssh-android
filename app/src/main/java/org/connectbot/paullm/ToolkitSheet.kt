package org.connectbot.paullm

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
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
    val vendor: AiVendor,
    val summary: String,
    val detectCmd: String,
    val installCmd: String,
    val authCmd: String? = null,
    val launchPreset: TmuxLaunchPreset? = null,
)

object VibeToolRegistry {
    val items = listOf(
        VibeToolSpec(
            id = "claude",
            name = "Claude Code",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.CLAUDE,
            summary = "Anthropic terminal coding agent.",
            detectCmd = "command -v claude >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g @anthropic-ai/claude-code",
            authCmd = "claude auth login",
            launchPreset = TmuxLaunchPreset.CLAUDE,
        ),
        VibeToolSpec(
            id = "codex",
            name = "Codex CLI",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.CODEX,
            summary = "OpenAI terminal coding agent.",
            detectCmd = "command -v codex >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g @openai/codex",
            authCmd = "codex login --device-auth",
            launchPreset = TmuxLaunchPreset.CODEX,
        ),
        VibeToolSpec(
            id = "grok",
            name = "Grok CLI",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.GROK,
            summary = "xAI Grok terminal coding agent.",
            detectCmd = "command -v grok >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g @xai-official/grok",
            authCmd = "grok",
            launchPreset = TmuxLaunchPreset.GROK,
        ),
        VibeToolSpec(
            id = "opencode",
            name = "OpenCode CLI",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.OPENCODE,
            summary = "Open-source multi-model coding CLI.",
            detectCmd = "command -v opencode >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g opencode-ai",
            authCmd = "opencode auth login",
            launchPreset = TmuxLaunchPreset.OPENCODE,
        ),
        VibeToolSpec(
            id = "antigravity",
            name = "Antigravity CLI",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.ANTIGRAVITY,
            summary = "Google Antigravity agent CLI (agy).",
            detectCmd = "command -v agy >/dev/null 2>&1 && echo __OK__",
            installCmd = "curl -fsSL https://antigravity.google/cli/install.sh | bash",
            authCmd = "agy",
            launchPreset = TmuxLaunchPreset.ANTIGRAVITY,
        ),
        VibeToolSpec(
            id = "gemini",
            name = "Gemini CLI",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.GEMINI,
            summary = "Google Gemini terminal coding agent.",
            detectCmd = "command -v gemini >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g @google/gemini-cli",
            authCmd = "gemini",
            launchPreset = TmuxLaunchPreset.GEMINI,
        ),
        VibeToolSpec(
            id = "pi",
            name = "Pi / Amie CLI",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.PI,
            summary = "Local Pi coding agent runtime.",
            detectCmd = "command -v pi >/dev/null 2>&1 && echo __OK__",
            installCmd = "npm install -g @mariozechner/pi-coding-agent",
            launchPreset = TmuxLaunchPreset.PI,
        ),
        VibeToolSpec(
            id = "aider",
            name = "Aider",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.AIDER,
            summary = "Git-native AI pair programmer (pip).",
            detectCmd = "command -v aider >/dev/null 2>&1 && echo __OK__",
            installCmd = "pip install aider-chat",
            launchPreset = TmuxLaunchPreset.AIDER,
        ),
        VibeToolSpec(
            id = "goose",
            name = "Goose",
            group = ToolGroup.AI_CLI,
            vendor = AiVendor.GOOSE,
            summary = "Extensible developer agent CLI.",
            detectCmd = "command -v goose >/dev/null 2>&1 && echo __OK__",
            installCmd = "curl -fsSL https://github.com/block/goose/releases/latest/download/install.sh | bash",
            launchPreset = TmuxLaunchPreset.GOOSE,
        ),
        VibeToolSpec(
            id = "agents-md",
            name = "Project AGENTS.md",
            group = ToolGroup.TEMPLATES,
            vendor = AiVendor.SHELL,
            summary = "Creates an AGENTS.md guide in the current folder.",
            detectCmd = "test -f AGENTS.md && echo __OK__",
            installCmd = "cat > AGENTS.md <<'EOF'\n# Project Agent Guide\n\n## Stack\n- \n\n## Conventions\n- Keep commits focused.\n- Run tests after edits.\nEOF",
        ),
        VibeToolSpec(
            id = "memory-vault",
            name = "AI Memory Folder",
            group = ToolGroup.TEMPLATES,
            vendor = AiVendor.TMUX,
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
    onOpenTmuxTab: (String) -> Unit,
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
            title = { Text("Install ${tool.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Runs inside a dedicated tmux setup tab so your current session stays untouched:")
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
                        scope.launch {
                            val setupName = "setup-${tool.id}"
                            TmuxRemoteController.createDetachedSession(
                                bridge = bridge,
                                rawName = setupName,
                                preset = TmuxLaunchPreset.SHELL,
                                customCommand = tool.installCmd,
                            ).onSuccess {
                                onOpenTmuxTab(it)
                                onMessage("Opened setup tab '$it'")
                                onDismiss()
                            }.onFailure {
                                bridge.injectString("${tool.installCmd}\r")
                                onMessage("Running ${tool.name} setup in terminal")
                                onDismiss()
                            }
                        }
                    },
                ) {
                    Text("Install")
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
                    Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("AI Vibe Toolkit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleTools, key = { it.id }) { tool ->
                    val installed = installedMap[tool.id] == true
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (installed) tool.vendor.accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AiVendorBadge(vendor = tool.vendor, size = 32.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(tool.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            tool.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (installed) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Installed",
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Ready",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                } else {
                                    Text(
                                        if (scanning) "..." else "Missing",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val preset = tool.launchPreset
                                if (installed && preset != null) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                TmuxRemoteController.createDetachedSession(
                                                    bridge = bridge,
                                                    rawName = preset.defaultName,
                                                    preset = preset,
                                                ).onSuccess { name ->
                                                    onOpenTmuxTab(name)
                                                    onMessage("Opened ${tool.name} in tab '$name'")
                                                    onDismiss()
                                                }.onFailure { err ->
                                                    onMessage(err.message ?: "Failed to launch ${tool.name}")
                                                }
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Launch Tab")
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
                                            scope.launch {
                                                val authTab = "auth-${tool.id}"
                                                TmuxRemoteController.createDetachedSession(
                                                    bridge = bridge,
                                                    rawName = authTab,
                                                    preset = TmuxLaunchPreset.SHELL,
                                                    customCommand = auth,
                                                ).onSuccess {
                                                    onOpenTmuxTab(it)
                                                    onMessage("Opened auth tab '$it'")
                                                    onDismiss()
                                                }.onFailure {
                                                    bridge.injectString("$auth\r")
                                                    onDismiss()
                                                }
                                            }
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
