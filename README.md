<a id="readme-top"></a>

# Rafiq

Run and steer coding-agent sessions on your own server from your phone, in a chat interface and not a terminal.

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/platform-Android-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)

Rafiq (Arabic for "companion") is a native Android app, tuned for the Samsung Galaxy Z Flip, plus a small server called the bridge. The bridge runs your coding agents on your server. The app shows their messages, tool calls, diffs and approval requests, and lets you answer from the cover screen without opening the phone. It starts with Claude Code and is built to take on other agents without app changes.

## Contents

- [How it works](#how-it-works)
- [Status](#status)
- [Requirements](#requirements)
- [Quickstart](#quickstart)
- [Repository layout](#repository-layout)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Support](#support)
- [License](#license)

## How it works

Three layers carry a session from your agent to your phone.

```
  Phone (Rafiq app)
        |
        |  WebSocket, JSON events
        |  over Tailscale (WireGuard)
        v
  Your server
  +--------------------------------------+
  |  Bridge (Ktor, SQLite event log)     |
  |     |            |            |      |
  |    ACP          ACP          ACP     |
  |   (stdio)      (stdio)      (stdio)  |
  |     v            v            v      |
  |  agent A      agent B      agent C   |
  +--------------------------------------+
```

- **Phone to bridge.** The app opens a WebSocket to the bridge. Events stream down. Prompts and approvals go up. All traffic stays on your [Tailscale](https://tailscale.com) network, so there are no public ports.
- **Bridge to agents.** The bridge launches each agent's Agent Client Protocol (ACP) adapter as a local process and speaks ACP, a JSON-RPC protocol, to it over stdio. It maps what the agent reports into Rafiq's own events: sessions, threads, messages, tool calls, approvals and status.
- **The app is agent-agnostic.** It knows only those generic events. Anything specific to one agent lives in an adapter on the bridge.
- **Reconnecting is cheap.** The bridge keeps an append-only event log per session, numbered in order. After a dropped signal, a fold or an app restart, the app sends the last number it saw and the bridge replays the gap.
- **Credentials stay on your server.** The phone approves actions. It never holds your agent's credentials.

Read [docs/architecture.md](docs/architecture.md) for the full picture.

## Status

Rafiq is pre-alpha and nothing is released. Today the bridge answers `GET /health`, the protocol types exist, and the app is a themed shell. Plans and progress are on the [GitHub milestones page](https://github.com/tatawurAI/rafiq/milestones).

## Requirements

- JDK 21
- The Android SDK, to build the app. The app needs Android 12 (API 31) or newer.
- [Tailscale](https://tailscale.com) on your server and on your phone
- An ACP adapter for your agent, installed on your server. For Claude Code that is `claude-agent-acp`.

## Quickstart

1. Clone the repository.

   ```sh
   git clone https://github.com/tatawurAI/rafiq.git
   cd rafiq
   ```

2. Run the bridge. For development, let Gradle start it:

   ```sh
   ./gradlew :bridge:run
   ```

   To run it as an installed program, build it once and start the script:

   ```sh
   ./gradlew :bridge:installDist
   bridge/build/install/rafiq-bridge/bin/rafiq-bridge
   ```

3. In another terminal, check that it is up. It listens on `127.0.0.1:8740` unless you configure otherwise.

   ```sh
   curl http://127.0.0.1:8740/health
   ```

   You should get `{"status":"ok","version":"..."}`.

4. Install the debug app on a connected phone or emulator.

   ```sh
   ./gradlew :android:installDebug
   ```

### Configuration

The bridge reads environment variables. Export them in your shell, or use `EnvironmentFile=` in a systemd unit. Nothing reads a `.env` file, so `.env.example` only lists the names and sample values.

| Variable | Purpose | Default |
|---|---|---|
| `RAFIQ_BIND_ADDRESS` | Address the bridge listens on | `127.0.0.1` |
| `RAFIQ_PORT` | Port the bridge listens on | `8740` |
| `RAFIQ_DATA_DIR` | Where the bridge keeps its data | unset |
| `RAFIQ_DEVICE_TOKEN` | Token a phone must present. Sensitive: keep it out of git. | unset |

For real use, set `RAFIQ_BIND_ADDRESS` to your server's Tailscale address so only your devices can reach the bridge.

## Repository layout

```
rafiq/
├── protocol/      Kotlin module: event model, shared by bridge and app
├── bridge/        JVM app: Ktor, ACP Kotlin SDK, SQLite event log
├── android/       ai.tatawur.rafiq, Jetpack Compose app
├── docs/          architecture.md, adr/
├── changelog.d/   one-sentence changelog fragments, one per pull request
├── gradle/        version catalog (libs.versions.toml)
└── .github/       issue forms, pull request template, workflows
```

## Documentation

| Document | What is in it |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layers, event model, bridge server, Android app |
| [docs/adr/](docs/adr/) | Architecture decision records: what we decided and why |
| [STYLE.md](STYLE.md) | Code, comment, protocol, test and docs conventions |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Setup, pull request flow, changelog fragments |

## Contributing

Contributions are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md). By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Support

Ask questions in [GitHub Discussions](https://github.com/tatawurAI/rafiq/discussions) and file bugs and feature requests with the issue forms. See [SUPPORT.md](SUPPORT.md). Report security problems privately as described in [SECURITY.md](SECURITY.md), never in a public issue.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Bundled fonts are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

[Back to top](#readme-top)
