# Architecture

This page describes the target design of Rafiq for contributors: the layers, the event model that connects them, the bridge server and the Android app. Decisions and their reasons are in [docs/adr/](adr/README.md).

## What exists today

Rafiq is pre-alpha. Part of what follows is design, not code.

| Part | Built |
|---|---|
| `:protocol` | The event types, with their serialisation and the unknown-type fallback, and the request and response bodies of the bridge's routes |
| `:client` | The repository interfaces and screen-state types the app's screens use, the reducer that folds events into screen state, and the sync loop that resumes each session's socket from the last cached `seq` |
| Bridge | The SQLite event log, the session manager, the ACP adapter, `WS /events` with replay, the session, summary and agent routes, a flag-gated dev page, and a shared device token with host and origin checks. It binds to `127.0.0.1` by default. |
| Android app | Pairing with a typed URL and token (the token is encrypted with a Keystore key), an offline event cache in Room, the session list, the session view with thread tabs, tool cards and approvals, and a composer with quick replies and on-device voice input. Half-folded, the session view and the list split at the hinge. |

Everything else on this page, including the file and git routes, per-device tokens, the auth relay and push, is planned. Milestones are tracked on the [GitHub milestones page](https://github.com/tatawurAI/rafiq/milestones).

## Principles

- **Agent-agnostic.** The app knows only generic sessions, threads and events. Agent-specific logic lives in adapters on the bridge.
- **Chat first, terminal when you need it.** The app shows messages, tool-call cards, diffs and approvals for every session. For a session in tmux that you have allowed, it also offers a terminal view of the real pane, as the ground truth and the fallback. It is not a general terminal client. See [ADR 0013](adr/0013-agents-in-tmux.md).
- **Fold-native.** The cover screen, the open screen and Flex Mode (half-folded) each have their own design, with continuity between them.
- **Private by default.** All traffic goes over Tailscale. Credentials stay on your server. The phone only approves.

Non-goals for v1: a general terminal client (SSH stays the out-of-band admin path), mosh or any terminal-state protocol beyond tmux's own control mode, iOS, the Play Store, multiple users, and running agents on the phone.

## Overview

Rafiq has three layers. The phone talks to a bridge server over a WebSocket. The bridge talks to each agent over the Agent Client Protocol (ACP), a JSON-RPC protocol over stdio, using the official [`agentclientprotocol/kotlin-sdk`](https://github.com/agentclientprotocol/kotlin-sdk). Everything rides on [Tailscale](https://tailscale.com).

```
  Phone app  <-- WebSocket (JSON events) -->  Bridge  <-- ACP over stdio -->  Agent adapter
       \________________ Tailscale (WireGuard) ________________/
```

The code is Kotlin on both ends, built by one Gradle build. A shared `:protocol` module holds the event model, so the bridge and the app cannot drift apart. A pure Kotlin `:client` module holds the app's logic that needs no Android: the screen state, the reducer from events to that state, and the replay cursor. See [ADR 0011](adr/0011-a-pure-kotlin-client-module-for-app-logic.md) and [ADR 0003](adr/0003-kotlin-on-both-ends-with-a-shared-protocol-module.md).

| Module | Kind | Depends on |
|---|---|---|
| `:protocol` | Kotlin/JVM library | nothing in the build |
| `:client` | Kotlin/JVM library | `:protocol` |
| `:bridge` | JVM application | `:protocol` |
| `:android` | Android application | `:client` |

### Links

| Link | Protocol | Purpose |
|---|---|---|
| Phone to server network | Tailscale (WireGuard) | Private, stable address from anywhere. No public ports. |
| Phone to bridge | WebSocket, JSON events | Streams tokens and events down. Prompts and approvals go up. |
| Bridge to agents | ACP over stdio | The bridge launches each agent's ACP adapter (for example `claude-agent-acp` or `codex-acp`) as a local process. |
| Bridge to phone, app closed | Push (transport undecided, see [ADR 0010](adr/0010-push-transport.md)) | "Agent needs you" pings. Tapping one reopens the socket. |
| Phone to dev servers | HTTP over Tailscale, `tailscale serve` for HTTPS | Live previews of what the agent builds. |
| Admin fallback | SSH | Out-of-band repair when the bridge is down. |

### Reconnecting

The bridge keeps an append-only event log per session. Each event has a sequence number (`seq`). On reconnect, the phone sends the last `seq` it saw, and the bridge replays everything after it. This covers dropped signal, folding the phone and app restarts. See [ADR 0005](adr/0005-reconnect-by-sequence-replay.md).

## Event model

The event model is the contract between the bridge and the app. It lives in the shared `:protocol` Kotlin module.

| Object | Key fields | Rendered as |
|---|---|---|
| Session | id, agent, project path, title, status, created and updated times | Session list row, cover-screen card |
| Thread | id, session id, parent thread id, kind (main, subagent, worker), title, status | Tab or drawer entry in a session |
| Message | id, thread id, role, content blocks (text, markdown, image), streaming flag | Chat bubble |
| Tool call | id, thread id, kind (edit, command, search, read, fetch, other), input summary, output, status | Collapsible card, with a diff viewer for edits |
| Approval request | id, tool call id, description, options (allow once, allow always, deny), expiry | Inline buttons, notification actions, cover-screen card |
| Approval resolved | approval id, outcome (selected, cancelled, withdrawn), chosen option | Removes the buttons. Sent once for every approval that goes away. A bridge restart or shutdown cancels the ones still open. |
| Status | session or thread id, state (working, waiting on you, done, error), detail | Badge and colour. Drives notifications. |
| Auth required | id, provider, kind (device code or redirect), URL, code, expiry | "Sign in" card, in-app browser |
| Preview | id, URL, label, port | "Open preview" card, in-app browser |
| File change | path, change type, diff reference, git status | File chip that opens the file viewer |

Every event carries an envelope:

- `session_id`: the session it belongs to.
- `seq`: a number that increases monotonically within the session.
- `ts_ms`: when the bridge recorded the event, in milliseconds since the Unix epoch (UTC).

Output the app does not recognise falls back to a plain Message, so a new agent feature degrades to readable text. Evolution rules are in [STYLE.md](../STYLE.md#wire-protocol). See [ADR 0004](adr/0004-the-app-knows-only-generic-sessions-threads-and-events.md).

## Bridge server

The bridge is one long-running JVM process built on Ktor. systemd manages it. It listens on `127.0.0.1`, and `tailscale serve` puts it behind HTTPS at your server's `*.ts.net` name. See [ADR 0012](adr/0012-https-through-tailscale-serve.md).

### Adapters

A generic ACP adapter spawns any ACP agent and maps ACP updates to events. The adapter interface is:

| Member | Purpose |
|---|---|
| `start` | Begin a new agent session in a project folder |
| `resume` | Reattach to an existing agent session |
| `prompt` | Send the user's message to the agent |
| `cancel` | Stop the current turn |
| `respondToApproval` | Deliver the user's answer to an approval request |
| event stream | Emits protocol events as the agent works |

### API

The health, session, summary, agent, thread, prompt, cancel, approval and event routes are implemented. The file, git and auth routes are planned. Every route except `GET /health` and `GET /dev` needs `Authorization: Bearer <RAFIQ_DEVICE_TOKEN>`. `WS /events` also accepts the token as a `Sec-WebSocket-Protocol` value next to `rafiq.v1`, because a browser cannot set headers on a WebSocket. A token in the query string is never accepted. Errors always have the shape `{"error":{"code":"...","message":"..."}}`.

| Method and path | Purpose |
|---|---|
| `GET /health` | Liveness and version. Needs no device token. |
| `GET /dev` | A plain test page. Exists only when `RAFIQ_DEV_PAGE=1`. Needs no token, because the page asks for it. |
| `GET /dev/agents` | The configured agent ids, for the test page. Exists only when `RAFIQ_DEV_PAGE=1`. |
| `WS /events?session=&after=` | Stream a session's events, replaying from sequence `after` |
| `POST /sessions` | Start a session (agent, project) |
| `GET /sessions` | List sessions |
| `GET /sessions/summaries` | List sessions with what the session list shows: the newest event `seq`, a one-line preview of the newest agent or user message on the main thread, and the approvals still open, oldest first. Read from small summary tables kept with the log, so polling it is cheap. |
| `GET /agents` | The agents you can start a session with, as `{"id": ...}` objects |
| `GET /sessions/{id}/threads` | List a session's threads |
| `POST /sessions/{id}/prompt` | Send a prompt. Prompts go to the main thread, and a helper thread is refused. |
| `POST /sessions/{id}/threads/{threadId}/cancel` | Stop the current turn on a thread |
| `POST /approvals/{id}` | Answer an approval request |
| `GET /files?path=` | List files |
| `GET /files/content?path=` | Read a file |
| `GET /git/diff?session=` | Diff, scoped to the session's project folder |
| `POST /auth/{id}/callback` | Complete a sign-in relay |

#### GET /health

Returns `200` with a JSON body:

```json
{"status":"ok","version":"0.1.0"}
```

`version` is the bridge's build version. The route is unauthenticated on purpose: it reveals only that the bridge is up and which version it is, and it is the first thing to check when your phone cannot connect.

```sh
curl http://127.0.0.1:8740/health
```

### Configuration

The bridge reads environment variables at start-up. Nothing reads a `.env` file. Export the variables in your shell, or put them in a systemd unit's `EnvironmentFile=`.

| Variable | Meaning | Default |
|---|---|---|
| `RAFIQ_BIND_ADDRESS` | Address to listen on. Keep the default and use `tailscale serve`. | `127.0.0.1` |
| `RAFIQ_PORT` | Port to listen on | `8740` |
| `RAFIQ_DATA_DIR` | Folder for the database and logs. Unset keeps events in memory only. | unset |
| `RAFIQ_AGENTS` | Agents as `id=command` pairs separated by commas | unset |
| `RAFIQ_DEV_PAGE` | `1` serves a plain test page at `/dev` | unset |
| `RAFIQ_DEVICE_TOKEN` | Secret every request but `/health` presents as a bearer token. At least 32 characters, and required when `RAFIQ_AGENTS` is set. Treat as sensitive. | unset |
| `RAFIQ_ALLOWED_HOSTS` | Extra `Host` values to accept, for example your Tailscale name | unset |

### Auth relay

Agents sometimes need you to sign in to a provider. The bridge handles two kinds of flow:

- **Device code.** The bridge detects the code and URL in the agent's output and emits an Auth required event. You enter the code in a browser.
- **Localhost redirect.** The app opens the login page in an in-app browser. When the provider redirects to `http://localhost:PORT/...`, the app intercepts that final redirect and posts it to the bridge, which requests it locally so the agent receives it.

### Previews

The bridge watches for new listening ports under a session's processes. When one appears, it emits a Preview event with the Tailscale URL.

### Storage and security

- SQLite holds sessions, threads and the event log, plus two small summary tables (the newest message preview and the open approvals per session) written in the same transaction as each event.
- Tailscale ACLs restrict the bridge's port to your devices. A device token adds a second check, and the bridge enforces it now. The `Host` header must be the bind address, `localhost` or a listed name (421 otherwise), and a request with a foreign `Origin` is refused, so a web page cannot reach the bridge through DNS rebinding. Pairing, Keystore storage and revocation come with M3. See [ADR 0012](adr/0012-https-through-tailscale-serve.md), and [setup.md](setup.md) for the `tailscale serve` steps.
- The file API refuses paths outside the session's project root.
- Approvals require a fresh biometric check on the phone.
- Push payloads carry ids and short status only.

For the vulnerability policy, see [SECURITY.md](../SECURITY.md).

## Android app

The app is native Kotlin and Jetpack Compose, installed directly (no Play Store). It is native because the cover screen, the fold posture and notification actions need platform APIs. See [ADR 0007](adr/0007-native-compose-for-fold-and-cover-screen-apis.md).

### Stack

| Concern | Choice |
|---|---|
| UI | Jetpack Compose |
| Socket | OkHttp WebSocket |
| Concurrency | Kotlin coroutines and Flow |
| Offline history | Room cache |
| Device token | Android Keystore |
| Approval check | BiometricPrompt |
| Fold posture | Jetpack WindowManager |
| Push | FCM or ntfy (undecided, see [ADR 0010](adr/0010-push-transport.md)) |
| Content | Markdown and syntax highlighting, diff view, in-app browser |

### Screens

- **Session list:** status badges, inline approve and deny.
- **Session view:** thread tabs, chat with tool-call cards, and a composer with voice input and quick replies.
- **File and diff viewer.**
- **Preview browser.**
- **Settings:** servers, agents, quick-reply macros and notification rules.

### Fold modes

| Fold mode | Job | Design |
|---|---|---|
| Closed (cover screen) | Triage without opening | One card per session: status, last line, approve and deny, voice reply, quick replies. A cover-screen widget comes first. |
| Open | Full work | Chat, session drawer and thread tab strip. |
| Half-folded (Flex Mode) | Read on top, act below | Conversation on top. Composer, quick replies and thread switcher below. |
| Unfold transition | Continuity | Unfolding opens the session or card from the cover screen, scrolled to the latest event. |

### Visual design

The app is dark first. Touch targets are at least 44dp. Colours come from theme tokens, never from literals in a composable.

| Token | Value |
|---|---|
| Ground | `#15191B` |
| Surface | `#1E2426` |
| Border | `#2A3234`, `#3F4A4C` |
| Text | `#F7F6F3` |
| Muted text | `#A8B0AD`, `#D8DCDA` |
| Accent | `#3FD9BC` |
| Needs you | `#F2B84B` |
| Deletion | `#F2777A` |

Type: Archivo for headings (600 and 700), IBM Plex Sans for body text, IBM Plex Mono for labels and code. Font licences are in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
