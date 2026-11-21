package org.connectbot.paullm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
    val yoloCommand: String?,
    val vendor: AiVendor,
) {
    CLAUDE("Claude", "claude", "claude", "claude --dangerously-skip-permissions", AiVendor.CLAUDE),
    CODEX("Codex", "codex", "codex", "codex --dangerously-bypass-approvals-and-sandbox", AiVendor.CODEX),
    GROK("Grok", "grok", "grok", "grok --always-approve", AiVendor.GROK),
    OPENCODE("OpenCode", "opencode", "opencode", "opencode --auto", AiVendor.OPENCODE),
    ANTIGRAVITY("Antigravity", "agy", "agy", "agy --dangerously-skip-permissions", AiVendor.ANTIGRAVITY),
    GEMINI("Gemini", "gemini", "gemini", "gemini --yolo --skip-trust", AiVendor.GEMINI),
    PI("Pi", "pi", "pi", "pi --approve", AiVendor.PI),
    AIDER("Aider", "aider", "aider", "aider --yes", AiVendor.AIDER),
    GOOSE("Goose", "goose", "goose", "goose", AiVendor.GOOSE),
    SHELL("Shell", "main", null, null, AiVendor.TMUX),
    ;

    fun resolvedCommand(yoloMode: Boolean): String? = if (yoloMode) yoloCommand ?: command else command
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

    /**
     * Creates a detached tmux session out-of-band via SSH exec so nothing is ever typed into another terminal tab.
     * Returns the sanitized session name ready to attach in its own dedicated PTY tab.
     */
    suspend fun createDetachedSession(
        bridge: TerminalBridge,
        rawName: String,
        preset: TmuxLaunchPreset,
        customCommand: String? = null,
        yoloMode: Boolean = true,
        workingDir: String? = null,
    ): Result<String> {
        val cleanName = rawName.trim()
            .ifEmpty { preset.defaultName }
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
        val quotedName = PaullmShell.quote(cleanName)
        val exactTarget = PaullmShell.quote("=$cleanName")
        val launchCmd = customCommand?.trim()?.takeIf { it.isNotEmpty() } ?: preset.resolvedCommand(yoloMode)
        val dirFlag = workingDir?.trim()?.takeIf { it.isNotEmpty() && it != "~" }?.let { " -c ${PaullmShell.quote(it)}" }.orEmpty()
        val createCmd = if (launchCmd != null) {
            "tmux new-session -d -s $quotedName$dirFlag ${PaullmShell.quote("$launchCmd; exec \${SHELL:-sh}")}"
        } else {
            "tmux new-session -d -s $quotedName$dirFlag"
        }
        val body = "if ! tmux has-session -t $exactTarget 2>/dev/null; then $createCmd; fi && echo __SESSION_OK__"
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(body), timeoutMs = 10_000L)
        return if (res.stdout.contains("__SESSION_OK__")) {
            Result.success(cleanName)
        } else {
            Result.failure(IllegalStateException(res.stderr.ifBlank { res.error ?: "Failed to create tmux session" }))
        }
    }

    suspend fun renameSession(bridge: TerminalBridge, oldName: String, newRawName: String): Result<String> {
        val cleanNew = newRawName.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")
        if (cleanNew.isEmpty()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        val body = "tmux rename-session -t ${PaullmShell.quote("=$oldName")} ${PaullmShell.quote(cleanNew)} && echo __OK__"
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(body), timeoutMs = 8_000L)
        return if (res.stdout.contains("__OK__")) {
            Result.success(cleanNew)
        } else {
            Result.failure(IllegalStateException(res.stderr.ifBlank { res.error ?: "Rename failed" }))
        }
    }

    suspend fun addWindow(bridge: TerminalBridge, sessionName: String): Result<Unit> {
        val body = "tmux new-window -t ${PaullmShell.quote("=$sessionName")} && echo __OK__"
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(body), timeoutMs = 8_000L)
        return if (res.stdout.contains("__OK__")) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(res.stderr.ifBlank { res.error ?: "Failed to create window" }))
        }
    }

    suspend fun killSession(bridge: TerminalBridge, sessionName: String): Result<Unit> {
        val body = "tmux kill-session -t ${PaullmShell.quote("=$sessionName")} && echo __OK__"
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(body), timeoutMs = 8_000L)
        return if (res.stdout.contains("__OK__")) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(res.stderr.ifBlank { res.error ?: "Kill failed" }))
        }
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
    openTmuxNames: Set<String>,
    onOpenTmuxTab: (String) -> Unit,
    onOpenNewShellTab: () -> Unit,
    onDetachCurrentTab: () -> Unit,
    onSessionsRefreshed: (List<TmuxSessionInfo>) -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var launching by remember { mutableStateOf(false) }
    var tmuxInstalled by remember { mutableStateOf(true) }
    var sessions by remember { mutableStateOf<List<TmuxSessionInfo>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var selectedPreset by remember { mutableStateOf(TmuxLaunchPreset.CLAUDE) }
    var newSessionName by remember { mutableStateOf(TmuxLaunchPreset.CLAUDE.defaultName) }
    var yoloMode by remember { mutableStateOf(true) }
    var workingDir by remember { mutableStateOf("~") }
    var customCmd by remember { mutableStateOf("") }

    var renameTarget by remember { mutableStateOf<TmuxSessionInfo?>(null) }
    var killTarget by remember { mutableStateOf<TmuxSessionInfo?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            errorText = null
            runCatching { TmuxRemoteController.discover(bridge) }
                .onSuccess { (installed, list) ->
                    tmuxInstalled = installed
                    sessions = list
                    onSessionsRefreshed(list)
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

    renameTarget?.let { target ->
        var editedName by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename tmux session") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("New session name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        renameTarget = null
                        scope.launch {
                            TmuxRemoteController.renameSession(bridge, target.name, editedName)
                                .onSuccess {
                                    onMessage("Renamed '${target.name}' to '$it'")
                                    refresh()
                                }
                                .onFailure { errorText = it.message }
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("Kill tmux session '${target.name}'?") },
            text = { Text("This terminates all processes running inside '${target.name}' on ${bridge.host.nickname}. Use Detach instead if you want to leave it running.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        killTarget = null
                        scope.launch {
                            TmuxRemoteController.killSession(bridge, target.name)
                                .onSuccess {
                                    onMessage("Killed tmux session '${target.name}'")
                                    refresh()
                                }
                                .onFailure { errorText = it.message }
                        }
                    },
                ) { Text("Kill session") }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
        )
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
                    AiVendorBadge(vendor = AiVendor.TMUX, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("tmux Sessions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            bridge.host.nickname,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bridge.tmuxSessionName != null) {
                        OutlinedButton(
                            onClick = {
                                onDetachCurrentTab()
                                onMessage("Detached client from '${bridge.tmuxSessionName}'")
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
                        Text("tmux is not installed on ${bridge.host.nickname}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Install tmux to run concurrent Claude, Codex, Grok, OpenCode, and shell sessions that survive disconnects.",
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
                        "No live tmux sessions on ${bridge.host.nickname}. Launch an AI or Shell session below — each opens in its own dedicated tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 250.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sessions, key = { it.name }) { session ->
                            val vendor = AiVendor.detectFromName(session.name, isTmux = true)
                            val isOpenTab = session.name in openTmuxNames
                            val isCurrent = bridge.tmuxSessionName == session.name
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenTmuxTab(session.name)
                                        onDismiss()
                                    },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    width = if (isCurrent) 1.5.dp else 1.dp,
                                    color = if (isCurrent) vendor.accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) {
                                        vendor.badgeBg
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AiVendorBadge(vendor = vendor, size = 34.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                session.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (isOpenTab) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                                ) {
                                                    Text(
                                                        if (isCurrent) "ACTIVE" else "TAB",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            buildString {
                                                append("${session.windowCount}w · ${session.attachedCount}c")
                                                session.currentPath?.let { append(" · $it") }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                TmuxRemoteController.addWindow(bridge, session.name)
                                                    .onSuccess {
                                                        onMessage("Added window to '${session.name}'")
                                                        refresh()
                                                    }
                                                    .onFailure { errorText = it.message }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Default.Tab, contentDescription = "New window", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { renameTarget = session },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { killTarget = session },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Kill session",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Launch AI / Shell Session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TextButton(
                        onClick = {
                            onOpenNewShellTab()
                            onDismiss()
                        },
                    ) {
                        Text("+ Plain Shell Tab")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TmuxLaunchPreset.entries.forEach { preset ->
                        val selected = selectedPreset == preset
                        Surface(
                            onClick = {
                                selectedPreset = preset
                                newSessionName = preset.defaultName
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) preset.vendor.badgeBg else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) preset.vendor.accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AiVendorLogo(vendor = preset.vendor, size = 20.dp)
                                Text(
                                    preset.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                if (selectedPreset.command != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("YOLO / Skip Permissions", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                            Text(
                                selectedPreset.resolvedCommand(yoloMode).orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = yoloMode,
                            onCheckedChange = { yoloMode = it },
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
                    OutlinedTextField(
                        value = workingDir,
                        onValueChange = { workingDir = it },
                        modifier = Modifier.weight(0.8f),
                        label = { Text("Start dir") },
                        singleLine = true,
                    )
                    Button(
                        enabled = !launching,
                        onClick = {
                            launching = true
                            scope.launch {
                                TmuxRemoteController.createDetachedSession(
                                    bridge = bridge,
                                    rawName = newSessionName,
                                    preset = selectedPreset,
                                    customCommand = customCmd,
                                    yoloMode = yoloMode,
                                    workingDir = workingDir,
                                ).onSuccess { createdName ->
                                    onOpenTmuxTab(createdName)
                                    onMessage("Opened '${createdName}' in dedicated tab")
                                    onDismiss()
                                }.onFailure { err ->
                                    errorText = err.message ?: "Failed to start session"
                                    launching = false
                                }
                            }
                        },
                    ) {
                        if (launching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start")
                        }
                    }
                }
            }
        }
    }
}
