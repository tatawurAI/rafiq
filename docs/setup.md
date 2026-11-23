# Set up the bridge for your phone

Your phone reaches the bridge only over HTTPS, through `tailscale serve` on your server ([ADR 0012](adr/0012-https-through-tailscale-serve.md)). The bridge itself stays on `127.0.0.1`. Tailscale gives your server a real certificate for its `*.ts.net` name and forwards HTTPS traffic from your tailnet to the bridge's local port. The app has no cleartext exception, so this is the only way it connects.

The examples use `box.example.ts.net` for your server's name. Use your own.

## What you need

- Tailscale on your server, signed in to the same tailnet as your phone. The commands here were checked against Tailscale 1.98. Older versions used a different `tailscale serve` syntax, so update first if a command below is refused.
- Tailscale on your phone, connected.
- The bridge installed on your server, as [install.md](install.md) shows, so `rafiq-bridge` is on your `PATH`. To build it from source instead, see the [README](../README.md#quickstart).
- Claude Code installed on your server and signed in, and its ACP adapter `claude-agent-acp`, as the [README](../README.md#requirements) shows.
- To build the app: it compiles against Android SDK Platform 37 (`platforms;android-37.0`) with Build-Tools 37.0.0, and still targets API 36.

## 1. Turn on HTTPS certificates for your tailnet

In the Tailscale admin console, open the DNS page.

1. Turn on MagicDNS, if it is not on already.
2. Under HTTPS Certificates, select Enable HTTPS.

You only do this once per tailnet. If you skip it, the first `tailscale serve` run offers to turn it on for you.

Certificates are public: your server's `*.ts.net` name is recorded in the public Certificate Transparency logs. Pick a machine name you are happy to publish.

## 2. Run rafiq-bridge setup

On your server, as the user who will run the bridge:

```sh
rafiq-bridge setup
```

Setup asks before every change. Answer `y` to go ahead. Anything else, or just Enter, skips that step and changes nothing. It runs nothing that changes your system without a yes. It does run two things that only read: `tailscale status --json`, and a look for programs on your `PATH`.

It goes through these steps:

1. It checks that `claude` and `claude-agent-acp` are on your `PATH`, and says how to install what is missing. It does not sign you in to anything. Run `claude` once yourself to sign in.
2. It reads your server's `*.ts.net` name from `tailscale status --json`, or says that Tailscale is not connected or that MagicDNS is off.
3. It offers each setting that is missing from the settings file, one question each:
   - `RAFIQ_DATA_DIR`, where the bridge keeps its database: `~/.local/share/rafiq`, or `$XDG_DATA_HOME/rafiq` if you set that.
   - `RAFIQ_AGENTS=claude-code=claude-agent-acp`, when the adapter is installed.
   - `RAFIQ_ALLOWED_HOSTS` with your `*.ts.net` name, added to any names already there.
4. It prints the `tailscale serve --bg 8740` command and offers to run it. On Linux that needs `sudo`, or allow your user once with `sudo tailscale set --operator=$USER`.
5. It prints every setting the bridge will use and where it came from:

   ```text
   Where each setting comes from
     RAFIQ_AGENTS         claude-code=claude-agent-acp (bridge.conf)
     RAFIQ_ALLOWED_HOSTS  box.example.ts.net (bridge.conf)
     RAFIQ_BIND_ADDRESS   127.0.0.1 (default)
     RAFIQ_DATA_DIR       /home/you/.local/share/rafiq (bridge.conf)
     RAFIQ_PORT           9000 (environment)
   ```

   A device token or a Tailscale login shows where it comes from, never its value. If the bridge would refuse to start with these settings, setup says why and stops here.
6. If the bridge is not running, it offers to install it as a service that starts when you log in (see [Keep the bridge running](#keep-the-bridge-running)).
7. It asks for a name for your phone and prints a pairing code and QR code, as `rafiq-bridge pair` does. If the bridge is still not running, it tells you to start it and run `rafiq-bridge pair` yourself.

Setup never writes a device token. Each phone gets its own token when it pairs, and the bridge keeps only a hash of it. Run setup again whenever you like. It offers only what is still missing.

## 3. Pair your phone

```sh
rafiq-bridge pair --name pixel
```

`setup` runs this for you at the end. Run it in the same environment as the bridge: it needs the same `RAFIQ_DATA_DIR`, which it reads from the settings file too. It prints a pairing code like `ABCD-EFGH`, your server's address and a QR code.

- **Scan:** in the app, tap Scan the QR code and point your phone at the terminal. The app asks for the camera only then, and pairs as soon as it reads the code. The phone's own camera app can read the code too. It opens Rafiq with the address and code filled in, and you tap Connect after checking the address.
- **Type:** enter your server's address (`https://box.example.ts.net`) and the code.

The QR code holds a `rafiq://pair` link with your server's `https://` address and the pairing code. It never holds a device token. The app refuses a link to a plain `http://` address. If the phone is already paired, the app ignores the link and says so. Unpair in Settings first to pair it with a server again.

The QR code is drawn in your terminal's text colour. On a terminal with dark text on a light background it comes out inverted. The app reads it either way, but another scanner might not. If the address line says to see this guide instead of naming your server, `RAFIQ_ALLOWED_HOSTS` was empty when the bridge started. Run setup, then restart the bridge.

The code works once and expires after 10 minutes. After 100 wrong codes in five minutes the bridge refuses every code until the window passes, a right one too. Anyone who can reach your server's address could cause that, so running `rafiq-bridge pair` again clears the lockout and gives you a fresh code at once. The phone gets its own token and keeps it in the Android Keystore. Nobody, including you, can read it again.

## Keep the bridge running

```sh
rafiq-bridge service install
```

This registers the bridge with your system so it starts when you log in and restarts if it stops:

- On macOS, a launchd agent in `~/Library/LaunchAgents/ai.tatawur.rafiq-bridge.plist`. Its log is `~/Library/Logs/rafiq-bridge.log`.
- On Linux, a systemd user unit in `~/.config/systemd/user/rafiq-bridge.service`. Read its log with `journalctl --user -u rafiq-bridge.service`. A user unit stops when you log out. To keep it running and start it at boot, run `sudo loginctl enable-linger $USER` once.

The service reads its settings from the settings file only. It gets no `RAFIQ_` variables from your shell, and `service install` names any that are set in your shell but missing from the file. It does keep the `PATH` you install it from, so it finds `claude-agent-acp`, `node` and `tmux`, and your `JAVA_HOME` and `XDG_CONFIG_HOME` if you set them. Run `service install` again after you move the bridge or change your `PATH`. To remove the service:

```sh
rafiq-bridge service uninstall
```

Your settings file and data folder stay where they are.

## The settings file

The bridge reads `bridge.conf` in `$XDG_CONFIG_HOME/rafiq/`, which is `~/.config/rafiq/` unless you set `XDG_CONFIG_HOME`. It holds the same settings as the environment variables, under the same names, one per line:

```text
# Written by rafiq-bridge setup. Edit freely.
RAFIQ_DATA_DIR=/home/you/.local/share/rafiq
RAFIQ_AGENTS=claude-code=claude-agent-acp
RAFIQ_ALLOWED_HOSTS=box.example.ts.net
```

- Blank lines and lines starting with `#` are ignored. A value is taken exactly as written. Do not put it in quotes.
- An environment variable that is set wins over the file. One that is set but empty does not, so to change a value from the file, edit the file.
- The bridge refuses to start if anyone but you can read or write the file, if it belongs to another user, or if others can write to its folder. Setup writes it with mode 0600 in a 0700 folder. Fix a file you wrote yourself with `chmod 600 ~/.config/rafiq/bridge.conf`.
- A line that is not a setting, or a setting given twice, stops the bridge with the line number. The message never shows the value.

## Set it up by hand

Setup does nothing you cannot do yourself. To do it by hand:

1. Find your server's name:

   ```sh
   tailscale status --json | jq -r .Self.DNSName
   ```

   This prints a name such as `box.example.ts.net.`. Drop the final dot.
2. Put the settings in `bridge.conf`, or export them, keeping the default bind address and port:

   ```sh
   export RAFIQ_AGENTS=claude-code=claude-agent-acp
   export RAFIQ_DATA_DIR=/var/lib/rafiq
   export RAFIQ_ALLOWED_HOSTS=box.example.ts.net
   rafiq-bridge
   ```

   Do not set `RAFIQ_BIND_ADDRESS` to a Tailscale or LAN address. `tailscale serve` connects to the bridge on `127.0.0.1`.

   `RAFIQ_ALLOWED_HOSTS` matters because `tailscale serve` passes on the `Host` header your phone sent. The bridge sees `Host: box.example.ts.net`, with no port because HTTPS uses the default port 443, and answers any host it does not know with `421 Misdirected Request`. A port in the header would also be accepted, since the check compares the name only.
3. Publish the bridge with `tailscale serve`:

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

### Check from another device

From any device on your tailnet, such as your laptop:

```sh
curl https://box.example.ts.net/health
curl -H "Authorization: Bearer $DEVICE_TOKEN" https://box.example.ts.net/sessions   # a token from pairing, or the legacy token
```

The first gives `{"status":"ok","version":"..."}`. The first request after you turn on HTTPS can take a few seconds while Tailscale gets the certificate.

## After pairing

### List and revoke phones

```sh
rafiq-bridge devices
rafiq-bridge revoke <id>
```

`devices` shows each phone's id, name, when it last connected and whether it is active. `revoke` cuts that phone off at once: its open connections close and its next request is refused. The phone clears its token and shows the pairing screen. Revoking is permanent. Pair the phone again for a new token. Unpairing in the app does the same thing from the phone. If your server cannot be reached when you unpair, the app tells you the phone is still listed, and you run `rafiq-bridge revoke <id>` later. These commands talk to the running bridge over a Unix socket in `$RAFIQ_DATA_DIR/admin`, a folder only your user can open. They never use the network port. They are for you: any process that runs as the same user as the bridge can reach this socket and the database in `RAFIQ_DATA_DIR`. The Wave 2 installer adds read-deny rules so an agent run by the bridge cannot.

### Retire the old shared token

If you set `RAFIQ_DEVICE_TOKEN` before this version, that token still works. The bridge lists it as the device `legacy`. Once your phone is paired with its own token, run `rafiq-bridge revoke legacy`, then remove `RAFIQ_DEVICE_TOKEN` from your service environment. The bridge no longer needs it. A revoked `legacy` stays revoked even if the variable is still set, so a leftover value is harmless.

### Check your Tailscale login too (optional)

`tailscale serve` tells the bridge which Tailscale login made each request, in the `Tailscale-User-Login` header. It removes that header if a client sent it, so the value comes from Tailscale. Tailscale describes these headers under "Identity headers" in [Tailscale Serve](https://tailscale.com/kb/1312/serve). Set your login, and the bridge refuses any request from another login, even one with a valid device token:

```sh
export RAFIQ_TAILSCALE_LOGIN=alex@example.com
```

Use the login exactly as Tailscale shows it. You find it on the Users page of the Tailscale admin console, or with `tailscale whois 100.64.0.2` on your server, using your phone's Tailscale address.

What it does:

- Every request and both sockets, `/events` and `/terminal`, need the header to name your login, on top of the device token. Only `GET /health` is left open. A missing or different login gets `403 identity_mismatch`.
- A stolen device token is then useless from someone else's Tailscale login, for example on a shared tailnet.
- Someone you shared your server with in Tailscale reaches it with their own login, so they are refused. That is intended.
- The bridge trusts the header only on a connection from `127.0.0.1`, where `tailscale serve` connects from. It will not start with `RAFIQ_TAILSCALE_LOGIN` set and `RAFIQ_BIND_ADDRESS` on another address, and says so.
- A refusal is logged with the login that was refused, and nothing else from the request.

What it does not do:

- It does not help against anything that runs on your server. A local process can connect from `127.0.0.1` and send any header it likes.
- Tailscale adds no identity header for a tagged device, so a phone that is tagged is refused. Leave the setting unset if your phone is tagged.
- `tailscale funnel` requests carry no identity header, so they are refused. Never use funnel for the bridge anyway.
- A login with non-ASCII characters is not supported. Tailscale sends it encoded, and the bridge refuses it. The bridge also will not start if `RAFIQ_TAILSCALE_LOGIN` holds an encoded value.

## Project folders and permission modes

New session on your phone offers your recent project folders, and lets you browse folders on your server one level at a time. It also sets the permission mode the agent starts in.

`RAFIQ_PROJECT_ROOTS` lists the folders you may browse, as absolute paths separated by commas:

```sh
export RAFIQ_PROJECT_ROOTS=/srv/projects,/srv/sandbox
```

- The phone sees folder names only, one level at a time, and never file contents.
- The bridge refuses any path outside these folders, including one that leaves a root through `..` or a symlink.
- Hidden folders are not listed, and a folder shows at most 500 folders.
- Without the setting, nothing can be browsed. Recent projects and a typed path still work.

`RAFIQ_DEFAULT_MODES` sets the permission mode a session starts in when you do not pick one, as `agent=mode` pairs separated by commas. Set it so sessions from your phone ask before edits and commands, whatever your server's own agent settings say:

```sh
export RAFIQ_DEFAULT_MODES=claude-code=default
```

- The mode names come from the agent. For Claude Code, `default` asks before edits and commands, `acceptEdits` accepts file edits, `plan` plans before changing anything, `auto` lets Claude Code decide, and `bypassPermissions` never asks.
- An agent with no entry starts in whatever mode it picks itself, which can be one that never asks.
- If the agent does not offer the mode you set, the bridge refuses to start the session and says so, rather than start it in a mode that might never ask.
- You can change the mode of a running session from its header on your phone. Sessions in tmux keep the mode you set in the agent itself.

## Terminal

Terminal shows a tmux session on your server in the app, and sends your keys to it. It is a remote shell. It is off until you turn it on, and it reaches only the sessions you name.

**Changed, and it can break an existing setup:** a plain shell session, one with no agent in it, now opens in Terminal only if you list it in `RAFIQ_TERMINAL_SHELLS`. Before, `RAFIQ_TERMINAL=1` let your phone open every session in `RAFIQ_TMUX_SESSIONS`. If a session you used to open now shows no Terminal toggle, add its name to `RAFIQ_TERMINAL_SHELLS`.

You need tmux 3.2 or newer on your server, and the default `window-size latest`.

There are two switches. `RAFIQ_TMUX=1` lists your tmux sessions, so you can chat with the agent in them. `RAFIQ_TERMINAL=1` also lets your phone open them in Terminal, and turns on the list by itself. To list and open the sessions where an agent runs:

```sh
export RAFIQ_TERMINAL=1
export RAFIQ_TMUX_SESSIONS=weather-app,docs-site
```

A session with no agent in it, such as a plain shell, is listed but does not open in Terminal until you name it a second time.

```sh
export RAFIQ_TERMINAL_SHELLS=docs-site
```

| Setting | Meaning |
|---|---|
| `RAFIQ_TMUX` | `1` lists the tmux sessions you name, without Terminal. Anything else, or unset, lists none unless `RAFIQ_TERMINAL` is on. |
| `RAFIQ_TERMINAL` | `1` lists the tmux sessions you name and lets your phone open them in Terminal. Anything else, or unset, leaves Terminal off. |
| `RAFIQ_TMUX_SESSIONS` | The exact names of the tmux sessions your phone may see. Letters, digits, `.`, `_` and `-` only. No `*`. The bridge will not start with a bad name, and says which variable it is. |
| `RAFIQ_TERMINAL_SHELLS` | Sessions from `RAFIQ_TMUX_SESSIONS` that open in Terminal even when no agent runs in them. Same name rules. A session with an agent does not need to be here. A name that is not also in `RAFIQ_TMUX_SESSIONS` fails the start. |
| `RAFIQ_TMUX_SOCKET` | The tmux socket name, if your sessions run on a tmux server started with `tmux -L <name>`. Unset uses the default server. |
| `RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK` | `1` lets the bridge bind to an address other than `127.0.0.1` while tmux sessions are on. Leave it unset. |

What to know before you turn it on:

- Terminal reaches every window and pane of each session you list. Name only sessions you are happy to type into from your phone.
- With tmux sessions on, the bridge refuses to start on a non-loopback address. Keep `RAFIQ_BIND_ADDRESS` at its default and use `tailscale serve`, as above. Never use `tailscale funnel`.
- Each phone can have 2 terminals open, and the bridge 4. A terminal closes after 10 minutes with no keys or resize from your phone and no output from the pane. A phone that is watching an agent work stays open. A terminal socket is closed after 8 hours whatever it is doing, so a forgotten phone does not stay attached. The app reconnects only if Terminal is on screen. A device may open at most 5 terminals a minute. Past that, the bridge answers "too many terminal opens, wait a moment". Fast size changes, such as a keyboard or fold animation, are merged into the newest size. More than 60 size changes in 10 seconds end the terminal.
- While you have Terminal open, the window in tmux has the size of your phone. When you leave, it goes back to the size of your terminal at your desk. If nothing is attached at your desk, it goes back to the size it had before you opened Terminal.
- Tap the window label to show another window of the session. Only your phone changes window, and your desk stays where it is. After you pick another window on your phone, your desk's window takes back its size the next time you type at the desk.
- Scrolling back reads the pane's history from tmux. It never changes what your desk sees.
- Rafiq can ask for your fingerprint or face before Terminal opens. Turn it on in the app's settings. It asks once when Terminal opens, and not for each key.
- The bridge's own view sessions are named `rafiq-view-...`. Do not use that name for your own sessions. When the bridge starts, it ends only the `rafiq-view-` sessions that have no client attached, so two bridges do not close each other's live views.

A listed session shows in your session list with the agent that runs in it. When Terminal may open it, its row has the Terminal toggle. When it may not, because `RAFIQ_TERMINAL` is off or no agent runs in it and it is not in `RAFIQ_TERMINAL_SHELLS`, the row says so and has no toggle. A session you did not list is not shown, and cannot be opened, even if its name starts with a listed name.

## Chat for tmux sessions

A listed tmux session that runs Claude Code also has a Chat. Replies, tool steps, progress and approvals come from Claude Code's own hooks in that project, so live Chat needs the hooks, which you set up for the project from the app. Before that, Chat shows what Claude Code's own session record holds, read from the end of the record, but nothing new arrives and you cannot send. Your messages are typed into the pane, so they also show at your desk.

- Tested with Claude Code 2.1.330. Claude Code's hooks and session record are not a stable interface, so a newer release can change them. If Chat looks wrong, the app says it may be incomplete. Open Terminal, which does not depend on any of this.
- Chat behaves exactly like typing at your desk. A message that starts with `!` runs as a shell command on your server, and every slash command and skill runs. That is by design. Rafiq adds no limit, list or confirmation for them.
- The bridge types only when it is safe: Claude is idle, no dialog is open at your desk, and the input box is empty. Otherwise it says why, for example "There is half-typed text in the terminal". A message sent while Claude is busy waits in the bridge and is typed when the turn ends, after any message already waiting. A waiting message is dropped after 10 minutes, or when the phone that sent it is revoked. Control characters other than tab and newline are removed before typing. A message is at most 64 KB.
- A slash command that opens a menu at your desk, such as `/model`, shows as "not confirmed" on your phone, because Claude reports nothing until you pick at the desk.
- An approval shows on your phone and at your desk at the same time, and the first answer wins. If nobody answers on the phone within 10 minutes, it goes back to the desk. At most 3 approvals per session, and 10 in all, wait for a phone. More are asked at the desk only. Revoking any paired device sends every waiting approval back to the desk.
- "Allow for this session" on a command allows exactly that command, as written, for the rest of the Claude session. A command with `*`, several lines or a `)` has no such option. For any other tool the option allows every call of that tool for the rest of the session, and its label says so, for example "Allow every Write this session". Settings that change what a command does, such as running it outside the sandbox or in the background, are shown under it.
- Chat prompts and tool output are stored in the bridge's event log on your server, and cached in the app on your phone, like every other session's events.

## Chat for tmux sessions: the hooks

Chat for a tmux session gets the agent's replies, steps and approvals from Claude Code's own hooks. You install them per project, from the app, and only after you have seen exactly what will be written. Rafiq never installs hooks for all projects, and never edits your user-level settings file (`~/.claude/settings.json`).

You need `python3` on your server. The hook program is `hooks/rafiq-hook.py` in the installed bridge, next to `bin` and `lib`, and uses only Python's standard library. Keep the bridge where you installed it: the hooks point at that path. If you move it, remove the hooks from the app and install them again. The bridge uses the hook program only if it and its folder belong to you or to root and no other user can write to them, so a package install such as `/opt/rafiq` with mode 755 works, and a folder others can write to does not.

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
| The bridge will not start and names `bridge.conf` | The file or its folder can be used by other users, or a line in it is not a setting. The message says which line, and the `chmod` that fixes the permissions. |
| `pair` prints no QR code and no address | `RAFIQ_ALLOWED_HOSTS` was empty when the bridge started. Run `rafiq-bridge setup`, then restart the bridge. |
| `403`, `identity_mismatch` | `RAFIQ_TAILSCALE_LOGIN` is set and the request came from another Tailscale login, a tagged device, or not through `tailscale serve`. Check the login is the one Tailscale shows for your phone. |
| The bridge will not start and names `RAFIQ_TAILSCALE_LOGIN` | The value is not one login, or `RAFIQ_BIND_ADDRESS` is not a loopback address. |
| `403`, `forbidden_origin` | A browser page on another site called the bridge. The app sends no `Origin`, so this is not the app. |
| `502` from `tailscale serve` | The bridge is not running, or not on `127.0.0.1:8740`. |
| No answer at all | Tailscale is off on your phone, the phone is on another tailnet, or your tailnet's access rules block it from your server. |
| The bridge will not start and names `RAFIQ_TMUX_SESSIONS`, `RAFIQ_TERMINAL_SHELLS`, `RAFIQ_TMUX_SOCKET` or `RAFIQ_BIND_ADDRESS` | A tmux setting is wrong. tmux sessions need at least one exact session name, and a loopback bind address. Every name in `RAFIQ_TERMINAL_SHELLS` must also be in `RAFIQ_TMUX_SESSIONS`. |
| A session is listed but has no Terminal toggle | `RAFIQ_TERMINAL` is off, or no agent runs in the session and its name is not in `RAFIQ_TERMINAL_SHELLS`. |
| A session is listed but Terminal closes at once | tmux is older than 3.2, or the session was killed. |
