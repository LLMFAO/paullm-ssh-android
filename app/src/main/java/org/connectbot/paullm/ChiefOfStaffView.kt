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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    OLLAMA("ollama", "Ollama"),
    PI("pi", "pi"),
    CLAUDE("claude", "Claude"),
    CODEX("codex", "Codex"),
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
    ;

    companion object {
        fun parse(raw: String?): CosActionType = when (raw?.trim()?.uppercase()) {
            "START_TMUX", "START" -> START_TMUX
            "KILL_TMUX", "KILL", "END_TMUX", "END" -> KILL_TMUX
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

data class CosBriefingPayload(
    val headline: String,
    val reply: String,
    val sessions: List<CosSessionDecision>,
    val orchestratorActions: List<CosAction>,
    val vaultRefs: List<CosVaultRef> = emptyList(),
    val backendUsed: String,
    val modelUsed: String,
    val conversationId: String?,
    val scannedCount: Int,
    val vaultNotesChecked: Int = 0,
    val generatedAtMs: Long = System.currentTimeMillis(),
) {
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

    val DEFAULT_AGENTS_MD = """
        # Chief of Staff Orchestrator — AGENTS.md

        You are Paul's executive Chief of Staff & strategic orchestrator. You help Paul make high-leverage decisions across ALL of his projects, notes, priorities, and active engineering work—NOT just what happens to be open in `tmux`.

        ## Big-Picture Mindset (Avoid tmux Myopia)
        - **Lead with Paul's actual question and strategic context.** Active `tmux` sessions are ONE live execution signal—not the whole picture.
        - Ground your recommendations in Paul's canonical Obsidian vault on `delllap` (`/home/paul/ObsidianVault_Final`: project roadmaps, goals, open decisions, recent agent updates, architecture docs, runbooks) as well as live `tmux` / host state.
        - **Only include `sessions` cards that are relevant**:
          - On an explicit full briefing ("Brief me on all tmux sessions" / "Brief Now"), summarize all live `tmux` sessions sorted by urgency (`NEEDS_DECISION`, `BLOCKED`, `WAITING_INPUT`, `RUNNING`, `DONE`, `IDLE`) AND highlight relevant vault context/priorities.
          - On a broader strategic, vault, priority, or single-project question, answer the big-picture question thoroughly in `reply` using vault + subagent research, and include ONLY relevant `tmux` sessions in `sessions` (or `[]` if `tmux` isn't relevant to the question).

        ## Canonical Vault Access (`delllap`) & Subagent Delegation
        - Paul's canonical personal Obsidian vault lives on SSH host `delllap` at `/home/paul/ObsidianVault_Final`.
        - Never use stale local mirrors, and never touch `ubuntu-server`'s `/home/paul/commure-athelas-vault`.
        - Pre-loaded `VAULT CONTEXT` (recent notes + matched excerpts from `delllap`) is included with every turn for fast orientation.
        - **Spin up subagents for deeper research**: Whenever Paul asks about project plans, priorities, specs, past decisions, runbooks, cross-project tradeoffs, or topics not fully covered by the pre-loaded excerpts, spin up read-only subagents (`invoke_subagent`) to search/read `delllap:/home/paul/ObsidianVault_Final` (via `ssh -o BatchMode=yes delllap ...`) in parallel, then synthesize a crisp executive answer.
        - **Never edit or delete vault notes** unless Paul explicitly requests a vault write.
        - Whenever you rely on vault notes, cite them in `vault_refs` using the exact Perlite URL format:
          `https://delllap.tailfaa66b.ts.net/wiki/?link=/<path-without-.md>`

        ## Orchestrator Permissions (CRITICAL)
        - **NEVER** start or kill `tmux` sessions directly inside tool calls.
        - When Paul asks to start a new session or end/clean up an existing session—or when a session is clearly done/stuck and should be ended—propose a typed action in `suggested_actions` or `orchestrator_actions`:
          - `START_TMUX`: specify `session`, `command` (use verified YOLO flags when appropriate: `codex --dangerously-bypass-approvals-and-sandbox`, `agy --dangerously-skip-permissions`, `opencode --auto`, `claude --dangerously-skip-permissions`, `grok --always-approve`), optional `cwd`, and `"risky": true`.
          - `KILL_TMUX`: specify `session` and `"risky": true`.
          - `SEND`: specify `session`, `send` (exact text to type + Enter), and set `"risky": true` if it deletes files, force-pushes, deploys, publishes, uses `sudo`, resets git, or makes irreversible changes.
        - Paul will approve permission-gated actions with one tap in the Android Chat UI.

        ## Style & Truthfulness
        - Be concise, sharp, strategic, and executive. Zero fluff.
        - Cite real vault notes, open decisions, and live session evidence—never invent facts or hide failures.
        - Write `reply` in clean human-readable prose/bullets (never dump raw JSON inside `reply`).
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
                                "action_type": {"type": "string", "enum": ["SEND", "START_TMUX", "KILL_TMUX"]},
                                "label": {"type": "string"},
                                "session": {"type": "string"},
                                "send": {"type": "string"},
                                "command": {"type": "string"},
                                "cwd": {"type": "string"},
                                "risky": {"type": "boolean"}
                            },
                            "required": ["action_type", "label", "risky"]
                        }
                    }
                },
                "required": ["session", "status", "summary", "suggested_actions"]
            }
        },
        "orchestrator_actions": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "action_type": {"type": "string", "enum": ["SEND", "START_TMUX", "KILL_TMUX"]},
                    "label": {"type": "string"},
                    "session": {"type": "string"},
                    "send": {"type": "string"},
                    "command": {"type": "string"},
                    "cwd": {"type": "string"},
                    "risky": {"type": "boolean"}
                },
                "required": ["action_type", "label", "session", "risky"]
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

def capture_tmux():
    panes_out = run_cmd([
        "tmux", "list-panes", "-a", "-F",
        "#{session_name}\t#{pane_id}\t#{pane_current_command}\t#{pane_pid}\t#{pane_current_path}\t#{pane_title}\t#{session_attached}\t#{session_windows}"
    ])
    ps_out = run_cmd(["ps", "-eo", "ppid=,args="])
    children = {}
    for line in ps_out.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            children.setdefault(parts[0], []).append(parts[1][:180])

    snap = []
    seen = set()
    for line in panes_out.splitlines():
        p = line.split("\t")
        if len(p) < 5 or not p[0] or p[0] in seen:
            continue
        seen.add(p[0])
        name, pane_id, cmd, pid, cwd = p[0], p[1], p[2], p[3], p[4]
        title = p[5] if len(p) > 5 else ""
        attached = p[6] if len(p) > 6 else "0"
        windows = p[7] if len(p) > 7 else "1"
        raw = run_cmd(["tmux", "capture-pane", "-t", pane_id, "-p", "-J", "-S", "-80"])
        clean_lines = [ANSI_RE.sub("", l).rstrip() for l in raw.splitlines()]
        clean_lines = [l for l in clean_lines if l.strip()]
        tail = "\n".join(clean_lines[-50:])[-2400:]
        branch = ""
        if cwd and os.path.isdir(cwd):
            branch = run_cmd(["git", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD"], timeout=2).strip()
        snap.append({
            "session": name,
            "pane_id": pane_id,
            "cmd": cmd,
            "child_cmds": children.get(pid, [])[:3],
            "cwd": cwd,
            "git_branch": branch,
            "title": title,
            "attached": attached,
            "windows": windows,
            "recent_output": tail,
        })
    return snap

def capture_vault_context(snap, user_msg):
    # Fast read-only context pull from canonical vault on delllap:/home/paul/ObsidianVault_Final
    vault_root = "/home/paul/ObsidianVault_Final"
    perlite_base = "https://delllap.tailfaa66b.ts.net/wiki/?link=/"
    terms = set()
    stop = {"the", "and", "for", "with", "that", "this", "from", "what", "which", "are", "all", "tmux", "session", "sessions", "brief", "briefing", "status", "right", "now", "about", "into", "your", "have", "vault", "delllap", "check", "give", "chief", "staff"}
    raw_sources = [user_msg] + [s.get("session", "") + " " + s.get("title", "") + " " + os.path.basename(s.get("cwd", "")) for s in snap]
    for src in raw_sources:
        for tok in re.findall(r"[A-Za-z0-9]{3,}", src.lower()):
            if tok not in stop:
                terms.add(tok)
    terms_b64 = base64.b64encode(json.dumps(sorted(terms)[:24]).encode()).decode()

    remote_py = f'''
import base64, json, os, pathlib, re
root = pathlib.Path({vault_root!r})
if not root.is_dir():
    print(json.dumps({{"available": False, "recent": [], "excerpts": []}}))
    raise SystemExit(0)
terms = json.loads(base64.b64decode({terms_b64!r}).decode())
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

recent = [{{"path": rel, "perlite_url": {perlite_base!r} + rel[:-3]}} for _, rel, _ in dedup[:14]]

scored = []
for idx, (mt, rel, fp) in enumerate(dedup[:160]):
    low = rel.lower()
    hits = sum(2 for t in terms if t in low)
    if hits > 0 or idx < 4:
        scored.append((hits, -idx, rel, fp))
scored.sort(reverse=True)

excerpts = []
for _, _, rel, fp in scored[:6]:
    try:
        txt = fp.read_text(encoding="utf-8", errors="ignore")
        if txt.startswith("---"):
            end_fm = txt.find("\\n---", 3)
            if end_fm != -1:
                txt = txt[end_fm + 4:]
        lines = [l.rstrip() for l in txt.strip().splitlines() if l.strip()]
        title = next((l.lstrip("# ").strip() for l in lines[:4] if l.startswith("#")), pathlib.Path(rel).stem)
        excerpt = "\\n".join(lines[:22])[:850]
        excerpts.append({{
            "title": title[:90],
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

def try_cli(bin_name, args_fn, prompt, cos_dir, timeout_s=65):
    path = shutil.which(bin_name)
    if not path:
        raise RuntimeError(f"{bin_name} not found")
    proc = subprocess.run(args_fn(path, prompt), cwd=str(cos_dir), capture_output=True, text=True, timeout=timeout_s)
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or proc.stdout or f"{bin_name} exit {proc.returncode}").strip()[:280])
    return extract_json(proc.stdout), bin_name, bin_name, None

def main():
    payload = json.loads(base64.b64decode(sys.argv[1]).decode("utf-8"))
    user_msg = payload.get("prompt", "Give me a Chief of Staff briefing across all tmux sessions.")
    backend = payload.get("backend", "auto")
    conv_id = payload.get("conversation_id") or ""
    history = payload.get("history") or []
    default_agents = payload.get("default_agents_md", "")

    cos_dir = pathlib.Path.home() / ".config/paullm-cos"
    cos_dir.mkdir(parents=True, exist_ok=True)
    agents_path = cos_dir / "AGENTS.md"
    if not agents_path.exists() and default_agents:
        agents_path.write_text(default_agents, encoding="utf-8")
    agents_md = agents_path.read_text(encoding="utf-8")[:6000] if agents_path.exists() else default_agents
    schema_path = cos_dir / "briefing-schema.json"
    schema_path.write_text(json.dumps(SCHEMA, indent=2), encoding="utf-8")

    snap = capture_tmux()
    vault_ctx = capture_vault_context(snap, user_msg)
    schema_hint = json.dumps(SCHEMA)
    system_prompt = (
        agents_md.strip() + "\n\n"
        "OUTPUT CONTRACT: Return ONLY a valid JSON object matching this schema:\n"
        + schema_hint + "\n"
        "For full tmux briefings, include all captured tmux sessions in `sessions` sorted by urgency "
        "(NEEDS_DECISION, BLOCKED, WAITING_INPUT, RUNNING, DONE, IDLE). "
        "For broader vault/strategy/priority questions, focus `reply` on the big picture and include ONLY "
        "the relevant tmux sessions in `sessions` (or `[]` if tmux is not relevant). "
        "Whenever you use facts from the canonical vault on delllap, include those notes in `vault_refs` "
        "with exact Perlite URLs (`https://delllap.tailfaa66b.ts.net/wiki/?link=/<path-without-.md>`). "
        "Keep `reply` as crisp human-readable prose/bullets, never raw JSON."
    )

    hist_block = ""
    if history:
        lines = [f"- {h.get('role','user').upper()}: {str(h.get('text',''))[:400]}" for h in history[-6:]]
        hist_block = "RECENT CHAT HISTORY:\n" + "\n".join(lines) + "\n\n"

    user_prompt = (
        hist_block
        + "USER REQUEST:\n" + user_msg + "\n\n"
        + "LIVE TMUX SNAPSHOT:\n" + json.dumps(snap) + "\n\n"
        + "CANONICAL VAULT CONTEXT (delllap:/home/paul/ObsidianVault_Final — you may also spin up read-only subagents to query `ssh -o BatchMode=yes delllap ...` if deeper vault info is needed):\n"
        + json.dumps(vault_ctx)
    )
    combined_prompt = system_prompt + "\n\n" + user_prompt

    errors = []
    result = None

    order = {
        "auto": ["agy_flash", "ollama", "pi", "claude", "codex"],
        "agy_flash": ["agy_flash"],
        "agy_orch": ["agy_orch_neon", "agy_orch_argon", "agy_neon_low"],
        "ollama": ["ollama"],
        "pi": ["pi"],
        "claude": ["claude"],
        "codex": ["codex"],
    }.get(backend, ["agy_flash", "ollama", "pi", "claude", "codex"])

    for step in order:
        try:
            if step == "agy_flash":
                result = try_agy("gemini-3.8-flash-low", combined_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=80)
            elif step == "agy_orch_neon":
                result = try_agy("gemini-pro-orchestrator-neon", combined_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=145)
            elif step == "agy_orch_argon":
                result = try_agy("gemini-pro-orchestrator-g1-pro", combined_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=145)
            elif step == "agy_neon_low":
                result = try_agy("gemini-4-neon-low", combined_prompt, schema_path, cos_dir, conv_id, allow_tools=True, timeout_s=85)
            elif step == "ollama":
                result = try_ollama(system_prompt, user_prompt)
            elif step == "pi":
                result = try_cli("pi", lambda p, pr: [p, "-p", "--no-session", pr], combined_prompt, cos_dir)
            elif step == "claude":
                result = try_cli("claude", lambda p, pr: [p, "-p", pr], combined_prompt, cos_dir)
            elif step == "codex":
                result = try_cli("codex", lambda p, pr: [p, "exec", "--skip-git-repo-check", pr], combined_prompt, cos_dir)
            if result is not None:
                break
        except Exception as e:
            errors.append(f"{step}: {e}")

    if result is None:
        print(json.dumps({"ok": False, "error": " | ".join(errors) or "No AI backend succeeded", "scanned_count": len(snap)}))
        return

    parsed, used_backend, used_model, next_conv_id = result
    print(json.dumps({
        "ok": True,
        "backend": used_backend,
        "model": used_model,
        "conversation_id": next_conv_id,
        "scanned_count": len(snap),
        "vault_notes_checked": len(vault_ctx.get("excerpts") or []),
        "snapshot_meta": {s["session"]: {"cmd": s["cmd"], "child_cmds": s["child_cmds"], "title": s["title"]} for s in snap},
        "data": parsed,
    }))

if __name__ == "__main__":
    main()
""".trimIndent()

    suspend fun loadAgentsMd(bridge: TerminalBridge): Result<String> {
        val seedB64 = Base64.encodeToString(DEFAULT_AGENTS_MD.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = """
            mkdir -p "${'$'}HOME/.config/paullm-cos"
            if [ ! -f "${'$'}HOME/.config/paullm-cos/AGENTS.md" ]; then
              printf '%s' '$seedB64' | base64 -d > "${'$'}HOME/.config/paullm-cos/AGENTS.md"
            fi
            cat "${'$'}HOME/.config/paullm-cos/AGENTS.md"
        """.trimIndent()
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = 10_000L)
        return if (res.error != null) {
            Result.failure(IllegalStateException(res.error))
        } else {
            Result.success(res.stdout)
        }
    }

    suspend fun saveAgentsMd(bridge: TerminalBridge, content: String): Result<Unit> {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = """
            mkdir -p "${'$'}HOME/.config/paullm-cos"
            printf '%s' '$b64' | base64 -d > "${'$'}HOME/.config/paullm-cos/AGENTS.md.tmp"
            mv "${'$'}HOME/.config/paullm-cos/AGENTS.md.tmp" "${'$'}HOME/.config/paullm-cos/AGENTS.md"
        """.trimIndent()
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = 10_000L)
        return if (!res.isSuccess) {
            Result.failure(IllegalStateException(res.error ?: res.stderr.ifBlank { "Failed to save AGENTS.md" }))
        } else {
            Result.success(Unit)
        }
    }

    suspend fun sendToTmuxSession(
        bridge: TerminalBridge,
        sessionName: String,
        text: String,
    ): Result<Unit> {
        val target = "=${sessionName}:"
        val textB64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = """
            MSG=${'$'}(printf '%s' '$textB64' | base64 -d)
            tmux send-keys -t ${PaullmShell.quote(target)} -l -- "${'$'}MSG" && tmux send-keys -t ${PaullmShell.quote(target)} Enter
        """.trimIndent()
        val res = bridge.execCommand(PaullmShell.wrapLoginShell(script), timeoutMs = 10_000L)
        return if (!res.isSuccess) {
            Result.failure(
                IllegalStateException(
                    res.error ?: res.stderr.ifBlank { "tmux send-keys failed for '$sessionName'" },
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
        }
        val reqB64 = Base64.encodeToString(reqObj.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val runnerB64 = Base64.encodeToString(PYTHON_RUNNER.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val script = """
            mkdir -p "${'$'}HOME/.config/paullm-cos"
            printf '%s' '$runnerB64' | base64 -d > "${'$'}HOME/.config/paullm-cos/cos_runner.py"
            python3 "${'$'}HOME/.config/paullm-cos/cos_runner.py" '$reqB64'
        """.trimIndent()

        val timeoutMs = if (backend == CosBackend.AGY_ORCH) 165_000L else 105_000L
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
                    val label = obj.optString("label").trim()
                    if (label.isEmpty()) continue
                    val type = CosActionType.parse(obj.optString("action_type", "SEND"))
                    val targetSession = obj.optString("session").trim().ifEmpty { fallbackSession }
                    val send = obj.optString("send").trim()
                    val command = obj.optString("command").trim()
                    val cwd = obj.optString("cwd").trim()
                    val risky = obj.optBoolean("risky", type != CosActionType.SEND)
                    if (type == CosActionType.SEND && (send.isEmpty() || targetSession.isEmpty())) continue
                    if ((type == CosActionType.START_TMUX || type == CosActionType.KILL_TMUX) && targetSession.isEmpty()) continue
                    out.add(
                        CosAction(
                            actionType = type,
                            label = label,
                            session = targetSession,
                            sendText = send,
                            command = command,
                            cwd = cwd,
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

            CosBriefingPayload(
                headline = data.optString("headline").trim().ifEmpty { "Briefing complete" },
                reply = data.optString("reply").trim(),
                sessions = parsedSessions,
                orchestratorActions = orchActions,
                vaultRefs = parsedVaultRefs,
                backendUsed = env.optString("backend", backend.label),
                modelUsed = env.optString("model", backend.label),
                conversationId = env.optString("conversation_id").takeIf { it.isNotBlank() && it != "null" },
                scannedCount = env.optInt("scanned_count", parsedSessions.size),
                vaultNotesChecked = env.optInt("vault_notes_checked", parsedVaultRefs.size),
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
    messages: List<CosChatMessage>,
    selectedBackend: CosBackend,
    isRunning: Boolean,
    onSelectBackend: (CosBackend) -> Unit,
    onSendPrompt: (String) -> Unit,
    onClearChat: () -> Unit,
    onJumpToSession: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSessionsChanged: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember(bridge.host.id) { mutableStateOf("") }
    var showAgentsDialog by remember { mutableStateOf(false) }
    var agentsContent by remember { mutableStateOf("") }
    var agentsLoading by remember { mutableStateOf(false) }
    var agentsSaving by remember { mutableStateOf(false) }
    var pendingPermissionAction by remember { mutableStateOf<CosAction?>(null) }
    val completedActionIds = remember(bridge.host.id) { mutableStateListOf<String>() }
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

    fun executeApprovedAction(action: CosAction, openAfterStart: Boolean = false) {
        scope.launch {
            when (action.actionType) {
                CosActionType.SEND -> {
                    ChiefOfStaffEngine.sendToTmuxSession(bridge, action.session, action.sendText)
                        .onSuccess {
                            if (action.id !in completedActionIds) completedActionIds.add(action.id)
                            onMessage("Sent to '${action.session}': ${action.sendText.take(40)}")
                        }
                        .onFailure { onMessage(it.message ?: "Send failed") }
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
                        onSessionsChanged()
                        onMessage("Started tmux session '$createdName'")
                        if (openAfterStart) {
                            onJumpToSession(createdName)
                        }
                    }.onFailure { onMessage(it.message ?: "Failed to start '${action.session}'") }
                }

                CosActionType.KILL_TMUX -> {
                    TmuxRemoteController.killSession(bridge, action.session)
                        .onSuccess {
                            if (action.id !in completedActionIds) completedActionIds.add(action.id)
                            onSessionsChanged()
                            onMessage("Ended tmux session '${action.session}'")
                        }
                        .onFailure { onMessage(it.message ?: "Failed to kill '${action.session}'") }
                }
            }
        }
    }

    pendingPermissionAction?.let { action ->
        val isKill = action.actionType == CosActionType.KILL_TMUX
        val isStart = action.actionType == CosActionType.START_TMUX
        AlertDialog(
            onDismissRequest = { pendingPermissionAction = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (isStart) Icons.Default.Add else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isKill) CosRed else if (isStart) CosCyan else CosAmber,
                    )
                    Text(
                        when (action.actionType) {
                            CosActionType.START_TMUX -> "Permission: Start tmux Session"
                            CosActionType.KILL_TMUX -> "Permission: End tmux Session"
                            CosActionType.SEND -> "Confirm Risky Send"
                        },
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (action.actionType) {
                        CosActionType.START_TMUX -> {
                            Text("Allow Chief of Staff to start a new tmux session on ${bridge.host.nickname}?")
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0F172A),
                                border = BorderStroke(1.dp, CosCardBorder),
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Session: ${action.session}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = CosCyan)
                                    if (action.cwd.isNotBlank()) {
                                        Text("Dir: ${action.cwd}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = CosMuted)
                                    }
                                    if (action.command.isNotBlank()) {
                                        Text("Cmd: ${action.command}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Color.White)
                                    }
                                }
                            }
                        }

                        CosActionType.KILL_TMUX -> {
                            Text(
                                "Allow Chief of Staff to permanently terminate tmux session '${action.session}' on ${bridge.host.nickname}? Running processes inside it will stop.",
                            )
                        }

                        CosActionType.SEND -> {
                            Text("Send this potentially risky command to tmux session '${action.session}'?")
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0F172A),
                                border = BorderStroke(1.dp, CosAmber.copy(alpha = 0.5f)),
                            ) {
                                Text(
                                    text = action.sendText,
                                    modifier = Modifier.padding(10.dp),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isStart) {
                        OutlinedButton(
                            onClick = {
                                pendingPermissionAction = null
                                executeApprovedAction(action, openAfterStart = false)
                            },
                        ) { Text("Start Detached") }
                        Button(
                            onClick = {
                                pendingPermissionAction = null
                                executeApprovedAction(action, openAfterStart = true)
                            },
                        ) { Text("Start & Open") }
                    } else {
                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isKill) CosRed else CosAmber,
                            ),
                            onClick = {
                                pendingPermissionAction = null
                                executeApprovedAction(action)
                            },
                        ) {
                            Text(if (isKill) "Approve & End" else "Approve & Send")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermissionAction = null }) { Text("Cancel") }
            },
        )
    }

    if (showAgentsDialog) {
        AlertDialog(
            onDismissRequest = { showAgentsDialog = false },
            title = {
                Column {
                    Text("Chief of Staff AGENTS.md")
                    Text(
                        text = ChiefOfStaffEngine.REMOTE_AGENTS_PATH,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = CosCyan,
                    )
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
                        label = { Text("Remote Orchestrator Instructions") },
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !agentsLoading && !agentsSaving,
                    onClick = {
                        agentsSaving = true
                        scope.launch {
                            ChiefOfStaffEngine.saveAgentsMd(bridge, agentsContent)
                                .onSuccess {
                                    agentsSaving = false
                                    showAgentsDialog = false
                                    onMessage("Saved ${ChiefOfStaffEngine.REMOTE_AGENTS_PATH}")
                                }
                                .onFailure {
                                    agentsSaving = false
                                    onMessage(it.message ?: "Failed to save AGENTS.md")
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
                                agentsLoading = true
                                scope.launch {
                                    ChiefOfStaffEngine.loadAgentsMd(bridge)
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
                                Text("AGENTS.md", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        Surface(
                            onClick = {
                                if (!isRunning && !disconnected) {
                                    onSendPrompt("Give me an executive Chief of Staff briefing combining my canonical vault on delllap and active tmux sessions. Highlight top priorities, open decisions, and blockers.")
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
                                    text = if (isRunning) "Scanning…" else "Brief Now",
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
                                text = "Runs on-demand directly on ${bridge.host.nickname}, inspects all live tmux panes + your Obsidian vault on delllap (with subagent research), uses ~/.config/paullm-cos/AGENTS.md, and can send replies or start/end tmux sessions with your permission.",
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
                        completedActionIds = completedActionIds,
                        onActionClick = { action ->
                            if (action.requiresPermissionDialog) {
                                pendingPermissionAction = action
                            } else {
                                executeApprovedAction(action)
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
    completedActionIds: List<String>,
    onActionClick: (CosAction) -> Unit,
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = CosCardBg,
            border = BorderStroke(1.dp, CosCardBorder),
        ) {
            Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                append("${briefing.scannedCount} tmux")
                                if (briefing.vaultNotesChecked > 0) {
                                    append(" · ${briefing.vaultNotesChecked} vault")
                                }
                                append(" · $timeLabel")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = CosMuted,
                        )
                    }
                    Text(
                        text = briefing.headline,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    if (briefing.reply.isNotBlank() && briefing.reply != briefing.headline) {
                        Text(
                            text = briefing.reply,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1),
                        )
                    }
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }

                // Vault Note References (clickable Perlite links on delllap)
                if (briefing != null && briefing.vaultRefs.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "VAULT CONTEXT (DELLLAP)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC084FC),
                        )
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
                }

                // Global Orchestrator Lifecycle Actions (START_TMUX / KILL_TMUX / cross-session SEND)
                if (briefing != null && briefing.orchestratorActions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "ORCHESTRATOR ACTIONS (PERMISSION REQUIRED)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CosAmber,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            briefing.orchestratorActions.forEach { action ->
                                CosActionChip(
                                    action = action,
                                    completed = action.id in completedActionIds,
                                    onClick = { onActionClick(action) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Per-Session Decision Cards bubbled up by urgency
        if (briefing != null && briefing.sessions.isNotEmpty()) {
            briefing.sessions.forEach { item ->
                val accent = urgencyColor(item.urgency)
                val highPriority = item.urgency == SessionUrgency.NEEDS_DECISION || item.urgency == SessionUrgency.BLOCKED
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (highPriority) Color(0xFF171B26) else Color(0xFF10141D),
                    border = BorderStroke(
                        width = if (highPriority) 1.2.dp else 1.dp,
                        color = if (highPriority) accent.copy(alpha = 0.55f) else CosCardBorder,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                AiVendorLogo(vendor = item.vendor, size = 20.dp)
                                Text(
                                    text = item.session,
                                    style = MaterialTheme.typography.titleSmall,
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
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = accent,
                                    )
                                }
                            }
                            Surface(
                                onClick = { onJumpToSession(item.session) },
                                shape = CircleShape,
                                color = Color(0xFF2563EB),
                            ) {
                                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Jump to ${item.session}",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }

                        if (item.summary.isNotBlank()) {
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1),
                            )
                        }

                        if (!item.decisionNeeded.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = accent.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    text = "Decision: ${item.decisionNeeded}",
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                )
                            }
                        }

                        if (item.suggestedActions.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                item.suggestedActions.forEach { action ->
                                    CosActionChip(
                                        action = action,
                                        completed = action.id in completedActionIds,
                                        onClick = { onActionClick(action) },
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
private fun CosActionChip(
    action: CosAction,
    completed: Boolean,
    onClick: () -> Unit,
) {
    val chipColor = when {
        completed -> CosGreen
        action.actionType == CosActionType.KILL_TMUX -> CosRed
        action.actionType == CosActionType.START_TMUX -> CosCyan
        action.requiresPermissionDialog -> CosAmber
        else -> CosCyan
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = chipColor.copy(alpha = if (completed) 0.22f else 0.14f),
        border = BorderStroke(1.dp, chipColor.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val icon = when {
                completed -> Icons.Default.Check
                action.actionType == CosActionType.START_TMUX -> Icons.Default.Add
                action.actionType == CosActionType.KILL_TMUX -> Icons.Default.Close
                action.requiresPermissionDialog -> Icons.Default.Warning
                else -> Icons.AutoMirrored.Filled.Send
            }
            Icon(icon, contentDescription = null, tint = chipColor, modifier = Modifier.size(13.dp))
            Text(
                text = if (completed) "${action.label} ✓" else action.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}
