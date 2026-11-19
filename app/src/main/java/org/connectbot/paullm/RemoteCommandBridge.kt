package org.connectbot.paullm

import kotlinx.coroutines.delay
import org.connectbot.service.TerminalBridge

data class SshExecResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean
        get() = error == null && (exitCode == null || exitCode == 0)
}

enum class TerminalSendMode(
    val id: String,
    val title: String,
    val detail: String,
) {
    ENTER(
        id = "enter",
        title = "Enter",
        detail = "Send text and press Return to run it.",
    ),
    AGENT(
        id = "agent",
        title = "Agent prompt",
        detail = "Bracketed-paste a multiline prompt to an AI CLI and submit it.",
    ),
    PASTE_SAFE(
        id = "paste_safe",
        title = "Paste-safe",
        detail = "Bracketed-paste multiline text without running each line.",
    ),
    RAW(
        id = "raw",
        title = "Raw",
        detail = "Send text exactly as typed with no trailing Return.",
    ),
    ;

    companion object {
        fun fromId(id: String?): TerminalSendMode = entries.firstOrNull { it.id == id } ?: ENTER
    }
}

object PaullmShell {
    const val PATH_EXPORT =
        "export PATH=\"\$HOME/.local/bin:\$HOME/.npm-global/bin:\$HOME/.cargo/bin:\$HOME/go/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\""

    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun wrapLoginShell(body: String): String = "sh -lc ${quote("$PATH_EXPORT; $body")}"

    private const val BRACKETED_PASTE_START = "\u001b[200~"
    private const val BRACKETED_PASTE_END = "\u001b[201~"

    fun sanitizeBracketedPaste(text: String): String =
        text.replace(BRACKETED_PASTE_END, "").replace(BRACKETED_PASTE_START, "")

    suspend fun sendComposeText(
        bridge: TerminalBridge,
        text: String,
        mode: TerminalSendMode,
    ) {
        val normalized = text.replace("\r\n", "\n")
        when (mode) {
            TerminalSendMode.RAW -> {
                bridge.injectString(normalized)
            }

            TerminalSendMode.ENTER -> {
                bridge.injectString(normalized.replace("\n", "\r") + "\r")
            }

            TerminalSendMode.PASTE_SAFE -> {
                val safe = sanitizeBracketedPaste(normalized)
                bridge.injectString("$BRACKETED_PASTE_START$safe$BRACKETED_PASTE_END")
            }

            TerminalSendMode.AGENT -> {
                val safe = sanitizeBracketedPaste(normalized)
                bridge.injectString("$BRACKETED_PASTE_START$safe$BRACKETED_PASTE_END")
                delay(90)
                bridge.injectString("\r")
            }
        }
    }
}
