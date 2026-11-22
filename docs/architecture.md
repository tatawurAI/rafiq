# Architecture

This page describes the target design of Rafiq for contributors: the layers, the event model that connects them, the bridge server and the Android app. Decisions and their reasons are in [docs/adr/](adr/README.md).

## What exists today

Rafiq is pre-alpha. Part of what follows is design, not code.

| Part | Built |
|---|---|
| `:protocol` | The event types, with their serialisation and the unknown-type fallback, the request and response bodies of the bridge's routes, and the messages of the terminal socket |
| `:client` | The repository interfaces and screen-state types the app's screens use, the reducer that folds events into screen state, and the sync loop that resumes each session's socket from the last cached `seq` |
| Bridge | The SQLite event log, the session manager, the ACP adapter, `WS /events` with replay, the session, summary and agent routes, a flag-gated dev page, and per-device tokens with revocation, and host and origin checks. It binds to `127.0.0.1` by default. Behind a gate that is off by default, it also lists allowed tmux sessions and mirrors one to the phone over `WS /terminal`. |
| Android app | Pairing with your server address and a one-time code (the device token it receives is encrypted with a Keystore key), an offline event cache in Room, the session list, the session view with thread tabs, tool cards and approvals, and a composer with quick replies and on-device voice input. Half-folded, the session view and the list split at the hinge. |

