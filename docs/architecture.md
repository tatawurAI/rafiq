# Architecture

This page describes the target design of Rafiq for contributors: the layers, the event model that connects them, the bridge server and the Android app. Decisions and their reasons are in [docs/adr/](adr/README.md).

## What exists today

Rafiq is pre-alpha. Part of what follows is design, not code.

| Part | Built |
|---|---|
| `:protocol` | The event types, with their serialisation and the unknown-type fallback, the request and response bodies of the bridge's routes, and the messages of the terminal socket |
| `:client` | The repository interfaces and screen-state types the app's screens use, the reducer that folds events into screen state, and the sync loop that resumes each session's socket from the last cached `seq` |
| Bridge | The SQLite event log, the session manager, the ACP adapter, `WS /events` with replay, the session, summary and agent routes, a flag-gated dev page, and a shared device token with host and origin checks. It binds to `127.0.0.1` by default. Behind a gate that is off by default, it also lists allowed tmux sessions and mirrors one to the phone over `WS /terminal`. |
| Android app | Pairing with a typed URL and token (the token is encrypted with a Keystore key), an offline event cache in Room, the session list, the session view with thread tabs, tool cards and approvals, and a composer with quick replies and on-device voice input. Half-folded, the session view and the list split at the hinge. |

Everything else on this page, including the file and git routes, per-device tokens, the auth relay and push, is planned. Milestones are tracked on the [GitHub milestones page](https://github.com/tatawurAI/rafiq/milestones).

## Principles

- **Agent-agnostic.** The app knows only generic sessions, threads and events. Agent-specific logic lives in adapters on the bridge.
- **Chat first.** The app shows messages, tool-call cards, diffs and approvals. For a tmux session on your server it can also show the real terminal, but only after you turn that on (see [Terminal](#terminal)). ADR 0013 records the decision.
- **Fold-native.** The cover screen, the open screen and Flex Mode (half-folded) each have their own design, with continuity between them.
- **Private by default.** All traffic goes over Tailscale. Credentials stay on your server. The phone only approves.

Non-goals for v1: a terminal for anything but the tmux sessions you allow (SSH stays the out-of-band admin path), mosh or any terminal-state protocol, iOS, the Play Store, multiple users, and running agents on the phone.

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
| Session | id, agent, project path, title, status, created and updated times, and whether it runs in tmux (`terminal`) | Session list row, cover-screen card |
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

The health, session, summary, agent, thread, prompt, cancel, approval and event routes are implemented. The file, git and auth routes are planned. Every route except `GET /health`, `GET /dev` and `POST /pair` needs a device token as `Authorization: Bearer <token>`. `WS /events` and `WS /terminal` also accept the token as a `Sec-WebSocket-Protocol` value next to `rafiq.v1` or `rafiq.term.v1`, because a browser cannot set headers on a WebSocket. A token in the query string is never accepted. The token of a revoked device gets `401` with the code `device_revoked`, so the app can tell "pair again" from "wrong token". Errors always have the shape `{"error":{"code":"...","message":"..."}}`.

| Method and path | Purpose |
|---|---|
| `GET /health` | Liveness and version. Needs no device token. |
| `GET /dev` | A plain test page. Exists only when `RAFIQ_DEV_PAGE=1`. Needs no token, because the page asks for it. |
| `GET /dev/agents` | The configured agent ids, for the test page. Exists only when `RAFIQ_DEV_PAGE=1`. |
| `WS /events?session=&after=` | Stream a session's events, replaying from sequence `after` |
| `WS /terminal?session=` | Mirror a tmux session's pane and take your keys. Exists only when Terminal is on. See [Terminal](#terminal). |
| `POST /sessions` | Start a session (agent, project) |
| `GET /sessions` | List sessions. With Terminal on, this includes the allowed tmux sessions. |
| `GET /sessions/summaries` | List sessions with what the session list shows: the newest event `seq`, a one-line preview of the newest agent or user message on the main thread, and the approvals still open, oldest first. Read from small summary tables kept with the log, so polling it is cheap. |
| `GET /agents` | The agents you can start a session with, as `{"id": ...}` objects |
| `POST /pair` | Trade a pairing code for a device token. Needs no token. Wrong codes are counted across all callers, and after 5 in 5 minutes every attempt is refused for a while. |
| `DELETE /devices/me` | Revoke the calling device and close its sockets. It cannot touch another device. |
| `GET /sessions/{id}/threads` | List a session's threads |
| `POST /sessions/{id}/prompt` | Send a prompt. Prompts go to the main thread, and a helper thread is refused. A tmux session answers `409` with `terminal_only`, because it opens in Terminal. |
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
| `RAFIQ_DEVICE_TOKEN` | A shared secret, accepted as the device `legacy` until you revoke it, so an existing pairing keeps working. At least 32 characters, and required when `RAFIQ_AGENTS` is set. Treat as sensitive. New phones pair with a code instead. | unset |
| `RAFIQ_ALLOWED_HOSTS` | Extra `Host` values to accept, for example your Tailscale name | unset |
| `RAFIQ_TERMINAL` | `1` turns on Terminal and tmux sessions. See [Terminal](#terminal). | unset (off) |
| `RAFIQ_TMUX_SESSIONS` | The exact names of the tmux sessions the phone may see and open, separated by commas. No wildcard. | unset |
| `RAFIQ_TMUX_SOCKET` | The tmux socket name (`tmux -L`), to use another tmux server than the default | unset (default server) |
| `RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK` | `1` lets the bridge bind to a non-loopback address while Terminal is on | unset |

### Terminal

Terminal shows the real screen of a tmux session on your server in the app, and sends your keys to it. It is a remote shell, so it is off until you turn it on, and it reaches only the sessions you name. [setup.md](setup.md#terminal) shows how to turn it on.

**The gate.** With `RAFIQ_TERMINAL` unset, the bridge lists no tmux session, `WS /terminal` closes with "no such session" and no tmux command runs. With it on:

- `RAFIQ_TMUX_SESSIONS` must name the sessions. Names are matched exactly and may use only letters, digits, `.`, `_` and `-`. There is no `*`. A bad entry fails the start and names the variable.
- A grant covers every window and pane of the session. The app mirrors the active pane of the session's current window at the moment you open Terminal.
- The bridge refuses to start bound to a non-loopback address, unless `RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK=1`. Publish it with `tailscale serve`, never `tailscale funnel`.
- At most 2 terminal sockets per device and 4 in all. Columns are clamped to 20 to 500 and rows to 5 to 200 on every resize, the first one included. A frame of keys may be at most 16 KiB, and a socket may send at most 64 KiB of keys per second. A socket that sends nothing for 10 minutes is closed and detached.
- Every `/events` and `/terminal` socket is registered under the device that opened it, so revoking a device closes its sockets at once. A closed terminal detaches its view like any other close.
- The bridge logs one line when a terminal socket opens and one when it closes, with the device, session, address, duration, bytes each way and resize count. It never logs keystrokes.

**tmux.** Terminal needs tmux 3.2 or newer, for `pause-after`. It also needs `window-size latest`, which is tmux's default: the window follows whichever client acted last.

**Tmux sessions in the list.** Each allowed session shows in `GET /sessions` as `tmux-<name>`, with `terminal` true and the agent `claude-code` when a `claude` process runs under one of its panes, otherwise `terminal`. The bridge looks at the pane's process tree, and asks `claude agents --json` when that works. No hook is needed. The list is refreshed every 3 seconds. A session tmux no longer has leaves the list, and one the bridge had marked as working or waiting becomes done, so it never keeps showing "Waiting on you". Until the first refresh has finished, nothing is listed. Sessions named `rafiq-view-...` are the bridge's own and are never listed.

**How the view attaches.** The bridge runs `tmux -C new-session -t <session> -s rafiq-view-<id>` as a plain child process, with no pseudo-terminal. That makes a grouped view session of its own, so picking a window in Terminal never moves the desk's window. The view session is `destroy-unattached`, so it goes away when the bridge or the socket does. When the bridge starts it kills leftover `rafiq-view-` sessions, on its own tmux server only.

**Size.** The first `resize` from the phone sets the size the view attaches with, so the desk is never resized to a guess. Each size is `refresh-client -C <cols>x<rows>` followed by `switch-client`, which is what makes the phone the client tmux follows. The bridge repeats `switch-client` when the phone sends keys, at most once a second. It never does it on a layout change, or it would fight the desk on every keystroke. It never uses `ignore-size`, `-g` or `resize-window`. Leaving Terminal detaches the view, and the window goes back to the desk's size. If no desk client is attached, nothing takes the size back, and the window keeps the phone's size.

**Wire.** Open `WS /terminal?session=<id>` with the subprotocol `rafiq.term.v1` next to the token. Down, a binary frame is raw pane output and a text frame is a JSON message from `:protocol`: `snapshot` (size, cursor and terminal modes), `size` and `ended`. A `snapshot` is always followed at once by one binary frame holding the screen. Up, a text `resize` and binary frames of keys. The bridge sends a fresh `snapshot` after every resize, and after any time it had to drop output because the phone was slow. The snapshot and the live stream join with no gap and no repeat, because tmux runs the size query and the screen capture in one command line.

**Keys.** The bridge passes on only what a person typing could send: printable text, Enter, Tab, Backspace, Ctrl keys, Escape, Alt plus a key, arrows, Home, End, Insert, Delete, page keys, function keys with their modifiers, and the bracketed paste markers. Everything else is dropped, including the replies a terminal emulator makes to the program's questions. tmux already answers those, so a reply that reached the pane would be read as typing.

### Devices

Each phone pairs once and gets a token of its own.

- `rafiq-bridge pair --name <device>` prints a pairing code and, when `RAFIQ_ALLOWED_HOSTS` names your server, the address to enter in the app. The code has 8 characters, works once and expires in about 10 minutes. The app trades it at `POST /pair` for a token, which it keeps in the Keystore.
- The bridge stores only a digest of each token and each code, in a `devices` table in the same SQLite file as the event log. A token is 256 random bits.
- `RAFIQ_DEVICE_TOKEN` keeps working as one device called `legacy`, so an existing pairing needs no change.
- `rafiq-bridge devices` lists devices with when each was last seen. `rafiq-bridge revoke <id>` revokes one. The next request from it gets `401` with `device_revoked`, and its open `/events` and `/terminal` sockets close at once.
- These commands talk to the running bridge over a Unix socket in `<RAFIQ_DATA_DIR>/admin/`, in a folder only your user can enter. They never use the network port, and they need the same `RAFIQ_DATA_DIR` as the bridge.

### Auth relay

Agents sometimes need you to sign in to a provider. The bridge handles two kinds of flow:

- **Device code.** The bridge detects the code and URL in the agent's output and emits an Auth required event. You enter the code in a browser.
- **Localhost redirect.** The app opens the login page in an in-app browser. When the provider redirects to `http://localhost:PORT/...`, the app intercepts that final redirect and posts it to the bridge, which requests it locally so the agent receives it.

### Previews

The bridge watches for new listening ports under a session's processes. When one appears, it emits a Preview event with the Tailscale URL.

### Storage and security

- SQLite holds sessions, threads and the event log, plus two small summary tables (the newest message preview and the open approvals per session) written in the same transaction as each event.
- Tailscale ACLs restrict the bridge's port to your devices. Each phone has a device token of its own, checked against the device store on every request and every socket handshake. The `Host` header must be the bind address, `localhost` or a listed name (421 otherwise), and a request with a foreign `Origin` is refused, so a web page cannot reach the bridge through DNS rebinding. See [Devices](#devices) for pairing and revoking. See [ADR 0012](adr/0012-https-through-tailscale-serve.md), and [setup.md](setup.md) for the `tailscale serve` steps.
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
