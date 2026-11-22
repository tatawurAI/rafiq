# Set up the bridge for your phone

Your phone reaches the bridge only over HTTPS, through `tailscale serve` on your server ([ADR 0012](adr/0012-https-through-tailscale-serve.md)). The bridge itself stays on `127.0.0.1`. Tailscale gives your server a real certificate for its `*.ts.net` name and forwards HTTPS traffic from your tailnet to the bridge's local port. The app has no cleartext exception, so this is the only way it connects.

The examples use `box.example.ts.net` for your server's name. Use your own.

## What you need

- Tailscale on your server, signed in to the same tailnet as your phone. The commands here were checked against Tailscale 1.98. Older versions used a different `tailscale serve` syntax, so update first if a command below is refused.
- Tailscale on your phone, connected.
- The bridge built and configured as in the [README](../README.md#quickstart), with a device token in `RAFIQ_DEVICE_TOKEN`.
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
export RAFIQ_DEVICE_TOKEN=replace-with-a-long-random-value   # openssl rand -hex 32
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
curl -H "Authorization: Bearer $RAFIQ_DEVICE_TOKEN" https://box.example.ts.net/sessions
```

The first gives `{"status":"ok","version":"..."}`. The first request after you turn on HTTPS can take a few seconds while Tailscale gets the certificate.

## 6. Pair your phone

1. Make sure Tailscale is connected on your phone.
2. Open Rafiq. Under **Server address**, enter `https://box.example.ts.net`, with no port and no path.
3. Under **Device token**, paste the token.

The app then connects and shows your sessions.

## If it does not connect

| Symptom | Likely cause |
|---|---|
| `421`, `misdirected_request` | The name is missing from `RAFIQ_ALLOWED_HOSTS`, or it does not match the name you used in the app. |
| `401`, `unauthorized` | The token in the app is not the bridge's `RAFIQ_DEVICE_TOKEN`. |
| `403`, `forbidden_origin` | A browser page on another site called the bridge. The app sends no `Origin`, so this is not the app. |
| `502` from `tailscale serve` | The bridge is not running, or not on `127.0.0.1:8740`. |
| No answer at all | Tailscale is off on your phone, the phone is on another tailnet, or your tailnet's access rules block it from your server. |
