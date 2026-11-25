<a id="readme-top"></a>

<p align="center"><img src="docs/assets/rafiq-logo.svg" width="128" height="128" alt="Rafiq logo: a sitting cat"></p>

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

Rafiq is pre-alpha. The bridge runs Claude Code sessions over ACP and in tmux, and the app pairs with it by QR code, lists your sessions, streams their threads, and lets you answer approvals and send prompts. Pushes tell you when an agent needs you, and you can deny, approve or reply from the notification. To install the bridge without a JDK, see [docs/install.md](docs/install.md). To set it up, run `rafiq-bridge setup`; see [docs/setup.md](docs/setup.md). Plans and progress are on the [GitHub milestones page](https://github.com/tatawurAI/rafiq/milestones).

## Requirements

- JDK 21, only to build from source. The release download of the bridge brings its own Java runtime; see [docs/install.md](docs/install.md).
- The Android SDK, to build the app. The app needs Android 12 (API 31) or newer.
- [Tailscale](https://tailscale.com) on your server and on your phone
- An ACP adapter for your agent, installed on your server. For Claude Code that is `claude-agent-acp`, from npm, and Claude Code itself must be signed in on the server:

  ```sh
  npm install -g @agentclientprotocol/claude-agent-acp
  ```

  To run it without a global install, use `npx` in `RAFIQ_AGENTS` instead, pinned to a version: `claude-code=npx -y @agentclientprotocol/claude-agent-acp@0.109.0`.

## Quickstart

1. Clone the repository.

   ```sh
   git clone https://github.com/tatawurAI/rafiq.git
   cd rafiq
   ```

2. Make a first device token for trying the bridge. It is optional: it becomes a device called `legacy`, and your phones get tokens of their own when you pair them (see below).

   ```sh
   export RAFIQ_DEVICE_TOKEN=$(openssl rand -hex 32)
   export RAFIQ_AGENTS=claude-code=claude-agent-acp
   ```

   Then run the bridge. For development, let Gradle start it:

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

   Every other route needs a device token as `Authorization: Bearer <token>`. The `legacy` token from step 2 works until you revoke it:

   ```sh
   curl -H "Authorization: Bearer $RAFIQ_DEVICE_TOKEN" http://127.0.0.1:8740/sessions
   ```

   You should get `{"status":"ok","version":"..."}`.

4. Install the debug app on a connected phone or emulator.

   ```sh
   ./gradlew :android:installDebug
   ```

### Configuration

The bridge reads these settings from `~/.config/rafiq/bridge.conf`, which `rafiq-bridge setup` writes, and from environment variables, which win over the file. See [docs/setup.md](docs/setup.md#the-settings-file). Nothing reads a `.env` file, so `.env.example` only lists the names and sample values.

| Variable | Purpose | Default |
|---|---|---|
| `RAFIQ_BIND_ADDRESS` | Address the bridge listens on | `127.0.0.1` |
| `RAFIQ_PORT` | Port the bridge listens on | `8740` |
| `RAFIQ_DATA_DIR` | Where the bridge keeps its data, including the event log and the paired devices. Needed to pair phones. Unset keeps events in memory only. | unset |
| `RAFIQ_AGENTS` | Agents you can start sessions with, as `id=command` pairs separated by commas, for example `claude-code=claude-agent-acp`. The command is split on whitespace. There is no shell and no quoting, so an argument cannot contain a space. | unset |
| `RAFIQ_DEV_PAGE` | Set to `1` to serve a plain test page at `/dev` for trying sessions from a browser. The route does not exist otherwise. | unset |
| `RAFIQ_DEVICE_TOKEN` | Optional. Creates the device `legacy`, accepted as `Authorization: Bearer <token>`, so a phone paired before per-device tokens keeps working. At least 32 characters when set. Make one with `openssl rand -hex 32`. Phones pair with a code and get their own token. Sensitive: keep it out of git. | unset |
| `RAFIQ_ALLOWED_HOSTS` | Extra names the bridge accepts in the `Host` header, separated by commas, for example your Tailscale name. The bind address and `localhost` are always accepted. Any other host gets `421`, which blocks DNS rebinding. | unset |

To use the app on your phone, keep the bridge on `127.0.0.1` and publish it over HTTPS with `tailscale serve`, as [docs/setup.md](docs/setup.md) describes.

Pair each phone with a code, not a pasted token. On your server, in the same environment as the bridge, run:

```sh
rafiq-bridge pair --name my-phone
```

It prints a code such as `ABCD-EFGH`. Enter your server address and the code in the app. The code works once and expires in about 10 minutes, and the phone gets a token of its own. `rafiq-bridge devices` lists your phones, and `rafiq-bridge revoke <id>` cuts one off at once. To retire the shared token, run `rafiq-bridge revoke legacy` once your phone is paired, then remove `RAFIQ_DEVICE_TOKEN` from the bridge's environment. A revoked `legacy` stays revoked if the variable lingers.

A request that carries an `Origin` header is refused unless it comes from the bridge itself, so web pages on other sites cannot use it. With `RAFIQ_DEV_PAGE=1`, open `/dev` and paste the token into the page. The page keeps it in memory only.

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
| [docs/install.md](docs/install.md) | Install the bridge from a release, with no JDK |
| [docs/setup.md](docs/setup.md) | First-run setup with `rafiq-bridge setup`, HTTPS through `tailscale serve`, and pairing the app |
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
