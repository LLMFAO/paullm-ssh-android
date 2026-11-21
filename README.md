# paullm-ssh for Android

Native Android SSH, Mosh, `tmux`, and SFTP client tailored for remote AI coding workflows (Claude Code, Codex CLI, Grok CLI, OpenCode, Antigravity, Gemini CLI, Pi, Aider, Goose) — built on **ConnectBot 1.12** (Apache-2.0, Kotlin + Jetpack Compose + Material 3 + `cbssh` + `termlib`).

## Highlights

- **Host Lab Dashboard (`📊 Dashboard | >_ Terminal | 📁 Files`)**
  - Live remote OS/kernel card with 1-tap **Connect / Disconnect**
  - Live `tmux` Sessions card with official `@lobehub/icons` AI vendor logos, `Loaded` tab indicator, 1-tap blue **Attach**, and red **Detach / Kill**
  - Live **CPU** & **Memory** circular donut gauges, **Network** dual-ring throughput/totals gauge, and **Volumes** disk usage bars
- **True Multi-Session `tmux` Tabs (SSH-Multiplexed)**
  - Every `tmux` session opens in its own dedicated PTY channel (`exec tmux attach-session -t '=<name>'`, never `-d`) multiplexed over your host's authenticated `SshClient` — zero re-auth prompts and zero commands typed into the wrong window
  - Always-visible pill tab strip with AI vendor logos, live status dots, 1-tap `×` client-only detach, and 1-tap attach for discovered remote sessions
  - Session launcher with verified **YOLO / Skip Permissions** flags (toggleable per launch) + custom **Start dir**
- **Smooth Mobile TUI Touch Scrolling**
  - Streaming DEC private-mode scanner (`?1000`/`?1002`/`?1003`/`?1006` mouse reporting + `?47`/`?1047`/`?1049` alternate screen) translates single-finger drags into SGR mouse-wheel events (`ESC[<64/65;col;rowM`) for **OpenCode**, **Codex**, **Claude Code**, **`tmux`**, **`vim`**, and **`htop`**
- **Inline Compose & Native Voice Dictation Bar**
  - Per-tab persisted multiline drafts with target badge (`Sending to <session>`)
  - Runtime `RECORD_AUDIO` permission + in-app Android `SpeechRecognizer` live partial transcription (with `RecognizerIntent` fallback)
  - 4 send modes: **Enter**, **Agent prompt** (bracketed paste + submit), **Paste-safe**, and **Raw**, plus AI quick prompts and **Saved Command Presets**
- **Native SFTP Remote File Browser**
  - Powered by `cbssh`'s `SftpClient` over the active SSH connection (including jump hosts & hardware keys)
  - Browse, hidden files, bounded 256 KiB UTF-8 text preview + copy, safe staged uploads with overwrite protection, SAF download with progress/cancel, Share via `FileProvider`, mkdir, rename/move, octal `chmod`, and confirmed delete
- **AI Vibe Toolkit, LAN SSH Discovery, Mosh, Jump Hosts & Security**
  - Host scan & 1-tap install/launch/auth for AI CLIs and repo templates
  - mDNS (`_ssh._tcp`) local network SSH discovery
  - Full ConnectBot foundation: Ed25519/ECDSA/RSA/Password/Keystore/FIDO2/YubiKey, Jump Hosts, Port Forwards, Mosh, Custom Themes/Fonts/Keyboards, Biometric App Lock, and `FLAG_SECURE` Privacy Mode

## Building from Source

```bash
ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleOssDebug
```

Output APK: `app/build/outputs/apk/oss/debug/app-oss-debug.apk`

## License & Attribution

- Base application: **ConnectBot** — Copyright 2007–2026 Kenny Root & contributors, licensed under the **Apache License 2.0**.
- `paullm-ssh` Android extensions (`app/src/main/java/org/connectbot/paullm/`): Copyright 2026 Paul Pettit, licensed under **GNU GPL v3.0**.
- AI vendor brand icons sourced from [`@lobehub/icons`](https://github.com/lobehub/lobe-icons).
