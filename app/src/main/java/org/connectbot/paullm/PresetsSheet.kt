package org.connectbot.paullm

import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CommandPreset(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val command: String,
    val builtIn: Boolean = false,
)

object CommandPresetStore {
    private const val PREF_KEY = "paullm_saved_presets_json"

    private val defaultPresets = listOf(
        CommandPreset("builtin-git", "Git status & diff stat", "git status -sb && git diff --stat", builtIn = true),
        CommandPreset("builtin-log", "Recent git commits", "git log -n 10 --oneline --decorate", builtIn = true),
        CommandPreset("builtin-tmux", "List tmux sessions", "tmux list-sessions", builtIn = true),
        CommandPreset("builtin-docker", "Running containers", "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'", builtIn = true),
        CommandPreset("builtin-sys", "Disk & memory check", "df -h / && free -h", builtIn = true),
        CommandPreset("builtin-ports", "Listening TCP ports", "ss -tulpn 2>/dev/null || netstat -tuln", builtIn = true),
    )

    fun load(context: Context): List<CommandPreset> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(PREF_KEY, null)
        val custom = mutableListOf<CommandPreset>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    custom.add(
                        CommandPreset(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            title = obj.optString("title", "Command"),
                            command = obj.optString("command", ""),
                            builtIn = false,
                        ),
                    )
                }
            }
        }
        return custom + defaultPresets
    }

    fun add(context: Context, title: String, command: String): List<CommandPreset> {
        val current = load(context).filterNot { it.builtIn }
        val updated = listOf(CommandPreset(title = title.trim(), command = command.trim())) + current
        saveCustom(context, updated)
        return updated + defaultPresets
    }

    fun delete(context: Context, id: String): List<CommandPreset> {
        val updated = load(context).filterNot { it.builtIn || it.id == id }
        saveCustom(context, updated)
        return updated + defaultPresets
    }

    private fun saveCustom(context: Context, items: List<CommandPreset>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("command", item.command)
                },
            )
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_KEY, arr.toString())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsSheet(
    targetLabel: String,
    onRunCommand: (String) -> Unit,
    onInsertToComposer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(CommandPresetStore.load(context)) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        var title by remember { mutableStateOf("") }
        var cmd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Command Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Label") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        label = { Text("Command") },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = title.isNotBlank() && cmd.isNotBlank(),
                    onClick = {
                        presets = CommandPresetStore.add(context, title, cmd)
                        showAddDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
        )
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
                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Command Presets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Target: $targetLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presets, key = { it.id }) { preset ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(preset.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (!preset.builtIn) {
                                    IconButton(
                                        onClick = { presets = CommandPresetStore.delete(context, preset.id) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete preset",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                preset.command,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        onRunCommand(preset.command)
                                        onDismiss()
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Run")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onInsertToComposer(preset.command)
                                        onDismiss()
                                    },
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Compose")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
