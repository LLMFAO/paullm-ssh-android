package org.connectbot.paullm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.connectbot.service.TerminalBridge
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class CosBackend(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    AGY_FLASH("agy_flash", "AGY Flash"),
    AGY_ORCH("agy_orch", "AGY Orch"),
    CODEX("codex", "Codex 6"),
    GROK("grok", "Grok 4.5"),
    OLLAMA_CLOUD("ollama_cloud", "Cloud Flash"),
    OLLAMA("ollama", "Local"),
    CLAUDE("claude", "Claude"),
}

enum class SessionUrgency(val wireName: String, val label: String, val rank: Int) {
    NEEDS_DECISION("NEEDS_DECISION", "NEEDS DECISION", 0),
    BLOCKED("BLOCKED", "BLOCKED", 1),
    WAITING_INPUT("WAITING_INPUT", "WAITING", 2),
    RUNNING("RUNNING", "RUNNING", 3),
    DONE("DONE", "DONE", 4),
    IDLE("IDLE", "IDLE", 5),
    ;

    companion object {
        fun parse(raw: String?): SessionUrgency {
            val norm = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.wireName == norm } ?: IDLE
        }
    }
}

enum class CosActionType(val wireName: String) {
    SEND("SEND"),
    START_TMUX("START_TMUX"),
    KILL_TMUX("KILL_TMUX"),
    RENAME_TMUX("RENAME_TMUX"),
    ;

    companion object {
        fun parse(raw: String?): CosActionType = when (raw?.trim()?.uppercase()) {
            "START_TMUX", "START" -> START_TMUX
            "KILL_TMUX", "KILL", "END_TMUX", "END" -> KILL_TMUX
            "RENAME_TMUX", "RENAME" -> RENAME_TMUX
            else -> SEND
        }
    }
}

data class CosAction(
    val id: String = UUID.randomUUID().toString(),
    val actionType: CosActionType,
    val label: String,
    val session: String,
    val sendText: String = "",
    val command: String = "",
    val cwd: String = "",
    val newName: String = "",
    val risky: Boolean = false,
) {
    val requiresPermissionDialog: Boolean
        get() = actionType != CosActionType.SEND || risky || looksRiskyText(sendText)

    companion object {
        private val RISKY_REGEX = Regex(
            """(?i)\b(rm\s+-|rm\b|rmdir|del\b|delete|drop\s+table|drop\s+database|truncate\b|force|reset\s+--hard|clean\s+-fd|sudo\b|kill\b|pkill|killall|reboot|shutdown|poweroff|deploy|publish|release|push\s+--force|push\s+-f|mkfs|dd\s+if=)""",
        )

        fun looksRiskyText(text: String): Boolean = RISKY_REGEX.containsMatchIn(text)
    }
}

data class CosSessionDecision(
    val session: String,
    val vendor: AiVendor,
    val urgency: SessionUrgency,
    val summary: String,
    val decisionNeeded: String?,
    val suggestedActions: List<CosAction>,
)

data class CosVaultRef(
    val title: String,
    val path: String,
    val perliteUrl: String,
    val relevance: String = "",
)

data class CosExecutedReceipt(
    val actionType: CosActionType,
    val session: String,
    val summary: String,
    val ok: Boolean = true,
    val error: String? = null,
    val previousSession: String? = null,
)

data class CosLearnedNote(
    val file: String,
    val summary: String,
)

data class CosBriefingPayload(
    val headline: String,
    val reply: String,
    val sessions: List<CosSessionDecision>,
    val orchestratorActions: List<CosAction>,
    val executedReceipts: List<CosExecutedReceipt> = emptyList(),
    val learnedNotes: List<CosLearnedNote> = emptyList(),
    val vaultRefs: List<CosVaultRef> = emptyList(),
    val backendUsed: String,
    val modelUsed: String,
    val conversationId: String?,
    val scannedCount: Int,
    val didFullTmuxScan: Boolean = false,
    val vaultNotesChecked: Int = 0,
    val promptChars: Int = 0,
    val generatedAtMs: Long = System.currentTimeMillis(),
) {
    /**
     * Unified, deduplicated action plan for this turn:
     * preserves distinct clickable choices/decisions per session while removing exact duplicates.
     */
    val actionPlan: List<CosAction> by lazy {
        val raw = buildList {
            addAll(orchestratorActions)
            sessions.forEach { addAll(it.suggestedActions) }
        }
        if (raw.isEmpty()) return@lazy emptyList()

        val sends = mutableListOf<CosAction>()
        val seenSends = mutableSetOf<String>()
        val lifecycle = mutableListOf<CosAction>()
        val seenLifecycle = mutableSetOf<String>()

        raw.forEach { act ->
            when (act.actionType) {
                CosActionType.SEND -> {
                    val sig = "${act.session.trim().lowercase()}:${act.sendText.trim().lowercase()}"
                    if (seenSends.add(sig)) {
                        sends.add(act)
                    }
                }

                CosActionType.START_TMUX, CosActionType.KILL_TMUX, CosActionType.RENAME_TMUX -> {
                    val sig = "${act.actionType.wireName}:${act.session.trim().lowercase()}:${act.newName.trim().lowercase()}:${act.command.trim()}"
                    if (seenLifecycle.add(sig)) {
                        lifecycle.add(act)
                    }
                }
            }
        }
        sends + lifecycle
    }

    val decisionCount: Int
        get() = sessions.count { it.urgency == SessionUrgency.NEEDS_DECISION }
    val blockedCount: Int
        get() = sessions.count { it.urgency == SessionUrgency.BLOCKED }
    val attentionCount: Int
        get() = sessions.count {
            it.urgency == SessionUrgency.NEEDS_DECISION || it.urgency == SessionUrgency.BLOCKED
        }
}

data class CosChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val briefing: CosBriefingPayload? = null,
    val error: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

object ChiefOfStaffEngine {
    const val REMOTE_AGENTS_PATH = "~/.config/paullm-cos/AGENTS.md"
    const val REMOTE_MEMORY_PATH = "~/.config/paullm-cos/MEMORY.md"

    val DEFAULT_MEMORY_MD = """
        # Chief of Staff Working Memory — MEMORY.md

        Persistent notes, preferences, project specifics, and recent decisions learned from Paul's interactions and canonical vault on `delllap`.

        ## Paul's Preferences
        - Act like a sharp conversational Chief of Staff—natural, concise, big-picture first (not `tmux`-myopic).
        - Execute explicit chat requests immediately without nagging for extra approval; reserve approval cards for proactive suggestions only.
        - Consolidate multiple instructions for the same `tmux` session into one clear prompt.
        - Only do full `tmux` scans when asked (or via Brief Now); keep regular chat fast and focused.

        ## Active Projects & Notes
        - (Updated automatically from interactions and `delllap:/home/paul/ObsidianVault_Final`.)
    """.trimIndent()

    val DEFAULT_AGENTS_MD = """
        # Chief of Staff Orchestrator — AGENTS.md

        You are Paul's executive Chief of Staff & strategic assistant across his `tmux` sessions and canonical Obsidian vault (`delllap:/home/paul/ObsidianVault_Final`).

        ## Stay Strictly on Focus (CRITICAL)
        - **Only address what Paul is focused on right now.** Do NOT re-inspect unrelated `tmux` sessions, do NOT re-crawl the vault, and do NOT spawn subagents on routine or single-session turns.
        - **Full analysis ONLY when asked:** Populate `sessions` across all sessions and propose broad next steps ONLY when `full_scan=true` or Paul explicitly asks for a full state/briefing/next-steps review.
        - Write `reply` in crisp, natural prose/bullets (never dump raw JSON inside `reply`).

        ## Direct Execution vs Clickable Decisions & Suggestions
        - **`execute_now` (runs immediately, no approval prompt):** Put actions Paul explicitly ordered in chat here (`SEND`, `START_TMUX`, `KILL_TMUX`, `RENAME_TMUX`).
          - **Rename:** `action_type: "RENAME_TMUX"`, `session` = current name, `new_name` = requested name.
          - **Consolidate per session:** Merge multiple instructions for the SAME session into ONE `SEND`.
          - For `START_TMUX`, use verified YOLO flags when appropriate (`codex --dangerously-bypass-approvals-and-sandbox`, `agy --dangerously-skip-permissions`, `opencode --auto`, `claude --dangerously-skip-permissions`, `grok --always-approve`).
        - **Clickable Decisions & Next-Step Replies (`sessions[].suggested_actions` / `orchestrator_actions`):**
          - Whenever you inspect a specific session (or run a full scan), ALWAYS include that session in `sessions` with its `status`, `decision_needed` (if waiting on a choice), and **1–3 concrete clickable `suggested_actions`** (ready-to-send replies, approvals, or logical next commands for that session) so Paul can tap to respond without typing!
          - Keep suggested actions scoped strictly to the session(s) Paul asked about.

        ## Lean Tool & Subagent Use
        - On routine focused turns, answer directly from the provided context in one turn (zero extra tool calls).
        - Only when deeper vault research or multi-session analysis is actually requested (e.g. `full_scan=true` or Paul asks to dig into `delllap`), delegate noisy reads to a cheap `flash_lite` / `flash` subagent or one targeted read-only `ssh -o BatchMode=yes delllap ...` command so your main context stays thin.
        - Never edit/delete vault notes unless asked. Cite used vault notes in `vault_refs` with Perlite URLs: `https://delllap.tailfaa66b.ts.net/wiki/?link=/<path-without-.md>`.

        ## Working Memory (`~/.config/paullm-cos/`)
        - Emit concise `memory_updates` (`MEMORY.md`, `AGENTS.md`, or `notes/<project>.md`) only when Paul shares a durable preference, correction, or key decision.
    """.trimIndent()

