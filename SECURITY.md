# Security policy

## Reporting a vulnerability

Report vulnerabilities privately. Never open a public issue, discussion or pull request for a security problem.

Use either channel:

1. GitHub's private vulnerability reporting: open the **Security** tab of this repository and choose **Report a vulnerability**.
2. Email security@tatawur.ai.

Include:

- What you found and what an attacker could do with it.
- Steps to reproduce, or a proof of concept.
- The Rafiq version or commit, and your server OS and JDK version.
- Whether the issue needs network access to the bridge, a paired device, or a malicious agent.

Do not include real credentials, tokens or private project files in a report. Redact them.

## What to expect

We aim to acknowledge a report within 3 business days. We will tell you whether we accept it as a vulnerability, keep you updated while we work on a fix, and credit you in the release notes if you want that. This is a small team, so these are targets and not guarantees.

## Supported versions

Only the latest release receives security fixes. Rafiq is pre-alpha, so expect to update to the newest release to get a fix.

## Threat surface

Rafiq is pre-alpha. The bridge starts agent processes and relays your prompts and approvals to them, so reaching its port is as good as running commands in your project folders. It binds to `127.0.0.1` unless you set `RAFIQ_BIND_ADDRESS`. Every route except `GET /health` requires the device token, and the bridge refuses to start with agents configured and no token of at least 32 characters. It reads no project files yet.

The finished bridge will also read files in your project folders. The protections below are the design, and each lands with the milestone shown.

| Protection | Intent | Planned in |
|---|---|---|
| Tailscale-only bind | You bind the bridge to your Tailscale address and use Tailscale ACLs to limit which of your devices reach it. This is deployment guidance. Nothing exposes a public port. | Deployment guidance, available now through `RAFIQ_BIND_ADDRESS` |
| Shared device token | Every route except `GET /health` needs `Authorization: Bearer <token>`, compared in constant time. The `Host` header must be the bind address, `localhost` or a name in `RAFIQ_ALLOWED_HOSTS`, and a request with a foreign `Origin` is refused. This blocks DNS rebinding and cross-site requests. | Available now |
| Per-device tokens | Each phone gets its own token, kept in the Android Keystore, with pairing and revocation. | M3 |
| Biometric approvals | With the approval lock on, the app sends an approval only within 30 seconds of a strong fingerprint or face check, enforced by a key in the Android Keystore. | M3 |
| Push payloads | Notifications carry ids and short status only, never file contents or command output. | M3 |
| Path scoping | The file API refuses paths outside the session's project root. | M5 |
| Auth relay | Sign-in flows are relayed without exposing credentials to the phone or a third party. | M5 |

An agent in tmux runs as the same OS user as the bridge. Its hooks reach the bridge over a Unix socket in a folder only that user can open, with a credential that is new at every start. A same-user agent that is allowed to read the bridge's data folder can still read that credential and approve its own requests. Running the bridge as a different OS user is the complete fix. [docs/setup.md](docs/setup.md#the-limit-of-a-hook-on-the-same-user) describes it, and what it does not cover yet.

The approval lock has a known limit. Someone who knows your phone's PIN can add their own fingerprint, which turns the lock off. The app then says so in the session list and in Settings until you dismiss it. Keep your PIN private.

M3 and M5 are [milestones](https://github.com/tatawurAI/rafiq/milestones). Agent credentials stay on your server, and the phone never holds them.

### Optional Tailscale login check

With `RAFIQ_TAILSCALE_LOGIN` set, every route except `GET /health` and both socket handshakes also need the `Tailscale-User-Login` header that `tailscale serve` adds to name that login. A missing or different login gets 403 `identity_mismatch`. This is a second proof next to the device token: a stolen token used from another Tailscale login is refused. The bridge trusts the header only on a loopback connection and will not start with the setting and a non-loopback bind address, so a client that reaches the port directly cannot supply it. It gives no protection against anything running on your server, since a local process can connect from loopback and send any header. Tailscale documents the header under "Identity headers" in [Tailscale Serve](https://tailscale.com/kb/1312/serve). See [docs/setup.md](docs/setup.md#check-your-tailscale-login-too-optional).

## In scope

These describe the finished design. Report a gap in any of them, and report anything wrong with what exists today.

- Bypassing the device token, or reaching the bridge without it.
- Reading or writing files outside a session's project root, including through symlinks or `..` segments.
- Sending an approval without the biometric check, or approving on behalf of another session.
- Leaking credentials, tokens or file contents through logs, push payloads or the event log.
- Weaknesses in the sign-in relay that let a third party capture or replay a login.

## Out of scope

- Anything that requires an attacker to already control your server, your Tailscale account or an unlocked, already-authenticated phone.
- Actions an agent takes after you approved them. Review what you approve.
- Vulnerabilities in Tailscale, Android, or an agent's own software. Report those to their maintainers.
- Denial of service by a device that already holds a valid token.
