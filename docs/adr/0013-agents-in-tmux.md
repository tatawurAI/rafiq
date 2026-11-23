# 0013. Agents in tmux

## Status

Proposed, 2026-11-22. Amends the "Chat, not terminal" principle and the terminal emulator non-goal in [docs/architecture.md](../architecture.md), and corrects one consequence line of [ADR 0012](0012-https-through-tailscale-serve.md) (see "Security guards"). It does not supersede either ADR.

## Context

Today the app shows only sessions the bridge starts itself, over ACP (see [ADR 0004](0004-the-app-knows-only-generic-sessions-threads-and-events.md)). Many people run Claude Code and Codex natively in tmux on their server, at a desk, and want to carry on the same session from their phone and switch back at any moment. Nothing about the desk workflow may change.

A spike ([issue 36](https://github.com/tatawurAI/rafiq/issues/36)) tried this on a real phone against a real tmux session running Claude Code, and answered the open questions. The spike's written findings are in that issue. This ADR records the decision they support.

Constraints the decision must meet:

- Agents keep running natively in tmux. Nobody starts them differently.
- Every agent reply shows on the phone, with tool steps and progress. Word-by-word streaming is not required.
- Approvals can be answered from the phone while the agent waits.
- A message sent from the phone appears in the terminal at the desk, and behaves exactly as if it was typed there.
- Every tmux session also has a real terminal view, which is the ground truth when chat looks wrong.
- Typing into a tmux pane is typing into a shell, so this feature needs guards.

## Decision

### Sessions

The bridge lists the tmux sessions on your server next to the sessions it starts itself. A tmux-backed session uses the existing routes and the existing event log. Its id is `tmux-<session name>`. Its `agent` is `claude-code` when a Claude Code pane is detected, and `terminal` otherwise. `Session` gains one additive field, `terminal` (default `false`), which is true for every tmux-backed session. The app uses it to show the Chat | Terminal toggle. The app still knows nothing about any agent.

A tmux session whose tmux session no longer exists is shown as ended, or hidden. It never stays in the list as waiting.

### The reader model

An ACP adapter starts an agent and talks to it. A reader does the other thing: it observes an agent that something else started, and it turns what it sees into the same events. The app cannot tell the two apart.

A reader has four parts.

**Hooks and the session record.** The agent's own hooks tell the bridge what happens, as it happens. A small hook program (`rafiq-hook`) sends each event as JSON, with the tmux pane id and the agent's process id, to the bridge over a local channel that is not reachable from the network. The agent's session record, a file the agent writes, is read only to fill gaps.

For Claude Code:

- Replies come from the `MessageDisplay` hook, which delivers every assistant message, including text between tool calls, in whole-line batches. `Stop.last_assistant_message` is used to check the joined text at the end of a turn. Subagent text comes from `SubagentStop`.
- The session record lags every hook by 40 to 350 ms, so it is not the live path. The bridge reads it from a saved byte offset to backfill after an outage or when a pane is first attached, and when a turn ended with batches missing.
- Tool steps come from `PreToolUse`, `PostToolUse`, `PostToolUseFailure` and `PostToolBatch`. Status comes from the agent's own session status (`busy`, `waiting`, `idle`), with the hooks for detail. Pane activity is not used for progress: it gives false alarms while the agent waits for you.
- Agent text is emitted as Markdown content, as the ACP adapter does.

For Codex, the same shape applies, from documentation and source only. Replies come from the session rollout file, which the `SessionStart` hook points to. Approvals come from the `PermissionRequest` hook. The Codex reader starts with a one-time check on a machine that has Codex.

**Pane to session mapping.** The pane id from the hook environment is the key. The bridge keeps the latest session id, process id and record path against it, and keys chat history by session, never by pane alone. After a restart it rebuilds the mapping from `tmux list-panes`, the process tree and the agent's own session list. A session that started before the hooks were installed appears after its first hook event.

**Input.** A message from the phone is typed into the pane, so it also appears at the desk. It behaves exactly as if it was typed at the desk. A leading `!` runs shell mode, and slash commands and skills run, with no extra limit, allow-list or confirmation from Rafiq. That is by design. The send guard runs in the bridge, not only in the app. It protects against accidents, not against you, and decides whether the bridge may type at all:

- The agent's status must be `idle`. If a turn is running, the message is queued by the agent and shown as queued. While a permission dialog is open, the bridge never types message keys, because an Enter would answer the dialog.
- The input box must be empty. The bridge reads the box from the pane with `capture-pane`. This is the only use of pane text in the reader. If the desk has a half-written line, the bridge refuses and says so.
- Control characters other than tab and newline are stripped, because tmux would treat them as key presses. The bridge types the text through a tmux paste buffer (bracketed paste), so anything that slips through is inert, then presses `Enter` once. The agent shows long or pasted text as pasted content, which is accepted.
- Right before the final `Enter`, the bridge checks the status again, and that the pane still runs the agent. If the agent exited in between, the text would otherwise run in a shell.
- After sending, the bridge checks that the message landed (for Claude Code, a `UserPromptSubmit` arrived, or for `!` and local slash commands, the input box cleared). If it did not, the app shows the message as not delivered.

**Approvals.** The agent's `PermissionRequest` hook holds the request and long-polls the bridge. The bridge raises the usual approval event. The desk dialog is shown at the same time, and the first answer wins. The bridge treats "the tool ran" as the truth: if the desk answered first, it resolves the approval as withdrawn. The hook must exit by itself when the request settles, because the agent does not kill a hook that the desk overrode. Answers are keyed by request. Allow-always adds a rule scoped to the session, and never touches the user's settings file. A question from the agent is answered with the chosen labels through the same hook.

The hook fails closed to the desk. Any error, timeout, bridge outage or malformed answer makes the hook print nothing and exit 0, so the agent shows its own dialog. The hook builds the decision itself from a validated `allow` or `deny` field. It never prints the bridge's raw reply. A hook error never approves. The approval card shows the full command, with a line count and a marker when it was cut, and shows the rule that "allow for this session" adds. Pending approvals are capped, and each has a deadline shorter than the hook's own timeout, after which it settles as "ask at the desk".

### Installing the hooks

Hooks go into the project's own local settings file, per project, and only after you see the exact JSON, the target file and the list of events, and tap to agree. The bridge records the consent with the file path and a hash of what it wrote. One tap removes exactly the entries it added and nothing else, and never switches hooks off globally. The bridge never edits the user-level settings file. The agent's own workspace trust dialog stays a separate, first-run step that you answer yourself. The installer also adds read-deny rules for Rafiq's own credential files (see "Security guards").

### The terminal view

Every tmux session has a Chat | Terminal toggle in the session header. Terminal is the real pane. Chat never changes the window size.

**Attach.** The bridge attaches as a tmux control-mode client (`tmux -C`) through its own grouped session named `rafiq-view-<id>`. The grouped session has its own current window, so the desk's view never moves. The bridge sets `destroy-unattached on` after attaching, and kills leftover `rafiq-view-` sessions when it starts.

**Size.** `window-size latest` is set globally, so the phone drives the size while it is the latest client to act. A control client wins the size only after it sends a size, and it wins it back after the desk types only with `switch-client`. So on entry and on every phone resize the bridge sends `refresh-client -C <cols>x<rows>` and then `switch-client`. On phone input it sends `switch-client` again, at most once a second. It never reclaims on a layout change, or the bridge and the desk would fight over every desk keystroke. There is no `ignore-size`, no zoom and no pan. The phone follows the real pane size the bridge reports, which differs from its own viewport whenever the desk takes the size back.

**Hand-back on leave.** Leaving Terminal, by the toggle, back, the cover screen, the app going to the background or a dropped socket, closes the stream. The bridge detaches its tmux client, so the desk gets its size back within the 0.4 s tmux polling interval. If the bridge itself dies, tmux removes the view session when the client's input ends.

**No desk attached.** If no other client is attached, nothing takes the size back, and the window stays at the phone's size after you leave. This is how tmux behaves with `window-size latest`. Since milestone 7 Wave 2, the bridge gives the session back the size it had before the phone took it when the last view client leaves and no other client is attached.

**Scrollback.** Control mode shows the live screen, and tmux copy mode on the shared pane would also show at the desk, so it is not used. The bridge serves read-only history from `capture-pane -S -<n> -e` for the pane. The phone shows it as an overlay above the live screen. The overlay never changes the window size or the desk's view. A swipe up opens it, and new output or typing returns to live. For a full-screen program on the alternate screen, which has no tmux history, a swipe sends scroll keys to the program instead: Page Up, or the mouse wheel when the program has turned on mouse mode.

**Wire format.** One WebSocket per terminal view, `WS /terminal?session=<id>`, separate from the event socket and authenticated the same way (bearer header, or the token as a `Sec-WebSocket-Protocol` value next to `rafiq.term.v1`).

- Down, binary frame: raw pane bytes, in order.
- Down, text frame: `snapshot` (`cols`, `rows`, `cursor_x`, `cursor_y`), immediately followed by one binary frame with the screen bytes. Then `size` when the pane size changes, and `ended`.
- Up, text frame: `resize`, debounced by the app. Up, binary frame: key and paste bytes.
- Closing the socket detaches.

The bridge uses tmux's `pause-after` for flow control. When a client falls behind, tmux pauses output, and the bridge continues it and sends a fresh snapshot. This needs tmux 3.2 or newer.

Control mode means tmux is the terminal and already answers the pane program's queries. The app therefore sends only user keys and paste up, never the emulator's automatic replies. The bridge drops anything that is not a known key encoding as a backstop.

**Emulator.** The app uses `org.connectbot:termlib` (Apache-2.0, with libvterm under MIT). It is a native Compose composable that takes bytes in and gives key bytes out. The view has a key row (Esc, Tab, Ctrl and Alt as sticky keys, arrows, Paste), and half-folded the terminal sits above the hinge with the keys and keyboard below. The cover screen stays on Chat. The pinch magnifier is blocked and there is no zoom. A window with split panes shows the agent's pane only. Pane bytes are untrusted, so the emulator is hardened (see "Security guards"). Releases of termlib after 0.2.0 need compileSdk 37 (targetSdk stays 36), so the milestone moves the build to compileSdk 37 and the matching build image. Version 0.2.0 was used for the prototype only.

### What this amends in docs/architecture.md

The principle "**Chat, not terminal.** The app shows messages, tool-call cards, diffs and approvals. It is not a terminal emulator." becomes:

> **Chat first, terminal when you need it.** The app shows messages, tool-call cards, diffs and approvals for every session. For a session in tmux that you have allowed, it also offers a terminal view of the real pane, as the ground truth and the fallback. It is not a general terminal client.

The v1 non-goal "a terminal emulator (SSH stays the out-of-band admin path), mosh or any terminal-state protocol" becomes:

> a general terminal client (SSH stays the out-of-band admin path), mosh or any terminal-state protocol beyond tmux's own control mode

The overview and links tables also gain the bridge-to-tmux link. The first build updates that page.

### No exception to ADR 0007

termlib is native Compose, so [ADR 0007](0007-native-compose-for-fold-and-cover-screen-apis.md) holds as written. An embedded web view would have needed an exception. It is not used.

### Security guards

Typing into a tmux pane is typing into your shell. Terminal is a remote shell on your server. Chat is one too, by design, because a message that starts with `!` runs a shell command in Claude Code with no permission prompt, exactly as at the desk. The guards below reduce who can do this and how easily. They do not limit what you can do once you allow a session.

**The trust boundary.** Tailnet membership, then the device token, then an optional biometric check at the door of Terminal and of approvals. ADR 0012 says anyone with access to the server itself is outside the model. That stays true for other people. It is not true for the agent: a prompt-injected agent is on the server by design, runs as the same user as the bridge, and can read the bridge's credential files. So the agent is an actor that is not trusted with Rafiq's own secrets, and this ADR corrects that line of ADR 0012 to say so. The phone approval is a convenience for a person at the desk's dialog, not a sandbox: hooks shipped in a repo, and other tools' hooks, can allow tools too. How several permission hooks combine is Claude Code's rule for Claude Code, and Codex's rule (a deny wins) for Codex. Neither is Rafiq's.

**Must have.** The feature does not reach a user without these.

1. Tailnet only. The bridge stays on `127.0.0.1` behind `tailscale serve`. Never `tailscale funnel`.
2. Off by default. Terminal and tmux Chat do nothing until the owner of the server turns them on in the bridge's own configuration. No tmux command runs while it is off.
3. A session allow-list, by name only. Names are validated against `[A-Za-z0-9._-]+` and matched exactly. There is no `*` for Terminal. A grant covers every window and pane in the session, and the app shows which window is mirrored.
4. The device token on every route and on `WS /terminal`. Per-device tokens with revocation are part of this milestone, and Terminal does not reach users before they land. Revoking or rotating a token closes its sockets and releases its pending approvals.
5. The hook channel is local only and cannot be taken over. It is a Unix socket in a directory that only the bridge's user can enter (peer credentials checked), or a second loopback listener that is never behind `tailscale serve`. The main app has no auth exemption for hook routes.
6. Permission hooks fail to "ask at the desk", never to "allow" (see Approvals).
7. Rafiq's secrets are harder for the agent to read. The hook installer adds read-deny rules for the token file and the bridge's data directory, which have owner-only modes. A same-user agent that is allowed to read them can still approve its own requests. Running the bridge as a different OS user is the complete fix and is a documented hardening option, not a requirement.
8. The send guard in the bridge (see Input).
9. A biometric check (strong, no device-credential fallback, short validity window), as a setting, guards opening Terminal and answering approvals. It sits at the door, not on each keystroke or message. The window ends when the phone leaves Terminal or the app goes to the background.
10. Emulator hardening. Clipboard writes from the pane (OSC 52) are ignored. Links open only after a confirmation that shows the real target, and only for `https`. The window title is ignored, inline images are off, and the bell causes at most one haptic.
11. An audit log, always on for Terminal: open and close with device id, session, remote address, duration, bytes up and down and resize count. Every Chat prompt and every approval answer is logged with the device id and a hash of the text. Keystroke content is not logged, because people type passwords into panes. Chat prompts and tool output are stored in the event log and cached on the phone, and the docs say so.
12. Limits on the terminal socket: at most 2 per device and 4 in total, rows and columns clamped to a sane range on every resize path, input rate and size limits, and an idle timeout that detaches.
13. The approval card shows the full command, with a line count and a marker where it was cut, and the rule behind "allow for this session".

**Should have.** Ship in the milestone if possible, otherwise record as a known gap: separate switches for Terminal and for Chat, with Terminal refused for plain shells unless listed in a second list; a per-start hook credential; bounded pending approvals; an allow-list of key encodings for upstream bytes; `FLAG_SECURE` on Terminal; and an optional check of the Tailscale identity header that `tailscale serve` adds.

**Considered and not adopted.** Refusing a leading `!` from Chat, passing only an allow-list of slash commands, or asking for a confirmation or a biometric check before such a send. We chose against it: Chat must behave exactly like the desk, including skills and shell mode, and the real wall is the tailnet, the device token, revocation and the optional biometric at the door.

### Out of scope

- Word-by-word streaming of replies.
- Cancelling a turn from Chat. Press Esc in Terminal.
- Links, sign-in cards and previews for tmux sessions. They stay with the files, auth and previews milestone.
- Starting or resuming an agent in tmux from the phone. The bridge only observes and types into sessions that already run.
- Windows with several panes beyond showing the agent's pane. Nested tmux and screen. Windows servers.
- Agents other than Claude Code and Codex get the terminal view only.
- Multi-user servers, protection against someone with a root shell on the server, and screen recording on the phone.
- A web client. Opening Chat and Terminal from a browser is a separate future decision with its own spike. It is not part of this ADR.

## Consequences

- You can carry on one session from the desk and the phone and switch at any moment. Chat gives replies, steps, progress and approvals. Terminal gives the real pane when chat looks wrong.
- Chat depends on the agents' hooks and session records. They are not stable contracts, and a release of the agent can change them without notice (see the next section).
- You must install hooks per project, with consent, and for Codex trust the hook once. Sessions in a project without hooks still list and open in Terminal, but show no chat.
- A hook that waits for the phone delays nothing on the desk for Claude Code, because both dialogs are live. For Codex the desk sees no prompt while its hook waits, so the Codex hook waits only if a phone is attached and gives up after a short time.
- Approvals from the phone are rare for people who run the agent in auto mode, because the agent rarely asks.
- Control mode builds the first screen from a snapshot, and the snapshot does not carry every bit of state (scroll regions, saved cursor, character sets). It rendered correctly in the demo. A PTY client is the fallback if a real case shows otherwise.
- The phone owns the window size while Terminal is open. A desk user who is typing takes it back at once, and the phone catches up on its next input.
- The feature adds a native library (about 0.9 MB per ABI), a move to compileSdk 37, and a minimum of tmux 3.2 on your server.
- The feature adds a biometric setting, an audit log, a hook channel and socket limits. That is real work, and it is part of the milestone, not a later phase.
- `Session.terminal` is one additive protocol field. Nothing is renamed or removed, so no protocol ADR is needed.

### Risk: records and hook payloads are not stable contracts

Claude Code's session record, its undocumented session registry, and Codex's rollout file can change shape without notice, and the `MessageDisplay` hook is new. A silent change would leave chat incomplete. We handle it three ways.

- Parsers are tolerant: unknown line types and unknown fields are ignored, and the agent version is recorded with each session.
- A drift canary runs against the newest agent release. For Claude Code it checks, on every `Stop`, that the joined `MessageDisplay` text matches the record's last assistant text, and a scheduled job replays a recorded sample session (placeholders only, no real data) through the parsers and fails on a changed shape. For Codex it also checks the generated hook schemas in the upstream repository. The docs state the tested version range.
- Chat fails soft. If the bridge sees a turn end with missing text, or no hook event for a pane that runs an agent, the app says chat may be incomplete and offers Terminal, which does not depend on any of this.

## Alternatives considered

**A real client in a pseudo-terminal (PTY).** The bridge would attach as an ordinary tmux client in a PTY. This also works and was tested: it wins the window size by attaching and resizing, and tmux itself composes the first screen, so the app gets a faithful repaint. We rejected it as the main path for three reasons. The JVM cannot allocate a PTY without a native library. The one that fits is under the Eclipse Public License 1.0, a weak copyleft licence that needs a notice and a deliberate decision in an Apache-2.0 project. And it streams tmux's own rendering, including the status line, and the whole window with all its panes, where the phone wants one pane. Control mode needs only `ProcessBuilder`, is lossless, and cleans itself up on every kind of exit. Its one weakness, not winning the size by activity, has a two-command fix. The PTY client stays as the fallback if control-mode snapshots render wrong on the phone.

**xterm.js in a web view.** It is MIT licensed and renders well on a desktop. We rejected it because Android keyboard input is its weak spot: open issues in 2026 (dropped input, missing deletes, erratic typing) sit on exactly the path the terminal view lives on. It also needs a JavaScript bridge and bundled assets, and it would need an exception to ADR 0007.

**Termux's terminal modules.** Apache-2.0 modules inside a GPLv3 repository. Its session class is final and tied to a local process, so we would vendor and change two modules, and its pinch zoom resizes the grid, which breaks "the phone drives the size". It is the fallback emulator if termlib fails on a real phone.