    private val PYTHON_RUNNER = """
import base64, json, os, pathlib, re, shutil, subprocess, sys, time, urllib.request

ANSI_RE = re.compile(r'\x1b(?:\[[0-9;?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[@-Z\\-_])')

SCHEMA = {
    "type": "object",
    "properties": {
        "headline": {"type": "string"},
        "reply": {"type": "string"},
        "sessions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "session": {"type": "string"},
                    "status": {"type": "string", "enum": ["NEEDS_DECISION", "BLOCKED", "WAITING_INPUT", "RUNNING", "DONE", "IDLE"]},
                    "summary": {"type": "string"},
                    "decision_needed": {"type": "string"},
                    "suggested_actions": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "action_type": {"type": "string", "enum": ["SEND", "START_TMUX", "KILL_TMUX", "RENAME_TMUX"]},
                                "label": {"type": "string"},
                                "session": {"type": "string"},
                                "send": {"type": "string"},
                                "command": {"type": "string"},
                                "cwd": {"type": "string"},
                                "new_name": {"type": "string"},
                                "risky": {"type": "boolean"}
                            },
                            "required": ["action_type", "label", "risky"]
                        }
                    }
                },
                "required": ["session", "status", "summary", "suggested_actions"]
            }
        },
        "execute_now": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "action_type": {"type": "string", "enum": ["SEND", "START_TMUX", "KILL_TMUX", "RENAME_TMUX"]},
                    "label": {"type": "string"},
                    "session": {"type": "string"},
                    "send": {"type": "string"},
                    "command": {"type": "string"},
                    "cwd": {"type": "string"},
                    "new_name": {"type": "string"}
                },
                "required": ["action_type", "session"]
            }
        },
        "orchestrator_actions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "action_type": {"type": "string", "enum": ["SEND", "START_TMUX", "KILL_TMUX", "RENAME_TMUX"]},
                    "label": {"type": "string"},
                    "session": {"type": "string"},
                    "send": {"type": "string"},
                    "command": {"type": "string"},
                    "cwd": {"type": "string"},
                    "new_name": {"type": "string"},
                    "risky": {"type": "boolean"}
                },
                "required": ["action_type", "label", "session", "risky"]
            }
        },
        "memory_updates": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "file": {"type": "string"},
                    "mode": {"type": "string", "enum": ["append", "replace"]},
                    "summary": {"type": "string"},
                    "content": {"type": "string"}
                },
                "required": ["file", "mode", "summary", "content"]
            }
        },
        "vault_refs": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "path": {"type": "string"},
                    "perlite_url": {"type": "string"},
                    "relevance": {"type": "string"}
                },
                "required": ["title", "path", "perlite_url"]
            }
        }
    },
    "required": ["headline", "reply", "sessions"]
}

def run_cmd(cmd, timeout=5, cwd=None):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, cwd=cwd)
        return p.stdout
    except Exception:
        return ""

def extract_json(text):
    text = (text or "").strip()
    if not text:
        raise ValueError("Empty model response")
    try:
        return json.loads(text)
    except Exception:
        pass
    m = re.search(r'```(?:json)?\s*(\{.*?\})\s*```', text, re.S)
    if m:
        return json.loads(m.group(1))
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        return json.loads(text[start:end + 1])
    raise ValueError("Could not parse JSON from model output: " + text[:240])

def shorten_cmd(raw):
    raw = (raw or "").strip()
    if not raw:
        return ""
    parts = raw.split()
    head = os.path.basename(parts[0])
    rest = " ".join(parts[1:])
    out = f"{head} {rest}".strip() if rest else head
    return out[:60]

def shorten_path(path):
    home = str(pathlib.Path.home())
    if path == home:
        return "~"
    if path.startswith(home + "/"):
        return "~/" + path[len(home) + 1:]
    return path

def session_mentioned(name, low_msg):
    if not name or not low_msg:
        return False
    low_name = name.lower()
    if len(low_name) >= 3 and low_name in low_msg:
        return True
    return bool(re.search(r'(?<![a-z0-9_-])' + re.escape(low_name) + r'(?![a-z0-9_-])', low_msg))

def detect_intent(user_msg, explicit_full_scan=False):
    low_q = (user_msg or "").lower()
    full_triggers = (
        "full scan", "full analysis", "scan all", "all tmux", "all sessions",
        "brief me", "briefing", "tmux status", "what's running", "whats running",
        "current state of", "state of tmux", "next steps across", "overview of all",
    )
    full_scan = bool(explicit_full_scan) or any(k in low_q for k in full_triggers)
    vault_triggers = (
        "vault", "obsidian", "delllap", "perlite", "priorit", "roadmap",
        "research", "runbook", "what should i work on", "big picture",
        "vault updates", "open decisions",
    )
    wants_vault = full_scan or any(k in low_q for k in vault_triggers)
    deep_triggers = (
        "deep dive", "full analysis", "compare", "cross-check", "strategy",
        "next steps", "priorit", "research",
    )
    wants_deep = full_scan or wants_vault or any(k in low_q for k in deep_triggers)
    return full_scan, wants_vault, wants_deep

def capture_tmux(full_scan=False, user_msg=""):
    panes_out = run_cmd([
        "tmux", "list-panes", "-a", "-F",
        "#{session_name}\t#{pane_id}\t#{pane_current_command}\t#{pane_pid}\t#{pane_current_path}\t#{pane_title}\t#{session_attached}\t#{session_windows}"
    ])
    ps_out = run_cmd(["ps", "-eo", "ppid=,args="])
    children = {}
    for line in ps_out.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            short = shorten_cmd(parts[1])
            if short:
                children.setdefault(parts[0], []).append(short)

    low_msg = (user_msg or "").lower()
    snap = []
    seen = set()
    for line in panes_out.splitlines():
        p = line.split("\t")
        if len(p) < 5 or not p[0] or p[0] in seen:
            continue
        seen.add(p[0])
        name, pane_id, cmd, pid, cwd = p[0], p[1], p[2], p[3], p[4]
        title = (p[5] if len(p) > 5 else "").strip()
        attached = p[6] if len(p) > 6 else "0"
        windows = p[7] if len(p) > 7 else "1"

        named = session_mentioned(name, low_msg)
        should_capture_pane = full_scan or named
        tail = ""
        branch = ""
        if should_capture_pane:
            max_lines = 25 if named else 20
            max_chars = 1400 if named else 900
            raw = run_cmd(["tmux", "capture-pane", "-t", pane_id, "-p", "-J", "-S", "-60"])
            clean_lines = [ANSI_RE.sub("", l).rstrip() for l in raw.splitlines()]
            clean_lines = [l for l in clean_lines if l.strip()]
            tail = "\n".join(clean_lines[-max_lines:])[-max_chars:]
            if cwd and os.path.isdir(cwd):
                branch = run_cmd(["git", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD"], timeout=2).strip()
        entry = {
            "session": name,
            "cmd": cmd,
            "cwd": shorten_path(cwd),
        }
        c_cmds = children.get(pid, [])[:2]
        if c_cmds:
            entry["child_cmds"] = c_cmds
        if title and title != cmd and title != name:
            entry["title"] = title[:60]
        if attached == "1":
            entry["attached"] = "1"
        if windows != "1":
            entry["windows"] = windows
        if branch:
            entry["git_branch"] = branch
        if tail:
            entry["recent_output"] = tail
        snap.append(entry)
    return snap

def send_to_tmux_pane(session_name, text):
    target = f"={session_name}:"
    p = subprocess.run(["tmux", "display-message", "-p", "-t", target, "#{pane_id}\t#{pane_in_mode}"], capture_output=True, text=True, timeout=5)
    if p.returncode != 0 or not p.stdout.strip():
        raise RuntimeError(f"tmux session '{session_name}' not found")
    parts = p.stdout.strip().split("\t")
    pane_id = parts[0]
    in_mode = parts[1] if len(parts) > 1 else "0"
    if in_mode == "1":
        subprocess.run(["tmux", "send-keys", "-t", pane_id, "-X", "cancel"], capture_output=True, timeout=5)
        time.sleep(0.12)
    clean = (text or "").replace("\r\n", "\n").strip("\n")
    if not clean:
        raise RuntimeError("Empty text to send")
    if "\n" in clean:
        buf_name = f"cos_{int(time.time() * 1000)}"
        subprocess.run(["tmux", "load-buffer", "-b", buf_name, "-"], input=clean, text=True, check=True, timeout=5)
        subprocess.run(["tmux", "paste-buffer", "-p", "-d", "-b", buf_name, "-t", pane_id], check=True, timeout=5)
    else:
        subprocess.run(["tmux", "send-keys", "-t", pane_id, "-l", "--", clean], check=True, timeout=5)
    time.sleep(0.30)
    subprocess.run(["tmux", "send-keys", "-t", pane_id, "C-m"], check=True, timeout=5)
    return pane_id

def execute_requested_actions(raw_actions):
    if not isinstance(raw_actions, list) or not raw_actions:
        return []
    # Consolidate multiple SEND actions targeting the same session into ONE prompt
    send_groups = {}
    send_order = []
    lifecycle = []
    for a in raw_actions:
        if not isinstance(a, dict):
            continue
        atype = str(a.get("action_type", "SEND")).upper().strip()
        sess = str(a.get("session", "")).strip()
        if not sess:
            continue
        if atype == "SEND":
            txt = str(a.get("send", "")).strip()
            if not txt:
                continue
            key = sess.lower()
            if key not in send_groups:
                send_groups[key] = {"session": sess, "texts": [], "labels": []}
                send_order.append(key)
            if txt not in send_groups[key]["texts"]:
                send_groups[key]["texts"].append(txt)
                lbl = str(a.get("label", "")).strip()
                if lbl:
                    send_groups[key]["labels"].append(lbl)
        elif atype == "RENAME_TMUX":
            new_name = str(a.get("new_name") or a.get("command") or "").strip()
            lifecycle.append((atype, sess, new_name, "", str(a.get("label", "")).strip()))
        elif atype in ("START_TMUX", "KILL_TMUX"):
            lifecycle.append((atype, sess, str(a.get("command", "")).strip(), str(a.get("cwd", "")).strip(), str(a.get("label", "")).strip()))

    receipts = []
    for key in send_order:
        g = send_groups[key]
        sess = g["session"]
        texts = g["texts"]
        # Join cleanly without numbering so both plain shells and AI CLIs execute cleanly
        combined = texts[0] if len(texts) == 1 else "\n".join(texts)
        preview = (combined.replace("\n", " ; "))[:90]
        try:
            send_to_tmux_pane(sess, combined)
            receipts.append({"action_type": "SEND", "session": sess, "summary": preview, "ok": True})
        except Exception as e:
            receipts.append({"action_type": "SEND", "session": sess, "summary": preview, "ok": False, "error": str(e)[:180]})

    for atype, sess, cmd_str, cwd_str, lbl in lifecycle:
        clean_name = re.sub(r"[^A-Za-z0-9._-]", "-", sess)
        try:
            if atype == "START_TMUX":
                tmux_cmd = ["tmux", "new-session", "-d", "-s", clean_name]
                if cwd_str and cwd_str != "~":
                    expanded = os.path.expanduser(cwd_str)
                    if os.path.isdir(expanded):
                        tmux_cmd.extend(["-c", expanded])
                if cmd_str:
                    tmux_cmd.append(f"{cmd_str}; exec ${'$'}{{SHELL:-bash}}")
                subprocess.run(tmux_cmd, check=True, capture_output=True, text=True, timeout=10)
                receipts.append({"action_type": "START_TMUX", "session": clean_name, "summary": cmd_str or "new shell", "ok": True})
            elif atype == "KILL_TMUX":
                subprocess.run(["tmux", "kill-session", "-t", f"={sess}"], check=True, capture_output=True, text=True, timeout=10)
                receipts.append({"action_type": "KILL_TMUX", "session": sess, "summary": lbl or "ended session", "ok": True})
            elif atype == "RENAME_TMUX":
                clean_new = re.sub(r"[^A-Za-z0-9._-]", "-", cmd_str).strip("-")
                if not clean_new:
                    raise RuntimeError("new session name is empty")
                subprocess.run(["tmux", "rename-session", "-t", f"={sess}", clean_new], check=True, capture_output=True, text=True, timeout=10)
                receipts.append({"action_type": "RENAME_TMUX", "session": clean_new, "previous_session": sess, "summary": f"{sess} -> {clean_new}", "ok": True})
        except Exception as e:
            receipts.append({"action_type": atype, "session": sess, "summary": lbl or atype, "ok": False, "error": str(e)[:180]})
    return receipts

def load_cos_memory(cos_dir, user_msg, default_memory_md, include_base=True):
    mem_path = cos_dir / "MEMORY.md"
    if not mem_path.exists() and default_memory_md:
        mem_path.write_text(default_memory_md, encoding="utf-8")
    notes_dir = cos_dir / "notes"
    notes_dir.mkdir(parents=True, exist_ok=True)
    memory_txt = mem_path.read_text(encoding="utf-8", errors="ignore")[:2500] if (include_base and mem_path.exists()) else ""
    note_files = sorted(notes_dir.glob("*.md"), key=lambda p: p.stat().st_mtime, reverse=True)
    note_index = [p.name for p in note_files[:15]] if include_base else []
    low_msg = (user_msg or "").lower()
    matched_notes = {}
    for nf in note_files[:15]:
        stem = nf.stem.lower()
        if len(stem) >= 3 and stem in low_msg and len(matched_notes) < 2:
            matched_notes[f"notes/{nf.name}"] = nf.read_text(encoding="utf-8", errors="ignore")[:900]
    out = {}
    if memory_txt:
        out["memory_md"] = memory_txt
    if note_index:
        out["note_files"] = note_index
    if matched_notes:
        out["note_excerpts"] = matched_notes
    return out

def apply_memory_updates(cos_dir, updates):
    if not isinstance(updates, list) or not updates:
        return []
    learned = []
    cos_resolved = cos_dir.resolve()
    for u in updates[:4]:
        if not isinstance(u, dict):
            continue
        rel = str(u.get("file", "")).strip().lstrip("/")
        mode = str(u.get("mode", "append")).strip().lower()
        summary = str(u.get("summary", "")).strip()[:120]
        content = str(u.get("content", "")).strip()
        if not rel or not content or not rel.endswith(".md"):
            continue
        if rel not in ("MEMORY.md", "AGENTS.md") and not rel.startswith("notes/"):
            rel = "notes/" + re.sub(r"[^A-Za-z0-9._-]", "-", pathlib.Path(rel).name)
        target = (cos_dir / rel).resolve()
        if not str(target).startswith(str(cos_resolved) + os.sep):
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            if mode == "replace":
                target.write_text(content + "\n", encoding="utf-8")
            else:
                existing = target.read_text(encoding="utf-8", errors="ignore") if target.exists() else ""
                if content not in existing:
                    sep = "\n\n" if existing.strip() else ""
                    target.write_text(existing.rstrip() + sep + content + "\n", encoding="utf-8")
            learned.append({"file": rel, "summary": summary or f"Updated {rel}"})
        except Exception:
            pass
    return learned

def capture_vault_context(user_msg, full_scan=False, wants_vault=False):
    # Only query canonical vault on delllap when explicitly relevant or on full_scan
    if not wants_vault:
        return {"available": False, "skipped": True, "recent": [], "excerpts": []}
    vault_root = "/home/paul/ObsidianVault_Final"
    perlite_base = "https://delllap.tailfaa66b.ts.net/wiki/?link=/"
    terms = set()
    stop = {"the", "and", "for", "with", "that", "this", "from", "what", "which", "are", "all", "tmux", "session", "sessions", "brief", "briefing", "status", "right", "now", "about", "into", "your", "have", "vault", "delllap", "check", "give", "chief", "staff", "next", "steps", "current", "state", "full", "scan", "analysis"}
    for tok in re.findall(r"[A-Za-z0-9]{3,}", (user_msg or "").lower()):
        if tok not in stop:
            terms.add(tok)
    low_q = (user_msg or "").lower()
    include_recent_fallback = full_scan or any(k in low_q for k in ("priorit", "recent", "update", "overview", "big picture", "what should i work on"))
    terms_b64 = base64.b64encode(json.dumps(sorted(terms)[:16]).encode()).decode()

    remote_py = f'''
import base64, json, os, pathlib, re
root = pathlib.Path({vault_root!r})
if not root.is_dir():
    print(json.dumps({{"available": False, "recent": [], "excerpts": []}}))
    raise SystemExit(0)
terms = json.loads(base64.b64decode({terms_b64!r}).decode())
include_recent = {include_recent_fallback!r}
files = []
for sub in ["INBOX/Agent-Updates", "PROJECTS", "."]:
    base = root / sub if sub != "." else root
    if not base.is_dir():
        continue
    max_depth = 3 if sub != "." else 1
    for dirpath, dirnames, filenames in os.walk(base):
        rel_dir = os.path.relpath(dirpath, base)
        depth = 0 if rel_dir == "." else rel_dir.count(os.sep) + 1
        dirnames[:] = [d for d in dirnames if not d.startswith(".") and d not in ("ARCHIVE-BACKUP", ".trash", "node_modules")]
        if depth >= max_depth:
            dirnames[:] = []
        for fn in filenames:
            if not fn.endswith(".md"):
                continue
            fp = pathlib.Path(dirpath) / fn
            try:
                mt = fp.stat().st_mtime
                rel = fp.relative_to(root).as_posix()
                files.append((mt, rel, fp))
            except Exception:
                pass
files.sort(key=lambda x: x[0], reverse=True)
seen = set()
dedup = []
for mt, rel, fp in files:
    if rel not in seen:
        seen.add(rel)
        dedup.append((mt, rel, fp))

recent = [{{"path": rel, "perlite_url": {perlite_base!r} + rel[:-3]}} for _, rel, _ in dedup[:6]] if include_recent else []

scored = []
for idx, (mt, rel, fp) in enumerate(dedup[:120]):
    low = rel.lower()
    hits = sum(2 for t in terms if t in low)
    if hits > 0 or (include_recent and idx < 2):
        scored.append((hits, -idx, rel, fp))
scored.sort(reverse=True)

excerpts = []
for _, _, rel, fp in scored[:3]:
    try:
        txt = fp.read_text(encoding="utf-8", errors="ignore")
        if txt.startswith("---"):
            end_fm = txt.find("\\n---", 3)
            if end_fm != -1:
                txt = txt[end_fm + 4:]
        lines = [l.rstrip() for l in txt.strip().splitlines() if l.strip()]
        title = next((l.lstrip("# ").strip() for l in lines[:4] if l.startswith("#")), pathlib.Path(rel).stem)
        excerpt = "\\n".join(lines[:14])[:500]
        excerpts.append({{
            "title": title[:80],
            "path": rel,
            "perlite_url": {perlite_base!r} + rel[:-3],
            "excerpt": excerpt,
        }})
    except Exception:
        pass
print(json.dumps({{"available": True, "recent": recent, "excerpts": excerpts}}))
'''
    host = run_cmd(["hostname"]).strip().lower()
    if host.startswith("delllap") and os.path.isdir(vault_root):
        cmd = ["python3", "-c", remote_py]
    else:
        cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=4", "delllap", "python3 -"]
    try:
        if cmd[0] == "ssh":
            p = subprocess.run(cmd, input=remote_py, capture_output=True, text=True, timeout=8)
        else:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=6)
        if p.returncode == 0 and p.stdout.strip():
            return json.loads(p.stdout.strip().splitlines()[-1])
    except Exception as e:
        return {"available": False, "error": str(e), "recent": [], "excerpts": []}
    return {"available": False, "recent": [], "excerpts": []}

def resolve_agy_bin():
    home = pathlib.Path.home()
    preferred = [
        home / ".local/share/mise/installs/antigravity-cli/latest/agy",
        home / ".local/share/mise/shims/agy",
        home / ".local/bin/agy",
    ]
    for p in preferred:
        if p.exists() and os.access(p, os.X_OK):
            return str(p)
    return shutil.which("agy")

def try_agy(model, prompt, schema_path, cos_dir, conv_id, allow_tools=False, timeout_s=75):
    agy_bin = resolve_agy_bin()
    if not agy_bin:
        raise RuntimeError("agy CLI not found")
    cmd = [
        agy_bin, "-p", prompt,
        "--model", model,
        "--output-format", "json",
        "--json-schema", str(schema_path),
        "--disable-slash-commands",
        "--print-timeout", f"{timeout_s}s",
    ]
    if allow_tools:
        cmd.append("--dangerously-skip-permissions")
    if conv_id:
        cmd.extend(["--conversation", conv_id])
    proc = subprocess.run(cmd, cwd=str(cos_dir), capture_output=True, text=True, timeout=timeout_s + 10)
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or proc.stdout or f"agy exit {proc.returncode}").strip()[:300])
    env = json.loads(proc.stdout)
    so = env.get("structured_output")
    if not isinstance(so, dict):
        so = extract_json(env.get("response", ""))
    return so, "agy", model, env.get("conversation_id")

def try_ollama(system_prompt, user_prompt):
    base = "http://127.0.0.1:11434"
    candidates = []
    try:
        with urllib.request.urlopen(base + "/api/ps", timeout=3) as r:
            for m in json.loads(r.read().decode()).get("models", []):
                name = m.get("name", "")
                if name and "embed" not in name.lower():
                    candidates.append((name, 55))
    except Exception:
        pass
    try:
        with urllib.request.urlopen(base + "/api/tags", timeout=3) as r:
            models = json.loads(r.read().decode()).get("models", [])
    except Exception as e:
        raise RuntimeError(f"Ollama unreachable: {e}")

    # Fast-fail cloud check first (429 fails in ~0.2s)
    for pref in ["kimi-k3:cloud", "glm-5.3:cloud"]:
        if any(m.get("name") == pref for m in models) and all(c[0] != pref for c in candidates):
            candidates.append((pref, 12))

    # Then GPU-sized local models (< 9.5 GB) sorted by preference
    local = []
    for m in models:
        name = m.get("name", "")
        size = int(m.get("size") or 0)
        low = name.lower()
        if not name or "embed" in low or ":cloud" in low or size > 9_500_000_000:
            continue
        score = 50
        if "qwen2.5-coder-7b" in low: score = 1
        elif "qwen3.8-9b" in low: score = 2
        elif "qwen3:8b" in low: score = 3
        elif "qwen2.5-coder" in low: score = 4
        elif "gemma4" in low: score = 5
        local.append((score, name))
    for _, name in sorted(local):
        if all(c[0] != name for c in candidates):
            candidates.append((name, 60))

    last_err = "No usable Ollama models found"
    for model, to in candidates[:5]:
        try:
            req = urllib.request.Request(
                base + "/api/chat",
                data=json.dumps({
                    "model": model,
                    "stream": False,
                    "format": "json",
                    "options": {"temperature": 0.2, "num_ctx": 16384},
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                }).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=to) as r:
                body = json.loads(r.read().decode())
                parsed = extract_json(body.get("message", {}).get("content", ""))
                return parsed, "ollama", model, None
        except Exception as e:
            last_err = f"{model}: {e}"
    raise RuntimeError(last_err)

def try_cli(bin_name, args_fn, prompt, cos_dir, timeout_s=65, model_label=None):
    path = shutil.which(bin_name)
    if not path:
        for extra in (pathlib.Path.home() / ".local/bin" / bin_name, pathlib.Path.home() / ".grok/bin" / bin_name):
            if extra.exists() and os.access(extra, os.X_OK):
                path = str(extra)
                break
    if not path:
        raise RuntimeError(f"{bin_name} not found")
    proc = subprocess.run(args_fn(path, prompt), cwd=str(cos_dir), capture_output=True, text=True, timeout=timeout_s)
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or proc.stdout or f"{bin_name} exit {proc.returncode}").strip()[:280])
    return extract_json(proc.stdout), bin_name, (model_label or bin_name), None

def try_ollama_cloud(system_prompt, user_prompt, cos_dir):
    # Fast direct Ollama Cloud API via keydeck (1-3s vs ~45s opencode cold start), with opencode fallback
    keydeck_bin = shutil.which("keydeck") or str(pathlib.Path.home() / ".local/bin/keydeck")
    flash_models = ["minimax-m3.1-flash", "glm-5.3-flash", "deepseek-v4.1-flash"]
    last_err = "Ollama Cloud unavailable"
    if os.path.exists(keydeck_bin) and os.access(keydeck_bin, os.X_OK):
        helper_py = (
            "import json, os, sys, urllib.request\n"
            "req_data = json.loads(sys.stdin.read())\n"
            "key = os.environ.get('OLLAMA_API_KEY', '')\n"
            "if not key: raise SystemExit('OLLAMA_API_KEY missing')\n"
            "req = urllib.request.Request('https://ollama.com/api/chat', data=json.dumps(req_data).encode(), "
            "headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key})\n"
            "with urllib.request.urlopen(req, timeout=35) as r:\n"
            "    print(r.read().decode())\n"
        )
        for model in flash_models:
            try:
                body = {
                    "model": model,
                    "stream": False,
                    "format": "json",
                    "options": {"temperature": 0.2},
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                }
                proc = subprocess.run(
                    [keydeck_bin, "use", "ollama-cloud", "--", "python3", "-c", helper_py],
                    input=json.dumps(body),
                    cwd=str(cos_dir),
                    capture_output=True,
                    text=True,
                    timeout=45,
                )
                if proc.returncode == 0 and proc.stdout.strip():
                    resp = json.loads(proc.stdout.strip().splitlines()[-1])
                    content = resp.get("message", {}).get("content", "")
                    parsed = extract_json(content)
                    return parsed, "ollama-cloud", model, None
                last_err = f"{model}: {(proc.stderr or proc.stdout).strip()[:180]}"
            except Exception as e:
                last_err = f"{model}: {e}"
    # Fallback to opencode run with Ollama Cloud flash model
    oc_bin = shutil.which("opencode") or str(pathlib.Path.home() / ".local/bin/opencode")
    if os.path.exists(oc_bin) and os.access(oc_bin, os.X_OK):
        cmd_prefix = [keydeck_bin, "use", "ollama-cloud", "--"] if (os.path.exists(keydeck_bin) and os.access(keydeck_bin, os.X_OK)) else []
        proc = subprocess.run(
            cmd_prefix + [oc_bin, "run", "--pure", "-m", "ollama-cloud/minimax-m3.1-flash", system_prompt + "\n\n" + user_prompt],
            cwd=str(cos_dir),
            capture_output=True,
            text=True,
            timeout=75,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            return extract_json(proc.stdout), "opencode", "minimax-m3.1-flash", None
        last_err = f"opencode: {(proc.stderr or proc.stdout).strip()[:180]}"
    raise RuntimeError(last_err)

COMPACT_SCHEMA_HINT = (
    '{"headline":"short title","reply":"concise natural prose",'
    '"sessions":[{"session":"name","status":"NEEDS_DECISION|BLOCKED|WAITING_INPUT|RUNNING|DONE|IDLE","summary":"...","decision_needed":"...","suggested_actions":[]}],'
    '"execute_now":[{"action_type":"SEND|START_TMUX|KILL_TMUX|RENAME_TMUX","session":"name","send":"...","command":"...","cwd":"...","new_name":"...","label":"..."}],'
    '"orchestrator_actions":[{"action_type":"SEND|START_TMUX|KILL_TMUX|RENAME_TMUX","label":"...","session":"name","send":"...","command":"...","cwd":"...","new_name":"...","risky":false}],'
    '"memory_updates":[{"file":"MEMORY.md","mode":"append|replace","summary":"...","content":"..."}],'
    '"vault_refs":[{"title":"...","path":"...","perlite_url":"https://delllap.tailfaa66b.ts.net/wiki/?link=/...","relevance":"..."}]}'
)

def main():
    payload = json.loads(base64.b64decode(sys.argv[1]).decode("utf-8"))
    user_msg = payload.get("prompt", "Give me a Chief of Staff briefing.")
    backend = payload.get("backend", "auto")
    conv_id = payload.get("conversation_id") or ""
    history = payload.get("history") or []
    default_agents = payload.get("default_agents_md", "")
    default_memory = payload.get("default_memory_md", "")
    full_scan, wants_vault, wants_deep = detect_intent(user_msg, bool(payload.get("full_scan", False)))

    cos_dir = pathlib.Path.home() / ".config/paullm-cos"
    cos_dir.mkdir(parents=True, exist_ok=True)
    agents_path = cos_dir / "AGENTS.md"
    if not agents_path.exists() and default_agents:
        agents_path.write_text(default_agents, encoding="utf-8")
    agents_md = agents_path.read_text(encoding="utf-8")[:3500] if agents_path.exists() else default_agents[:3500]
    schema_path = cos_dir / "briefing-schema.json"
    schema_path.write_text(json.dumps(SCHEMA, indent=2), encoding="utf-8")

    snap = capture_tmux(full_scan=full_scan, user_msg=user_msg)
    vault_ctx = capture_vault_context(user_msg, full_scan=full_scan, wants_vault=wants_vault)
    has_vault_data = bool(vault_ctx.get("excerpts") or vault_ctx.get("recent"))

    contract_rules = (
        f"MODE: full_scan={str(full_scan).lower()}.\n"
        "- Stay strictly on Paul's current focus; do NOT re-inspect unrelated sessions or vault notes.\n"
        "- Put explicit user commands in `execute_now` (merge multiple sends per session into ONE SEND; renames use RENAME_TMUX + new_name).\n"
        "- Whenever inspecting a specific session (or full_scan=true), ALWAYS include that session in `sessions` with `status`, `decision_needed` (if any), and 1-3 concrete clickable `suggested_actions` (ready-to-send replies or next-step commands) so Paul can tap to respond!\n"
        "- Put additional proactive suggestions (not explicitly ordered) in `orchestrator_actions`.\n"
        "- Emit `memory_updates` only for durable preferences/decisions; cite used vault notes in `vault_refs`."
    )

    tmux_label = "TMUX FULL SNAPSHOT" if full_scan else "TMUX INDEX (pane tails included ONLY if named)"
    tmux_json = json.dumps(snap, separators=(",", ":"))

    # 1) Lean prompt for agy (no verbose JSON schema; delta-only when continuing --conversation)
    if conv_id:
        delta_mem = load_cos_memory(cos_dir, user_msg, default_memory, include_base=False)
        agy_parts = [
            f"FOLLOW-UP TURN (full_scan={str(full_scan).lower()}). Stay strictly on Paul's current focus; do not re-analyze unrelated sessions. If inspecting a session, include it in `sessions` with 1-3 clickable `suggested_actions` for likely replies/next steps.",
            "USER REQUEST:\n" + user_msg,
            f"{tmux_label}:\n{tmux_json}",
        ]
        if delta_mem:
            agy_parts.append("MATCHED NOTES:\n" + json.dumps(delta_mem, separators=(",", ":")))
        if has_vault_data:
            agy_parts.append("VAULT CONTEXT (delllap):\n" + json.dumps(vault_ctx, separators=(",", ":")))
        agy_prompt = "\n\n".join(agy_parts)
    else:
        mem_ctx = load_cos_memory(cos_dir, user_msg, default_memory, include_base=True)
        agy_parts = [
            agents_md.strip(),
            contract_rules,
        ]
        if mem_ctx:
            agy_parts.append("MEMORY:\n" + json.dumps(mem_ctx, separators=(",", ":")))
        agy_parts.append("USER REQUEST:\n" + user_msg)
        agy_parts.append(f"{tmux_label}:\n{tmux_json}")
        if has_vault_data:
            agy_parts.append("VAULT CONTEXT (delllap):\n" + json.dumps(vault_ctx, separators=(",", ":")))
        agy_prompt = "\n\n".join(agy_parts)

    # 2) Stateless prompt for ollama / pi / claude / codex (compact schema + short history)
    stateless_mem = load_cos_memory(cos_dir, user_msg, default_memory, include_base=True)
    system_prompt = (
        agents_md.strip() + "\n\n"
        "OUTPUT CONTRACT: Return ONLY valid JSON matching:\n"
        + COMPACT_SCHEMA_HINT + "\n"
        + contract_rules
    )
    hist_block = ""
    if history:
        lines = [f"- {h.get('role','user').upper()}: {str(h.get('text',''))[:280]}" for h in history[-4:]]
        hist_block = "RECENT HISTORY:\n" + "\n".join(lines) + "\n\n"
    stateless_parts = [hist_block + "USER REQUEST:\n" + user_msg, f"{tmux_label}:\n{tmux_json}"]
    if stateless_mem:
        stateless_parts.insert(0, "MEMORY:\n" + json.dumps(stateless_mem, separators=(",", ":")))
    if has_vault_data:
        stateless_parts.append("VAULT CONTEXT (delllap):\n" + json.dumps(vault_ctx, separators=(",", ":")))
    user_prompt = "\n\n".join(stateless_parts)
    combined_prompt = system_prompt + "\n\n" + user_prompt

    errors = []
    result = None
    used_prompt_chars = len(agy_prompt)

    auto_order = (
        ["agy_orch_neon", "agy_flash", "codex", "grok", "ollama_cloud", "ollama"]
        if (full_scan or wants_deep)
        else ["agy_flash", "codex", "grok", "ollama_cloud", "ollama"]
    )
    order = {
        "auto": auto_order,
        "agy_flash": ["agy_flash"],
        "agy_orch": ["agy_orch_neon", "agy_orch_argon", "agy_neon_low"],
        "codex": ["codex"],
        "grok": ["grok"],
        "ollama_cloud": ["ollama_cloud"],
        "ollama": ["ollama"],
        "claude": ["claude"],
    }.get(backend, auto_order)

    for step in order:
        try:
            if step == "agy_flash":
                used_prompt_chars = len(agy_prompt)
                result = try_agy("gemini-3.8-flash-low", agy_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=80)
            elif step == "agy_orch_neon":
                used_prompt_chars = len(agy_prompt)
                result = try_agy("gemini-pro-orchestrator-neon", agy_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=145)
            elif step == "agy_orch_argon":
                used_prompt_chars = len(agy_prompt)
                result = try_agy("gemini-pro-orchestrator-g1-pro", agy_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=145)
            elif step == "agy_neon_low":
                used_prompt_chars = len(agy_prompt)
                result = try_agy("gemini-4-neon-low", agy_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=85)
            elif step == "codex":
                used_prompt_chars = len(combined_prompt)
                result = try_cli(
                    "codex",
                    lambda p, pr: [p, "exec", "--skip-git-repo-check", "-m", "gpt-6-luna", "-c", 'model_reasoning_effort="low"', pr],
                    combined_prompt,
                    cos_dir,
                    timeout_s=75,
                    model_label="gpt-6-luna",
                )
            elif step == "grok":
                used_prompt_chars = len(combined_prompt)
                result = try_cli(
                    "grok",
                    lambda p, pr: [p, "--model", "grok-4.5", "--disable-web-search", "-p", pr],
                    combined_prompt,
                    cos_dir,
                    timeout_s=75,
                    model_label="grok-4.5",
                )
            elif step == "ollama_cloud":
                used_prompt_chars = len(combined_prompt)
                result = try_ollama_cloud(system_prompt, user_prompt, cos_dir)
            elif step == "ollama":
                used_prompt_chars = len(combined_prompt)
                result = try_ollama(system_prompt, user_prompt)
            elif step == "claude":
                used_prompt_chars = len(combined_prompt)
                result = try_cli("claude", lambda p, pr: [p, "-p", pr], combined_prompt, cos_dir, model_label="claude")
            if result is not None:
                break
        except Exception as e:
            errors.append(f"{step}: {e}")

    if result is None:
        print(json.dumps({"ok": False, "error": " | ".join(errors) or "No AI backend succeeded", "scanned_count": len(snap)}))
        return

    parsed, used_backend, used_model, next_conv_id = result
    executed_receipts = execute_requested_actions(parsed.get("execute_now"))
    learned_notes = apply_memory_updates(cos_dir, parsed.get("memory_updates"))

    print(json.dumps({
        "ok": True,
        "backend": used_backend,
        "model": used_model,
        "conversation_id": next_conv_id,
        "scanned_count": len(snap),
        "did_full_scan": full_scan,
        "vault_notes_checked": len(vault_ctx.get("excerpts") or []),
        "prompt_chars": used_prompt_chars,
        "executed_receipts": executed_receipts,
        "learned_notes": learned_notes,
        "snapshot_meta": {
            s["session"]: {
                "cmd": s.get("cmd", ""),
                "child_cmds": s.get("child_cmds", []),
                "title": s.get("title", ""),
            }
            for s in snap
        },
        "data": parsed,
    }))

if __name__ == "__main__":
    main()
""".trimIndent()