Everything else on this page, including the file and git routes, per-device tokens, the auth relay and push, is planned. Milestones are tracked on the [GitHub milestones page](https://github.com/tatawurAI/rafiq/milestones).

## Principles

- **Agent-agnostic.** The app knows only generic sessions, threads and events. Agent-specific logic lives in adapters on the bridge.
- **Chat first, terminal when you need it.** The app shows messages, tool-call cards, diffs and approvals for every session. For a session in tmux that you have allowed, it also offers a terminal view of the real pane, as the ground truth and the fallback. It is off until you turn it on (see [Terminal](#terminal)). It is not a general terminal client. See [ADR 0013](adr/0013-agents-in-tmux.md).
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
| `GET /terminal/history?session=&window=&lines=` | Read-only scrollback of a pane of an allowed tmux session. Exists only when Terminal is on. See [Terminal](#terminal). |
| `POST /sessions` | Start a session (agent, project) |
| `GET /sessions` | List sessions. With Terminal on, this includes the allowed tmux sessions. |
| `GET /sessions/summaries` | List sessions with what the session list shows: the newest event `seq`, a one-line preview of the newest agent or user message on the main thread, and the approvals still open, oldest first. Read from small summary tables kept with the log, so polling it is cheap. |
| `GET /agents` | The agents you can start a session with, as `{"id": ...}` objects |
| `POST /pair` | Trade a pairing code for this phone's device token. Needs no token, because it is where a phone gets one. Host and origin checks still apply. A wrong, expired or used code is 401 `invalid_pairing_code`. After 100 wrong codes in five minutes, every attempt is 429 `too_many_attempts` with `Retry-After`. |
| `DELETE /devices/me` | Revoke the calling device and close its open sockets. Answers 204. Used when you unpair in the app. It cannot affect another device. |
| `GET /sessions/{id}/threads` | List a session's threads |
| `POST /sessions/{id}/prompt` | Send a prompt. Prompts go to the main thread, and a helper thread is refused. For a tmux session the send guard decides first: a refusal is `409` with a `SendRefusal` code, and what happens next comes as `prompt_delivery` events matched by `client_id`. See [Chat for tmux sessions](#chat-for-tmux-sessions). |
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
| `RAFIQ_DATA_DIR` | Folder for the database and logs. Unset keeps events in memory only. Required for pairing. | unset |
| `RAFIQ_AGENTS` | Agents as `id=command` pairs separated by commas | unset |
| `RAFIQ_DEV_PAGE` | `1` serves a plain test page at `/dev` | unset |
| `RAFIQ_DEVICE_TOKEN` | Optional. Creates the device `legacy`, so a phone paired before per-device tokens keeps working. At least 32 characters when set. Treat as sensitive. New phones pair with a code. | unset |
| `RAFIQ_ALLOWED_HOSTS` | Extra `Host` values to accept, for example your Tailscale name | unset |
| `RAFIQ_TMUX` | `1` lists tmux sessions, without Terminal. See [Terminal](#terminal). | unset (off) |
| `RAFIQ_TERMINAL` | `1` lists tmux sessions and lets the phone open them in Terminal. | unset (off) |
| `RAFIQ_TMUX_SESSIONS` | The exact names of the tmux sessions the phone may see, separated by commas. No wildcard. | unset |
| `RAFIQ_TERMINAL_SHELLS` | Listed sessions that open in Terminal even with no agent detected, separated by commas. No wildcard. | unset |
| `RAFIQ_TMUX_SOCKET` | The tmux socket name (`tmux -L`), to use another tmux server than the default | unset (default server) |
| `RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK` | `1` lets the bridge bind to a non-loopback address while tmux sessions are on | unset |
| `RAFIQ_TAILSCALE_LOGIN` | Optional. The Tailscale login every request must come from, checked against the header `tailscale serve` adds. Needs a loopback bind address. See [setup.md](setup.md#check-your-tailscale-login-too-optional). | unset |

### Terminal

Terminal shows the real screen of a tmux session on your server in the app, and sends your keys to it. It is a remote shell, so it is off until you turn it on, and it reaches only the sessions you name. [setup.md](setup.md#terminal) shows how to turn it on.

**The gate.** With both `RAFIQ_TMUX` and `RAFIQ_TERMINAL` unset, the bridge lists no tmux session, `WS /terminal` closes with "no such session" and no tmux command runs. `RAFIQ_TMUX=1` lists the named sessions for Chat. `RAFIQ_TERMINAL=1` lists them too and lets the phone open them in Terminal. A session opens in Terminal only when `RAFIQ_TERMINAL` is on and an agent was detected in it, or its name is in `RAFIQ_TERMINAL_SHELLS`. Otherwise `WS /terminal` closes with "no such session" before any tmux command, and the session carries `terminal_allowed` false so the app hides its Terminal toggle. With either switch on:

- `RAFIQ_TMUX_SESSIONS` must name the sessions. Names are matched exactly and may use only letters, digits, `.`, `_` and `-`. There is no `*`. A bad entry fails the start and names the variable. The same holds for `RAFIQ_TERMINAL_SHELLS`, and each of its names must also be in `RAFIQ_TMUX_SESSIONS`.
- A grant covers every window and pane of the session. The app mirrors the active pane of one window, and you can pick another window of the same session. `GET /terminal/history` reaches only a session Terminal may open, by the same rule as `WS /terminal`, and answers `404 session_not_found` for anything else.
- The bridge refuses to start bound to a non-loopback address, unless `RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK=1`. Publish it with `tailscale serve`, never `tailscale funnel`.
- At most 2 terminal sockets per device and 4 in all. Columns are clamped to 20 to 500 and rows to 5 to 200 on every resize, the first one included. A frame of keys may be at most 16 KiB, and a socket may send at most 64 KiB of keys per second. A socket is closed and detached after 10 minutes in which the phone sent no frame and the pane sent no output. Size changes are limited to 5 a second with a burst of 10, and a repeat of the current size is free. Text frames count toward the same 64 KiB per second input budget as keys. A terminal socket is closed after 8 hours whatever it is doing, so a forgotten phone does not stay attached. The app reconnects only if Terminal is on screen. A device may open at most 5 terminals a minute. Past that, the bridge answers "too many terminal opens, wait a moment". Fast size changes, such as a keyboard or fold animation, are merged into the newest size. More than 60 size changes in 10 seconds end the terminal.
- Every `/events` and `/terminal` socket is registered under the device that opened it, so revoking a device closes its sockets at once. A closed terminal detaches its view like any other close.
- The bridge logs one line when a terminal socket opens and one when it closes, with the device, session, address, duration, bytes each way and resize count. It never logs keystrokes.

**tmux.** Terminal needs tmux 3.2 or newer, for `pause-after`. It also needs `window-size latest`, which is tmux's default: the window follows whichever client acted last.

**Tmux sessions in the list.** Each allowed session shows in `GET /sessions` as `tmux-<name>`, with `terminal` true and the agent `claude-code` when a `claude` process runs under one of its panes, otherwise `terminal`. The bridge looks at the pane's process tree, and asks `claude agents --json` when that works. No hook is needed. The list is refreshed every 3 seconds. A session tmux no longer has leaves the list, and one the bridge had marked as working or waiting becomes done, so it never keeps showing "Waiting on you". Until the first refresh has finished, nothing is listed. Sessions named `rafiq-view-...` are the bridge's own and are never listed.

**How the view attaches.** The bridge runs `tmux -C new-session -t <session> -s rafiq-view-<nonce>-<id>` as a plain child process, with no pseudo-terminal. That makes a grouped view session of its own, so picking a window in Terminal never moves the desk's window. The view session is `destroy-unattached`, so it goes away when the bridge or the socket does. When the bridge starts it kills only the `rafiq-view-` sessions that have no client attached, on its own tmux server, so two bridges do not kill each other's live views.

**Size.** The first `resize` from the phone sets the size the view attaches with, so the desk is never resized to a guess. Each size is `refresh-client -C <cols>x<rows>` followed by `switch-client`, which is what makes the phone the client tmux follows. The bridge repeats `switch-client` when the phone sends keys, at most once a second. It never does it on a layout change, or it would fight the desk on every keystroke. It never uses `ignore-size`, `-g` or `resize-window`. Leaving Terminal detaches the view, and the window goes back to the desk's size. If the bridge's last view of the session is leaving and no other client of the group is attached, nothing would take the size back. So the view first sets the size the window had before the first view took it, then detaches.

**Wire.** Open `WS /terminal?session=<id>` with the subprotocol `rafiq.term.v1` next to the token. Down, a binary frame is raw pane output and a text frame is a JSON message from `:protocol`: `snapshot` (size, cursor and terminal modes), `size` and `ended`. A `snapshot` is always followed at once by one binary frame holding the screen. Up, a text `resize` and binary frames of keys. The bridge sends a fresh `snapshot` after every resize, and after any time it had to drop output because the phone was slow. The snapshot and the live stream join with no gap and no repeat, because tmux runs the size query and the screen capture in one command line. The capture is the one reply whose lines a pane controls, so the bridge reads it by the row count from the size query: a line the pane printed is text, even when it looks like the end of a tmux reply or a notification. A reply without one line for each row is never sent as a short screen. The bridge asks for a fresh one instead.

**Windows.** Up, `list_windows` gets `windows`: the session's windows and the index shown. `select_window` switches only the phone's own view session, so the desk's current window never changes, and answers with a fresh `snapshot`, or `notice` with the code `no_such_window`. The shown window takes the phone's size. In tmux every window of a session group follows the client that acted last, so the desk's window gets the desk's size back at its next keystroke, as it does with one window. When the shown window closes, the view moves to the session's current window and sends `notice` with the code `window_closed`, then a `snapshot`. A pane that closes in a split window gives way to that window's active pane.

**Scrollback.** `GET /terminal/history?session=<id>&window=<index>&lines=<n>` answers with `capture-pane -p -e -S -<n>` for the active pane of that window: up to `n` lines of history, capped at 5000, then the screen. Without `window` it reads the session's current window. `alternate_screen` is true when a full-screen program has the alternate screen, which has no tmux history, so the app sends it scroll keys instead. Only `display-message` and `capture-pane` run, so the window, its size and the desk's view never change. tmux copy mode is never used.

**Keys.** The bridge passes on only what a person typing could send: printable text, Enter, Tab, Backspace, Ctrl keys, Escape, Alt plus a key, arrows, Home, End, Insert, Delete, page keys, function keys with their modifiers, the bracketed paste markers, and the mouse wheel in the SGR encoding, which the app uses to scroll a full-screen program in mouse mode. Everything else is dropped, including the replies a terminal emulator makes to the program's questions. tmux already answers those, so a reply that reached the pane would be read as typing.

### Hook channel

Chat for tmux sessions gets its events from the agent's hooks ([ADR 0013](adr/0013-agents-in-tmux.md)). The hook program, `hooks/rafiq-hook.py` in the installed bridge, sends each call to a Unix socket at `$RAFIQ_DATA_DIR/hook/hook.sock`. It never uses the network port, and the main listener has no hook route and no exemption for one.

- **Who can connect.** The `hook` folder is created with mode 0700 and checked like the admin folder. The socket is 0600. Where the JDK reports the peer's user (Linux and macOS), any other user is refused. Each request carries the credential from `hook/credential` (0600), 256 random bits written fresh at every start and compared in constant time. It is never logged.
- **Wire.** One JSON line in, of at most 1 MiB, then for a permission request at most one JSON line back, of at most 64 KiB. A call that fails any check is closed with no reply. The wire types are in `bridge/.../hook/HookContract.kt`.
- **Permission requests.** The connection stays open while the reader waits for your phone. If the hook program hangs up, because the desk answered first, the wait is cancelled at once.
- **Fail closed.** On any error, timeout, missing bridge, malformed or oversized reply, or unknown decision, the hook program prints nothing and exits 0, so Claude Code asks at the desk. It builds Claude Code's decision itself from a checked reply, and a session rule may only cover the tool that asked.
- **The channel runs** only with Terminal on and `RAFIQ_DATA_DIR` set. tmux commands the bridge runs get no `RAFIQ_` variable, so panes do not inherit the device token.

`GET`, `POST` and `DELETE /sessions/{id}/hooks` preview, install and remove the hooks in the session project's `.claude/settings.local.json`. Install and remove need the hash of a preview, which covers the proposed text and the file as it is, so a stale preview gets 409 `hooks_file_changed`. Handlers are tagged by the hook program's path. Observers are async and the `PermissionRequest` handler has a 900 second timeout. The install adds `Read` and `Edit` deny rules for the data folder. Remove takes out only entries that are exactly what install wrote, and is refused otherwise. The bridge refuses a project at the root or in your home folder, and a `.claude` folder or settings file that is a link, so it can never write the user-level settings. Consent is recorded in `rafiq.db` with the file path and a hash of the text written.

**Audit log.** Each Chat prompt and each approval answer from a phone writes one line to the bridge's log, with the device and session, next to the Terminal open and close lines. A prompt is logged as the SHA-256 of its text, never the text.

### Devices and tokens

Each phone is a device with its own token. The bridge keeps devices and one-time pairing codes in `rafiq.db`, as SHA-256 digests. It never stores a token or a code. Tokens and codes are random, so a plain digest is enough, and the fetched digest is compared in constant time.

- **Pairing.** `rafiq-bridge pair --name <device>` makes a code of 8 characters, valid for 10 minutes and spent by the first `POST /pair` that uses it. The device and the spend happen in one transaction, so two requests with one code make one device. Wrong codes are counted across all callers, because behind `tailscale serve` every caller is `127.0.0.1`. This lockout is a trade-off: a stranger who can reach `/pair` can keep every code refused. Running `rafiq-bridge pair` clears the count, because the owner at the CLI has just shown they are present.
- **Checking.** The security plugin looks the token up in the device store on every request and every socket handshake. Nothing is cached, so a revocation applies to the next call. It sets `DevicePrincipal` on the call, and routes read the device id from it. A token of a revoked device gets 401 `device_revoked`. A wrong one gets 401 `unauthorized`.
- **Revoking.** `rafiq-bridge revoke <id>` (or `DELETE /devices/me`) marks the device revoked, then closes its sockets through the `ConnectionRegistry`. Every `/events` and `/terminal` socket registers there. A socket that registers just after a revoke is closed at once, so a handshake racing the revoke cannot stay open. A revoked device stays in the table, so the app can tell "removed" from "wrong token".
- **The CLI.** `rafiq-bridge pair --name <device>`, `rafiq-bridge devices` and `rafiq-bridge revoke <id>` talk to the running bridge over a Unix socket at `$RAFIQ_DATA_DIR/admin/ctl.sock`, never over the network port. The folder is created with mode 0700 and the bridge refuses to start if it is a symbolic link, belongs to another user or has any other mode. The socket file is 0600. Java has no portable peer-credential check, so that folder is the access control, as it is for the database beside it.
- **The legacy token.** `RAFIQ_DEVICE_TOKEN` becomes the device `legacy` at start, so a phone paired before per-device tokens keeps working. Revoking `legacy` is permanent, even if the variable is still set.

This does not cover anyone with a shell as the bridge's user. See [SECURITY.md](../SECURITY.md) and ADR 0012.

### Auth relay

Agents sometimes need you to sign in to a provider. The bridge handles two kinds of flow:

- **Device code.** The bridge detects the code and URL in the agent's output and emits an Auth required event. You enter the code in a browser.
- **Localhost redirect.** The app opens the login page in an in-app browser. When the provider redirects to `http://localhost:PORT/...`, the app intercepts that final redirect and posts it to the bridge, which requests it locally so the agent receives it.

### Previews

The bridge watches for new listening ports under a session's processes. When one appears, it emits a Preview event with the Tailscale URL.

### Storage and security

- SQLite holds sessions, threads and the event log, plus two small summary tables (the newest message preview and the open approvals per session) written in the same transaction as each event.
- Tailscale ACLs restrict the bridge's port to your devices. Each phone has a device token of its own, checked against the device store on every request and every socket handshake. The `Host` header must be the bind address, `localhost` or a listed name (421 otherwise), and a request with a foreign `Origin` is refused, so a web page cannot reach the bridge through DNS rebinding. See [Devices and tokens](#devices-and-tokens) for pairing and revoking. See [ADR 0012](adr/0012-https-through-tailscale-serve.md), and [setup.md](setup.md) for the `tailscale serve` steps.
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

## Chat for tmux sessions

The Claude reader (`bridge/.../tmux/reader/`) gives a tmux session running Claude Code a real Chat, as [ADR 0013](adr/0013-agents-in-tmux.md) decides. It observes the agent and writes the same events an ACP session writes, so the app cannot tell the two apart.

**Input.** Each hook call arrives on the hook channel with the pane id, the agent's process id and the tmux socket from `$TMUX`. The reader routes it to the allowed tmux session that owns the pane. It drops a call from another tmux server, from a pane not in an allowed session, and from an agent process that does not run inside the pane, since pane ids repeat across servers and are reused once a pane closes. A record path from a hook is read only when it resolves inside Claude Code's folder of records and is a regular file, never through a link. Every Claude pane in a session is followed: the first one found is the main thread, and each other one gets a thread of its own. The tmux source's scan finds Claude panes that sent no hook yet, and rebuilds the mapping after a restart from `tmux list-panes`, the process tree and `claude agents --json`.

**Mapping.** `UserPromptSubmit` starts a turn, `PreToolUse` and `PostToolUse` or `PostToolUseFailure` give tool cards, `PostToolBatch` gives the steps that were refused, and `Stop` ends the turn. Replies come from `MessageDisplay` batches, as Markdown. Hooks run side by side, so a step's card waits up to 250 ms and text written before the step shows first. A subagent's reply comes from `SubagentStop`. Progress goes out as `turn_progress`. An Esc at the desk sends no `Stop`, so the agent's own status (`busy`, `waiting`, `idle`) ends that turn, and also shows a dialog at the desk that no phone approval covers.

**The record and the drift check.** One second after `Stop`, the reader checks that the reply it showed is the agent's own final text. If batches were missed, it fills them from the agent's session record, and failing that from the `Stop` event, with a `chat_incomplete` note. The record is read only from a saved byte offset: after a bridge restart it fills exactly the gap, and on first attach it starts near the end. The offsets are kept in `reader/record-offsets.json` in the data directory. A recorded session (`bridge/src/test/resources/claude-reader/`, placeholders only) is replayed through the reader by a test, which breaks when a Claude Code release changes the shapes the reader relies on.

**Approvals.** A `PermissionRequest` is held while a phone may answer it, and matched to its step by tool name and input. It settles exactly once: a phone answer, the desk answering first (the step ran, or the turn moved on, so it is withdrawn), the 600 s deadline (the installed hook waits 900 s), the hook disconnecting, or the revocation of any paired device (the last three go back to the desk). At most 3 per session and 10 in all wait for a phone. A question from `AskUserQuestion` with one part and one choice is answered with the chosen label. The card carries the full command, its line count, the settings that change what it does (`dangerouslyDisableSandbox`, `run_in_background`, `timeout`) and the rule "allow for this session" adds. For a command that rule is the exact command, `Bash(<command>)`. Claude Code's own suggestion is not used, since it can be a prefix rule that allows more than you saw, and a command with `*`, several lines or a `)` gets no rule. Any other tool gets a rule for every call of that tool, and the option's label says so. The plain description an older app shows ends with the line count or "(cut)".

**The send guard.** The bridge types only when the agent is idle, no dialog is open, and the input box is empty. The input box, read with `capture-pane`, is the only pane text the reader reads. Control characters other than tab and newline are removed. The text goes in through a uniquely named paste buffer as a bracketed paste, and Enter is a separate key. Right before Enter the bridge checks again that the agent is idle and still runs in the pane, that no phone approval for the pane is open, and that the input box holds the pasted text, so a dialog that opened meanwhile never takes the Enter. A waiting prompt belongs to the phone that sent it: revoking the phone drops it, and after 10 minutes it is given up. A refused prompt is in the audit log with its code and the hash of its text. Delivery is confirmed by `UserPromptSubmit`, or for a `!` line or a slash command by the input box clearing. A prompt sent while the agent is busy waits in the bridge, as `queued`, and is typed when the turn ends. Every prompt that reaches the agent, from the phone or the desk, becomes a `message` from you with the id `user:<agent session id>:<prompt id>`. The hook and the record carry the same prompt id, so a backfill never shows a prompt twice. A `!` line or a local command has no prompt id, and its message has the id `user:<client id>`. The message is in the log before the `delivered` event. Revoking any device, including one paired after an approval was raised, sends waiting approvals back to the desk.
