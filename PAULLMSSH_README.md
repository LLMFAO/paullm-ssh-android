# paullmSSH for Android (ConnectBot-based Edition)

Native Kotlin + Jetpack Compose + Material 3 Android SSH client built on top of upstream **ConnectBot 1.12** (Apache-2.0) with `paullm-ssh` features integrated directly over the active SSH connection.

## Inherited Core Capabilities (from ConnectBot)
- **SSH Transport (`cbssh`)**: Ed25519, ECDSA, RSA, Password, Key + Passphrase, Android Keystore, Biometric unlock, FIDO2/YubiKey PIV, Jump Host chains (`ProxyJump`), Local/Remote/Dynamic Port Forwarding, Strict Known Hosts, Crypto Policy editor.
- **Mosh Transport**: Built-in Mosh roaming support.
- **Terminal Emulator (`termlib`)**: Full ANSI/Unicode/TUI support, resize, selection, clipboard, URL scan, inline images, Zmodem, custom themes & palettes, Nerd/custom fonts, reorderable extra keyboard & macros, connection automation, foreground service & notifications.
- **Portability & Security**: JSON host/profile export & import, Biometric App Lock on launch (`authOnLaunch`).

## Added paullmSSH Features (`org.connectbot.paullm`)
1. **Inline Compose & Voice Bar (`ComposeDraftBar.kt`)**:
   - Docked multiline composer in `ConsoleScreen` with per-host in-memory drafts. Drafts currently survive tab changes and reconnects, but not process death.
   - Dedicated Android Speech Recognition (`Mic`) button, Paste, Quick AI Prompt Snippets, Insert Newline, Clear.
   - Four send modes matching iOS `paullm-ssh`: **Enter**, **Agent prompt** (bracketed paste + submit delay), **Paste-safe** (bracketed paste only), and **Raw**.
2. **Live tmux Session Manager (`TmuxSheet.kt`)**:
   - Discovers active remote tmux sessions (`name`, window count, attached count, working directory) over an independent SSH `exec` channel.
   - One-tap exact attach (`tmux attach-session -t '=<name>'`, never `-d`).
   - Quick "+ New Session" launcher with AI presets: **Shell**, **Claude**, **Codex**, **OpenCode**, **Antigravity**.
   - **Detach** sends `Ctrl-B d` to the active terminal. Use only while that terminal is attached to tmux; connection-specific client verification is not yet implemented. Includes one-tap tmux installer when missing.
3. **Full SFTP Remote File Browser (`SftpBrowserDialog.kt`)**:
   - Powered directly by `cbssh`'s native `SftpClient` over the live SSH session (works transparently over jump hosts and hardware keys).
   - Browse, parent/home navigation, hidden-file toggle, bounded 256 KiB UTF-8 text preview + copy, SAF upload & download with progress & cancel, share via `FileProvider`, mkdir, rename/move, octal chmod, and confirmed delete. Uploads use remote staging and replacement rollback; download destination cleanup on interruption remains incomplete.
4. **AI Vibe Toolkit (`ToolkitSheet.kt`)**:
   - Scans the remote host for Claude Code, Codex CLI, OpenCode CLI, Antigravity CLI, Aider, Goose, `AGENTS.md`, and `docs/ai/context.md`.
   - One-tap Launch (in tmux), Install (with confirmation showing exact command), and Auth setup.
5. **Live Server Stats (`ServerStatsSheet.kt`)**:
   - Real-time remote Hostname/OS, Uptime, Load Average, CPU %, Memory, and Disk (`/`) metrics over SSH exec with 3-second auto-refresh toggle.
6. **Privacy Mode (`PaullmPrivacyDialog.kt`)**:
   - `FLAG_SECURE` toggle to block screenshots and Android Recents previews, paired with ConnectBot's Biometric App Lock.

## Building
```bash
ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleOssDebug
```
Output APK: `app/build/outputs/apk/oss/debug/`

## Attribution & Licensing
- Base client: ConnectBot (Copyright 2007-2026 Kenny Root & contributors, Apache License 2.0).
- paullmSSH additions (`app/src/main/java/org/connectbot/paullm/`): Copyright 2026 Paul Pettit, GNU GPL v3.0.
