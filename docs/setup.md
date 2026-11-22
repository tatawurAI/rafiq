# Set up the bridge for your phone

Your phone reaches the bridge only over HTTPS, through `tailscale serve` on your server ([ADR 0012](adr/0012-https-through-tailscale-serve.md)). The bridge itself stays on `127.0.0.1`. Tailscale gives your server a real certificate for its `*.ts.net` name and forwards HTTPS traffic from your tailnet to the bridge's local port. The app has no cleartext exception, so this is the only way it connects.

The examples use `box.example.ts.net` for your server's name. Use your own.

## What you need

- Tailscale on your server, signed in to the same tailnet as your phone. The commands here were checked against Tailscale 1.98. Older versions used a different `tailscale serve` syntax, so update first if a command below is refused.
- Tailscale on your phone, connected.
- The bridge built and configured as in the [README](../README.md#quickstart), with `RAFIQ_DATA_DIR` set, because pairing needs it.
- To build the app: it compiles against Android SDK Platform 37 (`platforms;android-37.0`) with Build-Tools 37.0.0, and still targets API 36.
- `claude-agent-acp` installed on your server, as the [README](../README.md#requirements) shows, and Claude Code signed in there.

## 1. Turn on HTTPS certificates for your tailnet

In the Tailscale admin console, open the DNS page.

1. Turn on MagicDNS, if it is not on already.
2. Under HTTPS Certificates, select Enable HTTPS.

You only do this once per tailnet. If you skip it, the first `tailscale serve` run offers to turn it on for you.

Certificates are public: your server's `*.ts.net` name is recorded in the public Certificate Transparency logs. Pick a machine name you are happy to publish.

## 2. Find your server's name

```sh
tailscale status --json | jq -r .Self.DNSName
```

This prints a name such as `box.example.ts.net.`. Drop the final dot.

## 3. Run the bridge on 127.0.0.1:8740

Keep the default bind address and port, and add your server's name to `RAFIQ_ALLOWED_HOSTS`:

```sh
export RAFIQ_AGENTS=claude-code=claude-agent-acp
export RAFIQ_DATA_DIR=/var/lib/rafiq
export RAFIQ_ALLOWED_HOSTS=box.example.ts.net
bridge/build/install/rafiq-bridge/bin/rafiq-bridge
```

Do not set `RAFIQ_BIND_ADDRESS` to a Tailscale or LAN address. `tailscale serve` connects to the bridge on `127.0.0.1`.

`RAFIQ_ALLOWED_HOSTS` matters because `tailscale serve` passes on the `Host` header your phone sent. The bridge sees `Host: box.example.ts.net`, with no port because HTTPS uses the default port 443, and answers any host it does not know with `421 Misdirected Request`. A port in the header would also be accepted, since the check compares the name only.

## 4. Publish the bridge with tailscale serve

On your server:

```sh
tailscale serve --bg 8740
```

This serves `https://box.example.ts.net` on port 443 and forwards it to `http://127.0.0.1:8740`. `--bg` keeps it running in the background, and the setting stays in place across restarts until you turn it off. On Linux, run it with `sudo`, or allow your user once with `sudo tailscale set --operator=$USER`.

WebSocket upgrades pass through the proxy, so `wss://box.example.ts.net/events` works as well. The `Origin` and `Sec-WebSocket-Protocol` headers reach the bridge unchanged.

To see what is served, and to stop serving:

```sh
tailscale serve status
tailscale serve --https=443 off
```

Never use `tailscale funnel` for the bridge. Funnel publishes to the whole internet, and the bridge is meant for your tailnet only.

## 5. Check from another device

From any device on your tailnet, such as your laptop:

```sh
curl https://box.example.ts.net/health
curl -H "Authorization: Bearer $DEVICE_TOKEN" https://box.example.ts.net/sessions   # a token from pairing, or the legacy token
```

The first gives `{"status":"ok","version":"..."}`. The first request after you turn on HTTPS can take a few seconds while Tailscale gets the certificate.

## 6. Pair your phone

Run this on your server, in the same environment as the bridge (it needs the same `RAFIQ_DATA_DIR`):

```sh
rafiq-bridge pair --name pixel
```

It prints a pairing code like `ABCD-EFGH` and the address to enter. The code works once and expires after 10 minutes. After 100 wrong codes in five minutes the bridge refuses every code until the window passes, a right one too. Anyone who can reach your server's address could cause that, so running `rafiq-bridge pair` again clears the lockout and gives you a fresh code at once. In the app, enter your server address (your https `*.ts.net` name) and the code. The phone gets its own token and keeps it in the Android Keystore. Nobody, including you, can read it again.

### List and revoke phones

```sh
rafiq-bridge devices
rafiq-bridge revoke <id>
```

`devices` shows each phone's id, name, when it last connected and whether it is active. `revoke` cuts that phone off at once: its open connections close and its next request is refused. The phone clears its token and shows the pairing screen. Revoking is permanent. Pair the phone again for a new token. Unpairing in the app does the same thing from the phone. If your server cannot be reached when you unpair, the app tells you the phone is still listed, and you run `rafiq-bridge revoke <id>` later. These commands talk to the running bridge over a Unix socket in `$RAFIQ_DATA_DIR/admin`, a folder only your user can open. They never use the network port. They are for you: any process that runs as the same user as the bridge can reach this socket and the database in `RAFIQ_DATA_DIR`. The Wave 2 installer adds read-deny rules so an agent run by the bridge cannot.

### Retire the old shared token

If you set `RAFIQ_DEVICE_TOKEN` before this version, that token still works. The bridge lists it as the device `legacy`. Once your phone is paired with its own token, run `rafiq-bridge revoke legacy`, then remove `RAFIQ_DEVICE_TOKEN` from your service environment. The bridge no longer needs it. A revoked `legacy` stays revoked even if the variable is still set, so a leftover value is harmless.

## Terminal

Terminal shows a tmux session on your server in the app, and sends your keys to it. It is a remote shell. It is off until you turn it on, and it reaches only the sessions you name.

You need tmux 3.2 or newer on your server, and the default `window-size latest`.

Turn it on in the bridge's environment:

```sh
export RAFIQ_TERMINAL=1
export RAFIQ_TMUX_SESSIONS=weather-app,docs-site
```

| Setting | Meaning |
|---|---|
| `RAFIQ_TERMINAL` | `1` turns Terminal and tmux sessions on. Anything else, or unset, leaves them off. |
| `RAFIQ_TMUX_SESSIONS` | The exact names of the tmux sessions your phone may see and open. Letters, digits, `.`, `_` and `-` only. No `*`. The bridge will not start with a bad name, and says which variable it is. |
| `RAFIQ_TMUX_SOCKET` | The tmux socket name, if your sessions run on a tmux server started with `tmux -L <name>`. Unset uses the default server. |
| `RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK` | `1` lets the bridge bind to an address other than `127.0.0.1` while Terminal is on. Leave it unset. |

What to know before you turn it on:

- Terminal reaches every window and pane of each session you list. Name only sessions you are happy to type into from your phone.
- With Terminal on, the bridge refuses to start on a non-loopback address. Keep `RAFIQ_BIND_ADDRESS` at its default and use `tailscale serve`, as above. Never use `tailscale funnel`.
- Each phone can have 2 terminals open, and the bridge 4. A terminal closes after 10 minutes with no keys or resize from your phone and no output from the pane. A phone that is watching an agent work stays open. A terminal socket is closed after 8 hours whatever it is doing, so a forgotten phone does not stay attached. The app reconnects only if Terminal is on screen. A device may open at most 5 terminals a minute. Past that, the bridge answers "too many terminal opens, wait a moment". Fast size changes, such as a keyboard or fold animation, are merged into the newest size. More than 60 size changes in 10 seconds end the terminal.
- While you have Terminal open, the window in tmux has the size of your phone. When you leave, it goes back to the size of your terminal at your desk. If nothing is attached at your desk, it goes back to the size it had before you opened Terminal.
- Tap the window label to show another window of the session. Only your phone changes window. Your desk stays where it is, and its window gets your desk's size back at your next keystroke there.
- Scrolling back reads the pane's history from tmux. It never changes what your desk sees.
- Rafiq can ask for your fingerprint or face before Terminal opens. Turn it on in the app's settings. It asks once when Terminal opens, and not for each key.
- The bridge's own view sessions are named `rafiq-view-...`. Do not use that name for your own sessions. When the bridge starts, it ends only the `rafiq-view-` sessions that have no client attached, so two bridges do not close each other's live views.

A listed session shows in your session list with the agent that runs in it. Its row opens in Terminal. A session you did not list is not shown, and cannot be opened, even if its name starts with a listed name.

## Chat for tmux sessions: the hooks

Chat for a tmux session gets the agent's replies, steps and approvals from Claude Code's own hooks. You install them per project, from the app, and only after you have seen exactly what will be written. Rafiq never installs hooks for all projects, and never edits your user-level settings file (`~/.claude/settings.json`).

You need `python3` on your server. The hook program is `hooks/rafiq-hook.py` in the installed bridge, next to `bin` and `lib`, and uses only Python's standard library. Keep the bridge where you installed it: the hooks point at that path. If you move it, remove the hooks from the app and install them again.

To install, open a tmux session in the app and follow the prompt to set up Chat. The app shows:

- The file it will write: `.claude/settings.local.json` in the session's project folder. Claude Code reads that file for this project only, and does not share it.
- The whole file exactly as it will be after the install, including anything already in it.
- The hook events, and what the hook program sends: each event as Claude Code's own JSON, with the tmux pane and the agent's process id, to the bridge over a Unix socket in `RAFIQ_DATA_DIR/hook`. Nothing goes over the network.

Nothing is written until you tap to agree. The bridge then records your agreement with the file's path and a hash of what it wrote. If the project is a git repository, the app offers to add the file to `.git/info/exclude`, so it is not committed by accident.

What to know:

- The first time Claude Code opens a folder, it asks whether you trust it, and says the folder configures hooks. That is Claude Code's own dialog. Answer it at your desk. Rafiq does not answer it for you. In a folder you already trust, the hooks start with the next event, with no restart.
- The install also adds read and edit deny rules for `RAFIQ_DATA_DIR`, where the bridge keeps its database and the hook credential, so the agent's file tools are refused there.
- A permission request waits up to 10 minutes for your phone, while the dialog at your desk stays open. The first answer wins. If the bridge is not running, or anything goes wrong, the hook says nothing and Claude Code asks at your desk as usual. A hook error never approves anything.
- Remove takes out exactly the entries Rafiq added and nothing else. If nothing else is left in the file, the file is deleted. If you edited Rafiq's entries by hand, the bridge refuses to remove them and asks you to do it in the file. It never switches all hooks off.
- Another tool's hooks in the same file keep working. Hooks from other files, and other tools, can still answer a permission request themselves. How several hooks combine is Claude Code's rule.

### The limit of a hook on the same user

The hook channel is a Unix socket in a folder only your user can open, and each call needs a credential that is new every time the bridge starts. Another user on your server cannot reach it. An agent runs as your user, though. One that is allowed to read the bridge's data folder, for example through a shell command, can read the credential and approve its own requests. The deny rules make that harder. They do not make it impossible.

The complete fix is to run the bridge as a different OS user from the one that runs your agents, with `RAFIQ_DATA_DIR` owned by that user, so no agent can read it. This is optional hardening, not a requirement. Today it covers sessions the bridge starts itself. Tmux sessions do not work in that layout yet, because the hook socket accepts only the bridge's own user, and tmux's socket only the user who started tmux.

If you keep `RAFIQ_DEVICE_TOKEN` in a systemd `EnvironmentFile=`, make that file mode 0600. Once your phone is paired with its own token, revoke `legacy` and remove the variable. Do not export any `RAFIQ_` variable in the shell that starts tmux: every new pane would inherit it. The bridge warns at start if the tmux server already holds one, by name only.

### What the bridge logs about your phone

The bridge writes these lines to its log, which goes to standard output (the systemd journal, if you run it as a service):

- Terminal open and close, with the device, session, address, duration, bytes each way and number of resizes. Never the keys you type, because people type passwords into panes.
- Each Chat prompt, with the device, session and a SHA-256 hash of the text. Never the text.
- Each approval answer, with the device, session, approval and the option you chose.

The log is not the only record. Chat prompts and the agent's replies and tool output are stored in the bridge's event log in `RAFIQ_DATA_DIR`, and cached on your phone, so Chat can show them again.

## If it does not connect

| Symptom | Likely cause |
|---|---|
| `421`, `misdirected_request` | The name is missing from `RAFIQ_ALLOWED_HOSTS`, or it does not match the name you used in the app. |
| `401`, `unauthorized` | The phone's token is not one the bridge knows. Pair again. |
| `401`, `device_revoked` | You revoked this phone with `rafiq-bridge revoke`, or unpaired it in the app. Pair it again. |
| `401`, `invalid_pairing_code` | The code is wrong, expired or already used. Run `rafiq-bridge pair` for a new one. |
| `429`, `too_many_attempts` | 100 wrong codes in five minutes. Run `rafiq-bridge pair` again for a fresh code. That also clears the lockout. |
| The app says your server removed the phone | The phone was revoked. Run `rafiq-bridge pair` again. |
| The app says the code is wrong | Codes work once and expire after 10 minutes. Run `rafiq-bridge pair` for a new one. |
| `403`, `forbidden_origin` | A browser page on another site called the bridge. The app sends no `Origin`, so this is not the app. |
| `502` from `tailscale serve` | The bridge is not running, or not on `127.0.0.1:8740`. |
| No answer at all | Tailscale is off on your phone, the phone is on another tailnet, or your tailnet's access rules block it from your server. |
| The bridge will not start and names `RAFIQ_TMUX_SESSIONS`, `RAFIQ_TMUX_SOCKET` or `RAFIQ_BIND_ADDRESS` | A Terminal setting is wrong. Terminal needs at least one exact session name, and a loopback bind address. |
| A session is listed but Terminal closes at once | tmux is older than 3.2, or the session was killed. |