    suspend fun loadCosFile(bridge: TerminalBridge, fileName: String): Result<String> {
        val safeFile = if (fileName == "MEMORY.md") "MEMORY.md" else "AGENTS.md"
        val seed = if (safeFile == "MEMORY.md") DEFAULT_MEMORY_MD else DEFAULT_AGENTS_MD
        val seedB64 = Base64.encodeToString(seed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = """
            mkdir -p "${'$'}HOME/.config/paullm-cos"
            if [ ! -f "${'$'}HOME/.config/paullm-cos/$safeFile" ]; then
              printf '%s' '$seedB64' | base64 -d > "${'$'}HOME/.config/paullm-cos/$safeFile"
            fi
            cat "${'$'}HOME/.config/paullm-cos/$safeFile"
        """.trimIndent()
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = 10_000L)
        return if (res.error != null) {
            Result.failure(IllegalStateException(res.error))
        } else {
            Result.success(res.stdout)
        }
    }

    suspend fun saveCosFile(bridge: TerminalBridge, fileName: String, content: String): Result<Unit> {
        val safeFile = if (fileName == "MEMORY.md") "MEMORY.md" else "AGENTS.md"
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = """
            mkdir -p "${'$'}HOME/.config/paullm-cos"
            printf '%s' '$b64' | base64 -d > "${'$'}HOME/.config/paullm-cos/$safeFile.tmp"
            mv "${'$'}HOME/.config/paullm-cos/$safeFile.tmp" "${'$'}HOME/.config/paullm-cos/$safeFile"
        """.trimIndent()
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = 10_000L)
        return if (!res.isSuccess) {
            Result.failure(IllegalStateException(res.error ?: res.stderr.ifBlank { "Failed to save $safeFile" }))
        } else {
            Result.success(Unit)
        }
    }

    suspend fun sendToTmuxSession(
        bridge: TerminalBridge,
        sessionName: String,
        text: String,
    ): Result<Unit> {
        val reqObj = JSONObject().apply {
            put("session", sessionName)
            put("text", text)
        }
        val b64 = Base64.encodeToString(reqObj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val py = """
            import base64, json, subprocess, sys, time
            d = json.loads(base64.b64decode(sys.argv[1]).decode('utf-8'))
            sess, text = d['session'], d['text']
            p = subprocess.run(['tmux', 'display-message', '-p', '-t', f'={sess}:', '#{pane_id}\t#{pane_in_mode}'], capture_output=True, text=True, timeout=5)
            if p.returncode != 0 or not p.stdout.strip():
                sys.stderr.write(p.stderr.strip() or f'tmux session {sess} not found')
                sys.exit(1)
            parts = p.stdout.strip().split('\t')
            pane_id = parts[0]
            if len(parts) > 1 and parts[1] == '1':
                subprocess.run(['tmux', 'send-keys', '-t', pane_id, '-X', 'cancel'], capture_output=True, timeout=5)
                time.sleep(0.12)
            clean = text.replace('\r\n', '\n').strip('\n')
            if '\n' in clean:
                buf = f'cos_{int(time.time()*1000)}'
                subprocess.run(['tmux', 'load-buffer', '-b', buf, '-'], input=clean, text=True, check=True, timeout=5)
                subprocess.run(['tmux', 'paste-buffer', '-p', '-d', '-b', buf, '-t', pane_id], check=True, timeout=5)
            else:
                subprocess.run(['tmux', 'send-keys', '-t', pane_id, '-l', '--', clean], check=True, timeout=5)
            time.sleep(0.30)
            subprocess.run(['tmux', 'send-keys', '-t', pane_id, 'C-m'], check=True, timeout=5)
        """.trimIndent()
        val pyB64 = Base64.encodeToString(py.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = "python3 -c \"\$(printf '%s' '$pyB64' | base64 -d)\" '$b64'"
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = 12_000L)
        return if (!res.isSuccess) {
            Result.failure(
                IllegalStateException(
                    res.error ?: res.stderr.ifBlank { "tmux send failed for '$sessionName'" },
                ),
            )
        } else {
            Result.success(Unit)
        }
    }

    suspend fun runTurn(
        bridge: TerminalBridge,
        prompt: String,
        backend: CosBackend,
        conversationId: String?,
        recentHistory: List<CosChatMessage>,
        knownSessions: List<TmuxSessionInfo>,
        fullScan: Boolean = false,
    ): Result<CosBriefingPayload> {
        val histArray = JSONArray()
        recentHistory.takeLast(6).forEach { msg ->
            histArray.put(
                JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("text", msg.briefing?.let { "${it.headline}\n${it.reply}" } ?: msg.text)
                },
            )
        }
        val reqObj = JSONObject().apply {
            put("prompt", prompt)
            put("backend", backend.id)
            put("conversation_id", conversationId.orEmpty())
            put("history", histArray)
            put("default_agents_md", DEFAULT_AGENTS_MD)
            put("default_memory_md", DEFAULT_MEMORY_MD)
            put("full_scan", fullScan)
        }
        val reqB64 = Base64.encodeToString(reqObj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val runnerB64 = Base64.encodeToString(PYTHON_RUNNER.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val script = """
            mkdir -p "${'$'}HOME/.config/paullm-cos"
            printf '%s' '$runnerB64' | base64 -d > "${'$'}HOME/.config/paullm-cos/cos_runner.py"
            python3 "${'$'}HOME/.config/paullm-cos/cos_runner.py" '$reqB64'
        """.trimIndent()

        val timeoutMs = if (backend == CosBackend.AGY_ORCH || (backend == CosBackend.AUTO && fullScan)) 165_000L else 105_000L
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = timeoutMs)
        if (res.error != null) {
            return Result.failure(IllegalStateException(res.error))
        }
        val outLine = res.stdout.trim().lineSequence().lastOrNull { it.trim().startsWith("{") }
            ?: return Result.failure(
                IllegalStateException(
                    res.stderr.trim().ifBlank { res.stdout.trim().ifBlank { "No response from Chief of Staff" } }.take(300),
                ),
            )

        return runCatching {
            val env = JSONObject(outLine)
            if (!env.optBoolean("ok", false)) {
                throw IllegalStateException(env.optString("error", "Chief of Staff failed"))
            }
            val data = env.getJSONObject("data")
            val snapMeta = env.optJSONObject("snapshot_meta")
            val knownByName = knownSessions.associateBy { it.name }

            fun resolveVendor(sessionName: String): AiVendor {
                knownByName[sessionName]?.let { return it.detectedVendor }
                val meta = snapMeta?.optJSONObject(sessionName)
                if (meta != null) {
                    val cmd = meta.optString("cmd")
                    val fromCmd = AiVendor.detectFromName(cmd, isTmux = false)
                    if (fromCmd != AiVendor.SHELL && fromCmd != AiVendor.TMUX) return fromCmd
                    val children = meta.optJSONArray("child_cmds")
                    if (children != null) {
                        for (i in 0 until children.length()) {
                            val fromChild = AiVendor.detectFromName(children.optString(i), isTmux = false)
                            if (fromChild != AiVendor.SHELL && fromChild != AiVendor.TMUX) return fromChild
                        }
                    }
                    val fromTitle = AiVendor.detectFromName(meta.optString("title"), isTmux = false)
                    if (fromTitle != AiVendor.SHELL && fromTitle != AiVendor.TMUX) return fromTitle
                }
                return AiVendor.detectFromName(sessionName, isTmux = true)
            }

            fun parseActions(arr: JSONArray?, fallbackSession: String): List<CosAction> {
                if (arr == null) return emptyList()
                val out = mutableListOf<CosAction>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val type = CosActionType.parse(obj.optString("action_type", "SEND"))
                    val targetSession = obj.optString("session").trim().ifEmpty { fallbackSession }
                    val send = obj.optString("send").trim()
                    val command = obj.optString("command").trim()
                    val cwd = obj.optString("cwd").trim()
                    val newName = obj.optString("new_name").trim().ifEmpty {
                        if (type == CosActionType.RENAME_TMUX) command else ""
                    }
                    var label = obj.optString("label").trim()
                    if (label.isEmpty() && type == CosActionType.RENAME_TMUX && newName.isNotEmpty()) {
                        label = "Rename to $newName"
                    }
                    if (label.isEmpty()) continue
                    val risky = obj.optBoolean("risky", type != CosActionType.SEND && type != CosActionType.RENAME_TMUX)
                    if (type == CosActionType.SEND && (send.isEmpty() || targetSession.isEmpty())) continue
                    if ((type == CosActionType.START_TMUX || type == CosActionType.KILL_TMUX) && targetSession.isEmpty()) continue
                    if (type == CosActionType.RENAME_TMUX && (targetSession.isEmpty() || newName.isEmpty())) continue
                    out.add(
                        CosAction(
                            actionType = type,
                            label = label,
                            session = targetSession,
                            sendText = send,
                            command = command,
                            cwd = cwd,
                            newName = newName,
                            risky = risky,
                        ),
                    )
                }
                return out
            }

            val parsedSessions = mutableListOf<CosSessionDecision>()
            val sessionsArr = data.optJSONArray("sessions")
            if (sessionsArr != null) {
                for (i in 0 until sessionsArr.length()) {
                    val s = sessionsArr.optJSONObject(i) ?: continue
                    val sName = s.optString("session").trim()
                    if (sName.isEmpty()) continue
                    val urgency = SessionUrgency.parse(s.optString("status"))
                    val summary = s.optString("summary").trim()
                    val decision = s.optString("decision_needed").trim()
                        .takeIf { raw ->
                            raw.isNotEmpty() &&
                                !raw.equals("null", ignoreCase = true) &&
                                !raw.equals("none", ignoreCase = true) &&
                                !raw.equals("n/a", ignoreCase = true) &&
                                !raw.startsWith("none;", ignoreCase = true) &&
                                !raw.startsWith("none.", ignoreCase = true) &&
                                !raw.startsWith("none —", ignoreCase = true) &&
                                !raw.startsWith("none -", ignoreCase = true)
                        }
                    val actions = parseActions(s.optJSONArray("suggested_actions"), sName)
                    parsedSessions.add(
                        CosSessionDecision(
                            session = sName,
                            vendor = resolveVendor(sName),
                            urgency = urgency,
                            summary = summary,
                            decisionNeeded = decision,
                            suggestedActions = actions,
                        ),
                    )
                }
            }
            parsedSessions.sortWith(compareBy<CosSessionDecision> { it.urgency.rank }.thenBy { it.session.lowercase() })

            val orchActions = parseActions(data.optJSONArray("orchestrator_actions"), "")
            val parsedVaultRefs = mutableListOf<CosVaultRef>()
            val vaultArr = data.optJSONArray("vault_refs")
            if (vaultArr != null) {
                for (i in 0 until vaultArr.length()) {
                    val v = vaultArr.optJSONObject(i) ?: continue
                    val title = v.optString("title").trim()
                    val path = v.optString("path").trim()
                    val rawUrl = v.optString("perlite_url").trim()
                    if (title.isEmpty() && path.isEmpty()) continue
                    val perliteUrl = rawUrl.ifEmpty {
                        val cleanPath = path.removePrefix("/").removeSuffix(".md")
                        "https://delllap.tailfaa66b.ts.net/wiki/?link=/$cleanPath"
                    }
                    parsedVaultRefs.add(
                        CosVaultRef(
                            title = title.ifEmpty { path.substringAfterLast('/').removeSuffix(".md") },
                            path = path,
                            perliteUrl = perliteUrl,
                            relevance = v.optString("relevance").trim(),
                        ),
                    )
                }
            }

            val parsedExecuted = mutableListOf<CosExecutedReceipt>()
            val execArr = env.optJSONArray("executed_receipts")
            if (execArr != null) {
                for (i in 0 until execArr.length()) {
                    val r = execArr.optJSONObject(i) ?: continue
                    val sess = r.optString("session").trim()
                    if (sess.isEmpty()) continue
                    parsedExecuted.add(
                        CosExecutedReceipt(
                            actionType = CosActionType.parse(r.optString("action_type")),
                            session = sess,
                            summary = r.optString("summary").trim(),
                            ok = r.optBoolean("ok", true),
                            error = r.optString("error").trim().takeIf { it.isNotEmpty() && it != "null" },
                            previousSession = r.optString("previous_session").trim().takeIf { it.isNotEmpty() && it != "null" },
                        ),
                    )
                }
            }

            val parsedLearned = mutableListOf<CosLearnedNote>()
            val learnedArr = env.optJSONArray("learned_notes")
            if (learnedArr != null) {
                for (i in 0 until learnedArr.length()) {
                    val l = learnedArr.optJSONObject(i) ?: continue
                    val f = l.optString("file").trim()
                    if (f.isEmpty()) continue
                    parsedLearned.add(
                        CosLearnedNote(
                            file = f,
                            summary = l.optString("summary").trim().ifEmpty { "Updated $f" },
                        ),
                    )
                }
            }

            CosBriefingPayload(
                headline = data.optString("headline").trim().ifEmpty { "Briefing complete" },
                reply = data.optString("reply").trim(),
                sessions = parsedSessions,
                orchestratorActions = orchActions,
                executedReceipts = parsedExecuted,
                learnedNotes = parsedLearned,
                vaultRefs = parsedVaultRefs,
                backendUsed = env.optString("backend", backend.label),
                modelUsed = env.optString("model", backend.label),
                conversationId = env.optString("conversation_id").takeIf { it.isNotBlank() && it != "null" },
                scannedCount = env.optInt("scanned_count", parsedSessions.size),
                didFullTmuxScan = env.optBoolean("did_full_scan", fullScan),
                vaultNotesChecked = env.optInt("vault_notes_checked", parsedVaultRefs.size),
                promptChars = env.optInt("prompt_chars", 0),
            )
        }
    }
}

