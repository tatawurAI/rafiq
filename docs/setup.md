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

### Check your Tailscale login too (optional)

`tailscale serve` tells the bridge which Tailscale login made each request, in the `Tailscale-User-Login` header. It removes that header if a client sent it, so the value comes from Tailscale. Set your login, and the bridge refuses any request from another login, even one with a valid device token:

```sh
export RAFIQ_TAILSCALE_LOGIN=alex@example.com
```

Use the login exactly as Tailscale shows it. You find it on the Users page of the Tailscale admin console, or with `tailscale whois 100.64.0.2` on your server, using your phone's Tailscale address.

What it does:

- Every request and both sockets, `/events` and `/terminal`, need the header to name your login, on top of the device token. Only `GET /health` is left open. A missing or different login gets `403 identity_mismatch`.
- A stolen device token is then useless from someone else's Tailscale login, for example on a shared tailnet.
- The bridge trusts the header only on a connection from `127.0.0.1`, where `tailscale serve` connects from. It will not start with `RAFIQ_TAILSCALE_LOGIN` set and `RAFIQ_BIND_ADDRESS` on another address, and says so.
- A refusal is logged with the login that was refused, and nothing else from the request.

What it does not do:

- It does not help against anything that runs on your server. A local process can connect from `127.0.0.1` and send any header it likes.
- Tailscale adds no identity header for a tagged device, so a phone that is tagged is refused. Leave the setting unset if your phone is tagged.
- `tailscale funnel` requests carry no identity header, so they are refused. Never use funnel for the bridge anyway.

## Terminal

Terminal shows a tmux session on your server in the app, and sends your keys to it. It is a remote shell. It is off until you turn it on, and it reaches only the sessions you name.

You need tmux 3.2 or newer on your server, and the default `window-size latest`.

There are two switches. `RAFIQ_TMUX=1` lists your tmux sessions, so you can chat with the agent in them. `RAFIQ_TERMINAL=1` also lets your phone open them in Terminal, and turns on the list by itself. To list and open the sessions where an agent runs:

```sh
export RAFIQ_TERMINAL=1
export RAFIQ_TMUX_SESSIONS=weather-app,docs-site
```

A session with no agent in it, such as a plain shell, is listed but does not open in Terminal until you name it a second time:

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
- While you have Terminal open, the window in tmux has the size of your phone. When you leave, it goes back to the size of your terminal at your desk. If nothing is attached at your desk, the window keeps your phone's size until something attaches.
- Rafiq can ask for your fingerprint or face before Terminal opens. Turn it on in the app's settings. It asks once when Terminal opens, and not for each key.
- The bridge's own view sessions are named `rafiq-view-...`. Do not use that name for your own sessions. When the bridge starts, it ends only the `rafiq-view-` sessions that have no client attached, so two bridges do not close each other's live views.

A listed session shows in your session list with the agent that runs in it. When Terminal may open it, its row has the Terminal toggle. When it may not, because `RAFIQ_TERMINAL` is off or no agent runs in it and it is not in `RAFIQ_TERMINAL_SHELLS`, the row says so and has no toggle. A session you did not list is not shown, and cannot be opened, even if its name starts with a listed name.

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
| `403`, `identity_mismatch` | `RAFIQ_TAILSCALE_LOGIN` is set and the request came from another Tailscale login, a tagged device, or not through `tailscale serve`. Check the login is the one Tailscale shows for your phone. |
| The bridge will not start and names `RAFIQ_TAILSCALE_LOGIN` | The value is not one login, or `RAFIQ_BIND_ADDRESS` is not a loopback address. |
| `403`, `forbidden_origin` | A browser page on another site called the bridge. The app sends no `Origin`, so this is not the app. |
| `502` from `tailscale serve` | The bridge is not running, or not on `127.0.0.1:8740`. |
| No answer at all | Tailscale is off on your phone, the phone is on another tailnet, or your tailnet's access rules block it from your server. |
| The bridge will not start and names `RAFIQ_TMUX_SESSIONS`, `RAFIQ_TERMINAL_SHELLS`, `RAFIQ_TMUX_SOCKET` or `RAFIQ_BIND_ADDRESS` | A tmux setting is wrong. tmux sessions need at least one exact session name, and a loopback bind address. Every name in `RAFIQ_TERMINAL_SHELLS` must also be in `RAFIQ_TMUX_SESSIONS`. |
| A session is listed but has no Terminal toggle | `RAFIQ_TERMINAL` is off, or no agent runs in the session and its name is not in `RAFIQ_TERMINAL_SHELLS`. |
| A session is listed but Terminal closes at once | tmux is older than 3.2, or the session was killed. |
