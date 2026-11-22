# 0006. Tailscale-only network with a per-device token

## Status

Superseded by 0012, 2026-11-20.

## Context

The bridge can read project files, start agent processes and relay approvals. Exposing it to the public internet would be dangerous, and running a public relay would add infrastructure and trust that a personal tool should not need. You still need to reach your server from anywhere.

## Decision

The bridge listens only on the Tailscale interface. There are no public ports. Tailscale ACLs restrict the bridge's port to your own devices. On top of that, each phone presents a per-device token, stored in the Android Keystore, which the bridge checks on every request. SSH over Tailscale remains the out-of-band way to repair the server.

## Consequences

- The network layer gives encryption, a stable address and device identity without us building them.
- Two independent checks must fail before someone reaches the bridge: network membership and the token.
- A single device can be cut off by revoking its token, without touching the others.
- You need Tailscale on both your server and your phone. That is a requirement, not an option, for v1.
- Anyone with access to the server itself is outside this model. See [SECURITY.md](../../SECURITY.md).