private val CosBg = Color(0xFF070A10)
private val CosCardBg = Color(0xFF141720)
private val CosCardBorder = Color(0xFF252B3B)
private val CosCyan = Color(0xFF38BDF8)
private val CosGreen = Color(0xFF22C55E)
private val CosAmber = Color(0xFFF59E0B)
private val CosRed = Color(0xFFEF4444)
private val CosMuted = Color(0xFF94A3B8)

private fun urgencyColor(urgency: SessionUrgency): Color = when (urgency) {
    SessionUrgency.NEEDS_DECISION -> CosAmber
    SessionUrgency.BLOCKED -> CosRed
    SessionUrgency.WAITING_INPUT -> Color(0xFFFBBF24)
    SessionUrgency.RUNNING -> CosCyan
    SessionUrgency.DONE -> CosGreen
    SessionUrgency.IDLE -> CosMuted
}

private val CosInlineMdRegex = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\*([^*]+)\*""")
private val CosHeadingRegex = Regex("""^#{1,6}\s+(.+)$""")
private val CosBulletRegex = Regex("""^(\s*)[-*]\s+(.+)$""")

private fun renderCosMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    val lines = raw.replace("\r\n", "\n").split("\n")
    lines.forEachIndexed { idx, line ->
        if (idx > 0) append('\n')
        val heading = CosHeadingRegex.matchEntire(line.trim())
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                appendInlineMarkdown(heading.groupValues[1])
            }
            return@forEachIndexed
        }
        val bullet = CosBulletRegex.matchEntire(line)
        if (bullet != null) {
            append(bullet.groupValues[1])
            withStyle(SpanStyle(color = CosCyan, fontWeight = FontWeight.Bold)) {
                append("• ")
            }
            appendInlineMarkdown(bullet.groupValues[2])
            return@forEachIndexed
        }
        appendInlineMarkdown(line)
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    var cursor = 0
    for (m in CosInlineMdRegex.findAll(text)) {
        if (m.range.first > cursor) {
            append(text.substring(cursor, m.range.first))
        }
        val bold = m.groups[1]?.value
        val code = m.groups[2]?.value
        val italic = m.groups[3]?.value
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                append(bold)
            }
            code != null -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = CosCyan,
                    background = Color(0xFF0F172A),
                ),
            ) {
                append(" $code ")
            }
            italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(italic)
            }
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

