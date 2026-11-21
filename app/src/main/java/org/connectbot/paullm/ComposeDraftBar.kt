package org.connectbot.paullm

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class QuickPromptItem(
    val label: String,
    val content: String,
)

object ComposeDraftStore {
    private val _drafts = MutableStateFlow<Map<UUID, String>>(emptyMap())
    val drafts: StateFlow<Map<UUID, String>> = _drafts.asStateFlow()

    private val _sendMode = MutableStateFlow(TerminalSendMode.ENTER)
    val sendMode: StateFlow<TerminalSendMode> = _sendMode.asStateFlow()

    val quickPrompts = listOf(
        QuickPromptItem(
            label = "Review git diff",
            content = "Inspect the current git diff for defects, edge cases, and missing tests. Report concrete findings with file and line numbers.",
        ),
        QuickPromptItem(
            label = "Propose plan first",
            content = "Before modifying code, inspect the relevant files and outline a brief plan listing the files you will update.",
        ),
        QuickPromptItem(
            label = "Project status",
            content = "Give a concise summary of current repo status, active blockers, and the next concrete steps.",
        ),
        QuickPromptItem(
            label = "Explain failure",
            content = "Analyze the latest error output, identify the root cause, and propose a minimal fix.",
        ),
    )

    fun updateDraft(sessionId: UUID, value: String) {
        _drafts.update { current -> current + (sessionId to value) }
    }

    fun appendDraft(sessionId: UUID, addition: String, separator: String = "") {
        _drafts.update { current ->
            val prev = current[sessionId].orEmpty()
            val merged = if (prev.isEmpty() || separator.isEmpty() || prev.endsWith(separator) || prev.endsWith("\n")) {
                prev + addition
            } else {
                prev + separator + addition
            }
            current + (sessionId to merged)
        }
    }

    fun clearDraft(sessionId: UUID) {
        _drafts.update { current -> current - sessionId }
    }

    fun setSendMode(mode: TerminalSendMode) {
        _sendMode.value = mode
    }
}

@Composable
fun PaullmComposeBar(
    sessionId: UUID,
    targetLabel: String,
    targetVendor: AiVendor,
    canSend: Boolean,
    onCollapse: () -> Unit,
    onOpenPresets: () -> Unit,
    onSend: (String, TerminalSendMode) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val allDrafts by ComposeDraftStore.drafts.collectAsState()
    val sendMode by ComposeDraftStore.sendMode.collectAsState()
    val draft = allDrafts[sessionId].orEmpty()

    var showModeMenu by remember { mutableStateOf(false) }
    var showPromptsMenu by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var partialSpeech by remember { mutableStateOf("") }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    val voiceIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                ComposeDraftStore.appendDraft(sessionId, spoken, separator = " ")
            }
        }
    }

    fun stopInAppRecognizer() {
        runCatching { speechRecognizer?.stopListening() }
        isListening = false
        partialSpeech = ""
    }

    fun launchSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate for $targetLabel")
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            val sr = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                speechRecognizer = it
            }
            sr.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        partialSpeech = "Listening..."
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        partialSpeech = ""
                        if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                            runCatching { voiceIntentLauncher.launch(intent) }.onFailure {
                                onMessage("Speech error ($error) — you can also tap the keyboard mic.")
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        partialSpeech = ""
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!spoken.isNullOrEmpty()) {
                            ComposeDraftStore.appendDraft(sessionId, spoken, separator = " ")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!partial.isNullOrEmpty()) {
                            partialSpeech = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )
            runCatching {
                isListening = true
                partialSpeech = "Listening..."
                sr.startListening(intent)
            }.onFailure {
                isListening = false
                partialSpeech = ""
                runCatching { voiceIntentLauncher.launch(intent) }.onFailure {
                    onMessage("Speech input unavailable — use your keyboard mic key.")
                }
            }
        } else {
            runCatching { voiceIntentLauncher.launch(intent) }.onFailure {
                onMessage("No speech recognizer installed — use your keyboard mic key.")
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchSpeechRecognition()
        } else {
            onMessage("Microphone permission denied — allow mic access or use keyboard dictation.")
        }
    }

    fun startVoiceDictation() {
        if (isListening) {
            stopInAppRecognizer()
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            launchSpeechRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun pasteIntoDraft() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        if (!text.isNullOrEmpty()) {
            ComposeDraftStore.appendDraft(sessionId, text)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HorizontalDivider(color = targetVendor.accent.copy(alpha = 0.35f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiVendorLogo(vendor = targetVendor, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Sending to $targetLabel",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = targetVendor.accent,
                    )
                }
                Text(
                    text = sendMode.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isListening || partialSpeech.isNotEmpty()) {
                Text(
                    text = "🎙 $partialSpeech",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { ComposeDraftStore.updateDraft(sessionId, it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Compose prompt or command for $targetLabel...") },
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Hide composer")
                }

                FilledTonalIconButton(
                    onClick = ::startVoiceDictation,
                    modifier = Modifier.size(36.dp),
                    colors = if (isListening) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    },
                ) {
                    Icon(
                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop dictation" else "Dictate",
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = ::pasteIntoDraft,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }

                    IconButton(
                        onClick = onOpenPresets,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Command presets")
                    }

                    Box {
                        IconButton(
                            onClick = { showPromptsMenu = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Quick prompts")
                        }
                        DropdownMenu(
                            expanded = showPromptsMenu,
                            onDismissRequest = { showPromptsMenu = false },
                        ) {
                            ComposeDraftStore.quickPrompts.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        showPromptsMenu = false
                                        ComposeDraftStore.appendDraft(sessionId, item.content, separator = "\n")
                                    },
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { ComposeDraftStore.appendDraft(sessionId, "\n") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = "Insert newline")
                    }

                    if (draft.isNotEmpty()) {
                        IconButton(
                            onClick = { ComposeDraftStore.clearDraft(sessionId) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear draft")
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { showModeMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(sendMode.title, style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                    ) {
                        TerminalSendMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(mode.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            mode.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    showModeMenu = false
                                    ComposeDraftStore.setSendMode(mode)
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val payload = draft
                        if (payload.isNotBlank() && canSend) {
                            onSend(payload, sendMode)
                            ComposeDraftStore.clearDraft(sessionId)
                        }
                    },
                    enabled = draft.isNotBlank() && canSend,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Send")
                }
            }
        }
    }
}
