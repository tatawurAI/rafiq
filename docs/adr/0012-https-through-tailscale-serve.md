# 0012. HTTPS through tailscale serve, with a per-device token

## Status

Accepted, 2026-11-20. Supersedes [0006](0006-tailscale-only-network-with-a-per-device-token.md).

## Context

[ADR 0006](0006-tailscale-only-network-with-a-per-device-token.md) had the bridge listen on the Tailscale interface. The bridge speaks plain HTTP. Android blocks cleartext traffic by default, so the app would need a cleartext exception for the bridge's address. WireGuard encrypts the link, but the device token would still travel in plain HTTP inside it, and a cleartext exception is easy to widen by mistake.

Tailscale can put a local port behind HTTPS with a real certificate for your machine's `*.ts.net` name, using `tailscale serve`. Only devices on your tailnet can reach it.

## Decision

- The bridge stays bound to `127.0.0.1`, its default. It never listens on a public or LAN interface.
- `tailscale serve` on your server publishes the bridge's port over HTTPS at your machine's `*.ts.net` name. The app talks only `https://` and `wss://`, and has no cleartext exception.
- You add that `*.ts.net` name to `RAFIQ_ALLOWED_HOSTS`, so the bridge's `Host` check accepts it.
- Tailscale ACLs restrict who on your tailnet can reach your server. There are no public ports, and `tailscale funnel` is never used.
- Each phone presents a device token, stored encrypted with its key in the Android Keystore, which the bridge checks on every request. This version uses one shared token. Per-device tokens with revocation come later.
- SSH over Tailscale remains the out-of-band way to repair your server.

## Consequences

- The token always travels inside TLS, and the app needs no network security exceptions.
- The certificate is a real one, so the app uses the platform's default trust store.
- You need Tailscale on both your server and your phone, with HTTPS certificates turned on for your tailnet.
- Anything on your server that can reach `127.0.0.1` can reach the bridge's port. The token still applies to it. Anyone with access to the server itself is outside this model. See [SECURITY.md](../../SECURITY.md).
- Two independent checks must fail before someone reaches the bridge: tailnet membership and the token.