@Composable
fun ChiefOfStaffDashboardCard(
    latestBriefing: CosBriefingPayload?,
    isBusy: Boolean,
    sessionCount: Int,
    onOpenBriefing: () -> Unit,
    onRunBriefingNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CosCardBg,
        border = BorderStroke(
            width = 1.dp,
            color = if ((latestBriefing?.attentionCount ?: 0) > 0) {
                CosAmber.copy(alpha = 0.55f)
            } else {
                CosCyan.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = CosCyan,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Chief of Staff",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CosCyan)
                    } else {
                        Surface(
                            onClick = onRunBriefingNow,
                            shape = RoundedCornerShape(999.dp),
                            color = CosCyan.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, CosCyan.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = CosCyan, modifier = Modifier.size(13.dp))
                                Text(
                                    text = if (latestBriefing == null) "Brief Now" else "Refresh",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CosCyan,
                                )
                            }
                        }
                    }
                }
            }

            if (latestBriefing == null) {
                Text(
                    text = "On-demand remote orchestrator ($sessionCount tmux sessions) · driven by ~/.config/paullm-cos/AGENTS.md",
                    style = MaterialTheme.typography.bodySmall,
                    color = CosMuted,
                )
            } else {
                Text(
                    text = latestBriefing.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE2E8F0),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (latestBriefing.decisionCount > 0) {
                            CosCountPill("${latestBriefing.decisionCount} Decision", CosAmber)
                        }
                        if (latestBriefing.blockedCount > 0) {
                            CosCountPill("${latestBriefing.blockedCount} Blocked", CosRed)
                        }
                        val runningCount = latestBriefing.sessions.count { it.urgency == SessionUrgency.RUNNING }
                        if (runningCount > 0) {
                            CosCountPill("$runningCount Running", CosCyan)
                        }
                        if (latestBriefing.attentionCount == 0 && runningCount == 0) {
                            CosCountPill("${latestBriefing.scannedCount} Scanned", CosGreen)
                        }
                    }
                    Surface(
                        onClick = onOpenBriefing,
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xFF1E293B),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Open Chat",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = CosCyan,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CosCountPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
fun ChiefOfStaffChatView(
    bridge: TerminalBridge,
    disconnected: Boolean,
    knownSessions: List<TmuxSessionInfo>,
    liveSessions: List<CosSessionDecision>,
    messages: List<CosChatMessage>,
    selectedBackend: CosBackend,
    isRunning: Boolean,
    onSelectBackend: (CosBackend) -> Unit,
    onSendPrompt: (String) -> Unit,
    onRunFullScan: () -> Unit,
    onClearChat: () -> Unit,
    onJumpToSession: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onActionsExecuted: (List<CosAction>) -> Unit,
    onSessionsChanged: () -> Unit,
    onSessionRenamed: (String, String) -> Unit = { _, _ -> },
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember(bridge.host.id) { mutableStateOf("") }
    var showAgentsDialog by remember { mutableStateOf(false) }
    var activeCosDocTab by remember { mutableStateOf("AGENTS.md") }
    var agentsContent by remember { mutableStateOf("") }
    var agentsLoading by remember { mutableStateOf(false) }
    var agentsSaving by remember { mutableStateOf(false) }
    var pendingBatchPermission by remember { mutableStateOf<List<CosAction>?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var isExecutingBatch by remember { mutableStateOf(false) }
    var liveBoardExpanded by remember(bridge.host.id) { mutableStateOf(false) }
    val completedActionIds = remember(bridge.host.id) { mutableStateListOf<String>() }
    val deselectedActionIds = remember(bridge.host.id) { mutableStateMapOf<String, Boolean>() }
    var isListeningVoice by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    LaunchedEffect(messages.size, isRunning) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    val voiceFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                inputText = if (inputText.isBlank()) spoken else "$inputText $spoken"
            }
        }
    }

    fun startVoiceInput() {
        if (isListeningVoice) {
            runCatching { speechRecognizer?.stopListening() }
            isListeningVoice = false
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Chief of Staff")
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching {
                speechRecognizer?.destroy()
                val sr = SpeechRecognizer.createSpeechRecognizer(context)
                val prefix = inputText
                sr.setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { isListeningVoice = true }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { isListeningVoice = false }
                        override fun onError(error: Int) { isListeningVoice = false }
                        override fun onResults(results: Bundle?) {
                            isListeningVoice = false
                            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.trim()
                            if (!spoken.isNullOrEmpty()) {
                                inputText = if (prefix.isBlank()) spoken else "$prefix $spoken"
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val spoken = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.trim()
                            if (!spoken.isNullOrEmpty()) {
                                inputText = if (prefix.isBlank()) spoken else "$prefix $spoken"
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    },
                )
                speechRecognizer = sr
                sr.startListening(intent)
            }.onFailure {
                isListeningVoice = false
                runCatching { voiceFallbackLauncher.launch(intent) }
                    .onFailure { onMessage("Voice dictation unavailable") }
            }
        } else {
            runCatching { voiceFallbackLauncher.launch(intent) }
                .onFailure { onMessage("Voice dictation unavailable") }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startVoiceInput() else onMessage("Microphone permission required for voice")
    }

    fun executeBatchActions(actions: List<CosAction>, openStartedSession: Boolean = false) {
        if (actions.isEmpty() || isExecutingBatch) return
        isExecutingBatch = true
        scope.launch {
            val succeeded = mutableListOf<CosAction>()
            var lastError: String? = null
            var startedToOpen: String? = null
            var touchedSessions = false

            for (action in actions) {
                when (action.actionType) {
                    CosActionType.SEND -> {
                        ChiefOfStaffEngine.sendToTmuxSession(bridge, action.session, action.sendText)
                            .onSuccess {
                                if (action.id !in completedActionIds) completedActionIds.add(action.id)
                                succeeded.add(action)
                            }
                            .onFailure { lastError = "${action.session}: ${it.message ?: "send failed"}" }
                    }

                    CosActionType.START_TMUX -> {
                        TmuxRemoteController.createDetachedSession(
                            bridge = bridge,
                            rawName = action.session,
                            preset = TmuxLaunchPreset.SHELL,
                            customCommand = action.command.ifBlank { null },
                            yoloMode = true,
                            workingDir = action.cwd.ifBlank { null },
                        ).onSuccess { createdName ->
                            if (action.id !in completedActionIds) completedActionIds.add(action.id)
                            succeeded.add(action.copy(session = createdName))
                            touchedSessions = true
                            if (openStartedSession) startedToOpen = createdName
                        }.onFailure { lastError = "${action.session}: ${it.message ?: "start failed"}" }
                    }

                    CosActionType.KILL_TMUX -> {
                        TmuxRemoteController.killSession(bridge, action.session)
                            .onSuccess {
                                if (action.id !in completedActionIds) completedActionIds.add(action.id)
                                succeeded.add(action)
                                touchedSessions = true
                            }
                            .onFailure { lastError = "${action.session}: ${it.message ?: "kill failed"}" }
                    }

                    CosActionType.RENAME_TMUX -> {
                        val requested = action.newName.ifBlank { action.command }
                        TmuxRemoteController.renameSession(bridge, action.session, requested)
                            .onSuccess { createdName ->
                                if (action.id !in completedActionIds) completedActionIds.add(action.id)
                                succeeded.add(action.copy(newName = createdName))
                                touchedSessions = true
                                onSessionRenamed(action.session, createdName)
                            }
                            .onFailure { lastError = "${action.session}: ${it.message ?: "rename failed"}" }
                    }
                }
            }

            if (succeeded.isNotEmpty()) {
                onActionsExecuted(succeeded)
            }
            if (touchedSessions) {
                onSessionsChanged()
            }
            val msg = when {
                succeeded.isEmpty() && lastError != null -> "Action failed: $lastError"
                succeeded.size == 1 && lastError == null -> {
                    val only = succeeded.first()
                    when (only.actionType) {
                        CosActionType.SEND -> "Sent to '${only.session}'"
                        CosActionType.START_TMUX -> "Started '${only.session}'"
                        CosActionType.KILL_TMUX -> "Ended '${only.session}'"
                        CosActionType.RENAME_TMUX -> "Renamed '${only.session}' to '${only.newName}'"
                    }
                }
                lastError == null -> "Executed ${succeeded.size} actions (${succeeded.joinToString(", ") { it.session }})"
                else -> "Ran ${succeeded.size}/${actions.size} · failed: $lastError"
            }
            onMessage(msg)
            isExecutingBatch = false
            startedToOpen?.let { onJumpToSession(it) }
        }
    }

    renameTarget?.let { currentName ->
        TmuxRenameDialog(
            currentName = currentName,
            onDismiss = { renameTarget = null },
            onConfirm = { edited ->
                renameTarget = null
                scope.launch {
                    TmuxRemoteController.renameSession(bridge, currentName, edited)
                        .onSuccess { created ->
                            onSessionRenamed(currentName, created)
                            onSessionsChanged()
                            onMessage("Renamed '$currentName' to '$created'")
                        }
                        .onFailure { onMessage(it.message ?: "Rename failed") }
                }
            },
        )
    }

    pendingBatchPermission?.let { batch ->
        val hasKill = batch.any { it.actionType == CosActionType.KILL_TMUX }
        val singleStart = batch.size == 1 && batch.first().actionType == CosActionType.START_TMUX
        AlertDialog(
            onDismissRequest = { pendingBatchPermission = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (singleStart) Icons.Default.Add else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasKill) CosRed else if (singleStart) CosCyan else CosAmber,
                    )
                    Text(
                        if (batch.size == 1) {
                            when (batch.first().actionType) {
                                CosActionType.START_TMUX -> "Approve Suggested Start"
                                CosActionType.KILL_TMUX -> "Approve Suggested End"
                                CosActionType.RENAME_TMUX -> "Approve Suggested Rename"
                                CosActionType.SEND -> "Confirm Risky Suggestion"
                            }
                        } else {
                            "Approve Suggested Actions (${batch.size})"
                        },
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Run these suggested actions on ${bridge.host.nickname}:",
                        style = MaterialTheme.typography.bodySmall,
                        color = CosMuted,
                    )
                    batch.forEach { action ->
                        val accent = when {
                            action.actionType == CosActionType.KILL_TMUX -> CosRed
                            action.actionType == CosActionType.START_TMUX || action.actionType == CosActionType.RENAME_TMUX -> CosCyan
                            action.requiresPermissionDialog -> CosAmber
                            else -> CosCyan
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = "${action.actionType.wireName} → ${action.session}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                )
                                when (action.actionType) {
                                    CosActionType.SEND -> Text(
                                        text = action.sendText,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    CosActionType.START_TMUX -> {
                                        if (action.cwd.isNotBlank()) {
                                            Text("dir: ${action.cwd}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = CosMuted)
                                        }
                                        Text(
                                            text = action.command.ifBlank { "default shell" },
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                        )
                                    }
                                    CosActionType.KILL_TMUX -> Text(
                                        text = "Permanently terminates session '${action.session}'.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFCA5A5),
                                    )
                                    CosActionType.RENAME_TMUX -> Text(
                                        text = "Rename '${action.session}' to '${action.newName.ifBlank { action.command }}'.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (singleStart) {
                        OutlinedButton(
                            onClick = {
                                pendingBatchPermission = null
                                executeBatchActions(batch, openStartedSession = false)
                            },
                        ) { Text("Start Detached") }
                        Button(
                            onClick = {
                                pendingBatchPermission = null
                                executeBatchActions(batch, openStartedSession = true)
                            },
                        ) { Text("Start & Open") }
                    } else {
                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasKill) CosRed else CosAmber,
                            ),
                            onClick = {
                                pendingBatchPermission = null
                                executeBatchActions(batch)
                            },
                        ) {
                            Text(if (batch.size == 1) "Approve" else "Run All (${batch.size})")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchPermission = null }) { Text("Cancel") }
            },
        )
    }

    if (showAgentsDialog) {
        fun loadDocTab(fileName: String) {
            activeCosDocTab = fileName
            agentsLoading = true
            scope.launch {
                ChiefOfStaffEngine.loadCosFile(bridge, fileName)
                    .onSuccess { agentsContent = it }
                    .onFailure {
                        agentsContent = if (fileName == "MEMORY.md") {
                            ChiefOfStaffEngine.DEFAULT_MEMORY_MD
                        } else {
                            ChiefOfStaffEngine.DEFAULT_AGENTS_MD
                        }
                        onMessage(it.message ?: "Could not read $fileName")
                    }
                agentsLoading = false
            }
        }
        AlertDialog(
            onDismissRequest = { showAgentsDialog = false },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Chief of Staff Memory")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("AGENTS.md", "MEMORY.md").forEach { tab ->
                            val selected = activeCosDocTab == tab
                            Surface(
                                onClick = { if (!agentsSaving) loadDocTab(tab) },
                                shape = RoundedCornerShape(999.dp),
                                color = if (selected) CosCyan.copy(alpha = 0.22f) else Color(0xFF1E293B),
                                border = BorderStroke(1.dp, if (selected) CosCyan else CosCardBorder),
                            ) {
                                Text(
                                    text = tab,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) CosCyan else CosMuted,
                                )
                            }
                        }
                    }
                }
            },
            text = {
                if (agentsLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CosCyan)
                    }
                } else {
                    OutlinedTextField(
                        value = agentsContent,
                        onValueChange = { agentsContent = it },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        label = { Text("~/.config/paullm-cos/$activeCosDocTab") },
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !agentsLoading && !agentsSaving,
                    onClick = {
                        agentsSaving = true
                        scope.launch {
                            ChiefOfStaffEngine.saveCosFile(bridge, activeCosDocTab, agentsContent)
                                .onSuccess {
                                    agentsSaving = false
                                    showAgentsDialog = false
                                    onMessage("Saved ~/.config/paullm-cos/$activeCosDocTab")
                                }
                                .onFailure {
                                    agentsSaving = false
                                    onMessage(it.message ?: "Failed to save $activeCosDocTab")
                                }
                        }
                    },
                ) { Text(if (agentsSaving) "Saving…" else "Save to Host") }
            },
            dismissButton = {
                TextButton(onClick = { showAgentsDialog = false }) { Text("Close") }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CosBg),
    ) {
        // Top CoS Controls Strip: Backend Picker + AGENTS.md + Brief Now + Clear
        Surface(
            color = Color(0xFF0D111A),
            border = BorderStroke(0.5.dp, CosCardBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CosCyan, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Chief of Staff",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = "· ${knownSessions.size} tmux",
                            style = MaterialTheme.typography.labelSmall,
                            color = CosMuted,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = {
                                showAgentsDialog = true
                                activeCosDocTab = "AGENTS.md"
                                agentsLoading = true
                                scope.launch {
                                    ChiefOfStaffEngine.loadCosFile(bridge, "AGENTS.md")
                                        .onSuccess { agentsContent = it }
                                        .onFailure {
                                            agentsContent = ChiefOfStaffEngine.DEFAULT_AGENTS_MD
                                            onMessage(it.message ?: "Could not read remote AGENTS.md")
                                        }
                                    agentsLoading = false
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, CosCardBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = CosCyan, modifier = Modifier.size(13.dp))
                                Text("Memory", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        Surface(
                            onClick = {
                                if (!isRunning && !disconnected) {
                                    onRunFullScan()
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = CosCyan.copy(alpha = if (isRunning) 0.08f else 0.2f),
                            border = BorderStroke(1.dp, CosCyan.copy(alpha = 0.55f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (isRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.8.dp, color = CosCyan)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = CosCyan, modifier = Modifier.size(13.dp))
                                }
                                Text(
                                    text = if (isRunning) "Thinking…" else "Full Scan",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CosCyan,
                                )
                            }
                        }
                        if (messages.isNotEmpty()) {
                            IconButton(onClick = onClearChat, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear chat", tint = CosMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Backend selector pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CosBackend.entries.forEach { backend ->
                        val active = backend == selectedBackend
                        Surface(
                            onClick = { onSelectBackend(backend) },
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) CosCyan.copy(alpha = 0.2f) else Color(0xFF141720),
                            border = BorderStroke(1.dp, if (active) CosCyan else CosCardBorder),
                        ) {
                            Text(
                                text = backend.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) CosCyan else CosMuted,
                            )
                        }
                    }
                }
            }
        }

        // Live-Updating Collapsible Session Pulse Board (single source of truth; never duplicates in chat!)
        if (liveSessions.isNotEmpty()) {
            val decCount = liveSessions.count { it.urgency == SessionUrgency.NEEDS_DECISION }
            val blkCount = liveSessions.count { it.urgency == SessionUrgency.BLOCKED }
            val runCount = liveSessions.count { it.urgency == SessionUrgency.RUNNING }
            val waitCount = liveSessions.count { it.urgency == SessionUrgency.WAITING_INPUT }
            Surface(
                color = Color(0xFF0E131D),
                border = BorderStroke(0.5.dp, CosCardBorder),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { liveBoardExpanded = !liveBoardExpanded }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Live Status",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            if (decCount > 0) CosCountPill("$decCount Decision", CosAmber)
                            if (blkCount > 0) CosCountPill("$blkCount Blocked", CosRed)
                            if (waitCount > 0) CosCountPill("$waitCount Waiting", Color(0xFFFBBF24))
                            if (runCount > 0) CosCountPill("$runCount Running", CosCyan)
                            if (decCount == 0 && blkCount == 0 && waitCount == 0 && runCount == 0) {
                                CosCountPill("${liveSessions.size} Ready", CosGreen)
                            }
                        }
                        Icon(
                            if (liveBoardExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle live session board",
                            tint = CosMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    if (liveBoardExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            liveSessions.forEach { item ->
                                val accent = urgencyColor(item.urgency)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = CosCardBg,
                                    border = BorderStroke(1.dp, if (item.urgency.rank <= 1) accent.copy(alpha = 0.45f) else CosCardBorder),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        AiVendorLogo(vendor = item.vendor, size = 18.dp)
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = item.session,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = accent.copy(alpha = 0.16f),
                                                ) {
                                                    Text(
                                                        text = item.urgency.label,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = accent,
                                                    )
                                                }
                                            }
                                            Text(
                                                text = item.decisionNeeded?.takeIf { it.isNotBlank() } ?: item.summary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFCBD5E1),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Surface(
                                            onClick = { renameTarget = item.session },
                                            shape = CircleShape,
                                            color = Color(0xFF1E293B),
                                        ) {
                                            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "Rename ${item.session}",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                            }
                                        }
                                        Surface(
                                            onClick = { onJumpToSession(item.session) },
                                            shape = CircleShape,
                                            color = Color(0xFF2563EB),
                                        ) {
                                            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "Jump to ${item.session}",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp),
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
        }

        // Chat Feed
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty() && !isRunning) {
                item(key = "cos_empty_hero") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CosCardBg,
                        border = BorderStroke(1.dp, CosCardBorder),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Remote Orchestrator · ${bridge.host.nickname}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = "Stays lean and focused on your current request on ${bridge.host.nickname}. Only runs a full tmux + delllap vault analysis (via AGY Orchestrator) when you tap Full Scan or explicitly ask.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CosMuted,
                            )
                            val starters = listOf(
                                "What should I prioritize right now? (vault + active sessions)",
                                "Brief me on all tmux sessions & decisions",
                                "Summarize recent vault updates & open decisions on delllap",
                                "Which sessions are blocked or done and safe to end?",
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                starters.forEach { starter ->
                                    Surface(
                                        onClick = { if (!disconnected) onSendPrompt(starter) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF1B2130),
                                        border = BorderStroke(1.dp, CosCardBorder),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(starter, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2E8F0))
                                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = CosCyan, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                if (msg.isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                            color = Color(0xFF1E3A5F),
                            border = BorderStroke(1.dp, CosCyan.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text = msg.text,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }
                } else {
                    CosAssistantMessageBubble(
                        message = msg,
                        knownSessions = knownSessions,
                        completedActionIds = completedActionIds,
                        deselectedActionIds = deselectedActionIds,
                        isExecutingBatch = isExecutingBatch,
                        onToggleActionSelected = { actionId ->
                            if (deselectedActionIds[actionId] == true) {
                                deselectedActionIds.remove(actionId)
                            } else {
                                deselectedActionIds[actionId] = true
                            }
                        },
                        onRunBatch = { selectedActions ->
                            if (selectedActions.any { it.requiresPermissionDialog }) {
                                pendingBatchPermission = selectedActions
                            } else {
                                executeBatchActions(selectedActions)
                            }
                        },
                        onJumpToSession = onJumpToSession,
                        onOpenUrl = onOpenUrl,
                    )
                }
            }

            if (isRunning) {
                item(key = "cos_loading") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CosCardBg,
                        border = BorderStroke(1.dp, CosCyan.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CosCyan)
                            Text(
                                text = "Analyzing tmux sessions on ${bridge.host.nickname} (${selectedBackend.label})…",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1),
                            )
                        }
                    }
                }
            }
        }

        // Bottom Chat Composer with Voice Mic
        Surface(
            color = Color(0xFF0D111A),
            border = BorderStroke(0.5.dp, CosCardBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (isListeningVoice) "Listening…" else "Ask CoS anything (vault, priorities, tmux…)",
                            style = MaterialTheme.typography.bodySmall,
                            color = CosMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    maxLines = 3,
                    singleLine = false,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CosCardBg,
                        unfocusedContainerColor = CosCardBg,
                        focusedBorderColor = CosCyan,
                        unfocusedBorderColor = CosCardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val trimmed = inputText.trim()
                            if (trimmed.isNotEmpty() && !isRunning && !disconnected) {
                                inputText = ""
                                onSendPrompt(trimmed)
                            }
                        },
                    ),
                )
                Surface(
                    onClick = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) startVoiceInput() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    shape = CircleShape,
                    color = if (isListeningVoice) CosRed.copy(alpha = 0.25f) else CosCardBg,
                    border = BorderStroke(1.dp, if (isListeningVoice) CosRed else CosCardBorder),
                ) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isListeningVoice) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = if (isListeningVoice) CosRed else CosCyan,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Surface(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty() && !isRunning && !disconnected) {
                            inputText = ""
                            onSendPrompt(trimmed)
                        }
                    },
                    shape = CircleShape,
                    color = if (inputText.isNotBlank() && !isRunning && !disconnected) CosCyan else Color(0xFF1E293B),
                ) {
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send to Chief of Staff",
                            tint = if (inputText.isNotBlank() && !isRunning && !disconnected) Color(0xFF04101B) else CosMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CosAssistantMessageBubble(
    message: CosChatMessage,
    knownSessions: List<TmuxSessionInfo>,
    completedActionIds: List<String>,
    deselectedActionIds: Map<String, Boolean>,
    isExecutingBatch: Boolean,
    onToggleActionSelected: (String) -> Unit,
    onRunBatch: (List<CosAction>) -> Unit,
    onJumpToSession: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val timeLabel = remember(message.timestampMs) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestampMs))
    }

    if (message.error != null) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CosRed.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, CosRed.copy(alpha = 0.45f)),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Chief of Staff Error · $timeLabel", style = MaterialTheme.typography.labelSmall, color = CosRed, fontWeight = FontWeight.Bold)
                Text(message.error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFCA5A5))
            }
        }
        return
    }

    val briefing = message.briefing
    val knownByName = remember(knownSessions) { knownSessions.associateBy { it.name.lowercase() } }

    Surface(
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        color = CosCardBg,
        border = BorderStroke(1.dp, CosCardBorder),
    ) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (briefing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${briefing.backendUsed} · ${briefing.modelUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = CosCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            if (briefing.didFullTmuxScan) {
                                append("full scan · ")
                            }
                            if (briefing.vaultNotesChecked > 0) {
                                append("${briefing.vaultNotesChecked} vault · ")
                            }
                            if (briefing.promptChars > 0) {
                                val approxTok = (briefing.promptChars + 3) / 4
                                if (approxTok >= 1000) {
                                    append(String.format(Locale.US, "~%.1fk tok · ", approxTok / 1000.0))
                                } else {
                                    append("~${approxTok}t · ")
                                }
                            }
                            append(timeLabel)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = CosMuted,
                    )
                }

                val bodyText = briefing.reply.ifBlank { briefing.headline }
                val renderedBody = remember(bodyText) { renderCosMarkdown(bodyText) }
                Text(
                    text = renderedBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE2E8F0),
                )

                // Direct Chat-Requested Executions (already ran immediately — zero approval friction!)
                if (briefing.executedReceipts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        briefing.executedReceipts.forEach { rec ->
                            val c = if (rec.ok) CosGreen else CosRed
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = c.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, c.copy(alpha = 0.4f)),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        if (rec.ok) Icons.Default.Check else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = c,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        val verb = when (rec.actionType) {
                                            CosActionType.SEND -> "Sent to ${rec.session}"
                                            CosActionType.START_TMUX -> "Started ${rec.session}"
                                            CosActionType.KILL_TMUX -> "Ended ${rec.session}"
                                            CosActionType.RENAME_TMUX -> "Renamed to ${rec.session}"
                                        }
                                        Text(
                                            text = if (rec.ok) verb else "$verb (failed)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = c,
                                        )
                                        val detail = rec.error ?: rec.summary
                                        if (detail.isNotBlank()) {
                                            Text(
                                                text = detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFCBD5E1),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (rec.ok && rec.actionType != CosActionType.KILL_TMUX) {
                                        Surface(
                                            onClick = { onJumpToSession(rec.session) },
                                            shape = CircleShape,
                                            color = Color(0xFF2563EB),
                                        ) {
                                            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "Open ${rec.session}",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Assistant Memory Learning Receipts (AGENTS.md / MEMORY.md / notes/*.md)
                if (briefing.learnedNotes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        briefing.learnedNotes.forEach { note ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = CosCyan.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, CosCyan.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    text = "Noted in ${note.file}: ${note.summary}",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CosCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Vault Note References (clickable Perlite links on delllap)
                if (briefing.vaultRefs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        briefing.vaultRefs.forEach { ref ->
                            Surface(
                                onClick = { onOpenUrl(ref.perliteUrl) },
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xFFA855F7).copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        tint = Color(0xFFC084FC),
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        text = ref.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFE9D5FF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // ONE Unified Suggested Actions Card (ONLY for proactive suggestions; collapses cleanly once run!)
                val plan = briefing.actionPlan
                if (plan.isNotEmpty()) {
                    val remaining = plan.filter { it.id !in completedActionIds }
                    if (remaining.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CosGreen.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, CosGreen.copy(alpha = 0.4f)),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = CosGreen, modifier = Modifier.size(14.dp))
                                Text(
                                    text = "Ran ${plan.size} suggested action${if (plan.size == 1) "" else "s"} (${plan.joinToString(", ") { it.session }})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CosGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        val selectedToRun = remaining.filter { deselectedActionIds[it.id] != true }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0E1420),
                            border = BorderStroke(1.dp, CosCyan.copy(alpha = 0.35f)),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(
                                    text = if (remaining.size == 1) "SUGGESTED ACTION" else "SUGGESTED ACTIONS (${remaining.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CosCyan,
                                )
                                remaining.forEach { action ->
                                    val checked = deselectedActionIds[action.id] != true
                                    val accent = when (action.actionType) {
                                        CosActionType.KILL_TMUX -> CosRed
                                        CosActionType.START_TMUX, CosActionType.RENAME_TMUX -> CosCyan
                                        CosActionType.SEND -> if (action.requiresPermissionDialog) CosAmber else CosCyan
                                    }
                                    val vendor = knownByName[action.session.lowercase()]?.detectedVendor
                                        ?: AiVendor.detectFromName(action.session, isTmux = true)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onToggleActionSelected(action.id) }
                                            .background(if (checked) Color(0xFF151C2C) else Color(0xFF0B0F18))
                                            .padding(horizontal = 8.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (checked) accent.copy(alpha = 0.25f) else Color.Transparent,
                                            border = BorderStroke(1.2.dp, if (checked) accent else CosMuted.copy(alpha = 0.5f)),
                                            modifier = Modifier.padding(top = 2.dp).size(18.dp),
                                        ) {
                                            if (checked) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
                                                }
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                AiVendorLogo(vendor = vendor, size = 15.dp)
                                                Text(
                                                    text = "${action.session} · ${action.label}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (checked) Color.White else CosMuted,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            val preview = when (action.actionType) {
                                                CosActionType.SEND -> action.sendText
                                                CosActionType.START_TMUX -> action.command.ifBlank { "Start shell session" }
                                                CosActionType.KILL_TMUX -> "Terminate session '${action.session}'"
                                                CosActionType.RENAME_TMUX -> "Rename '${action.session}' to '${action.newName.ifBlank { action.command }}'"
                                            }
                                            Text(
                                                text = preview,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = CosMuted,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                Button(
                                    onClick = { onRunBatch(selectedToRun) },
                                    enabled = selectedToRun.isNotEmpty() && !isExecutingBatch,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CosCyan, contentColor = Color(0xFF04101B)),
                                ) {
                                    if (isExecutingBatch) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF04101B))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Running…", fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (selectedToRun.size == 1) {
                                                val one = selectedToRun.first()
                                                when (one.actionType) {
                                                    CosActionType.SEND -> "Send to ${one.session}"
                                                    CosActionType.START_TMUX -> "Start ${one.session}"
                                                    CosActionType.KILL_TMUX -> "End ${one.session}"
                                                    CosActionType.RENAME_TMUX -> "Rename ${one.session}"
                                                }
                                            } else {
                                                "Run Selected (${selectedToRun.size})"
                                            },
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }
}
