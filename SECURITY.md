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

The bridge is a long-running process on your server. It can read files in your project folders, start agent processes, and relay approvals from your phone to those agents. Treat access to the bridge as access to your project.

What the design relies on:

- **Network:** the bridge listens only on the Tailscale interface, and Tailscale ACLs limit which of your devices can reach its port. Nothing is exposed on a public port.
- **Device token:** each phone presents a per-device token, held in the Android Keystore. The bridge rejects requests without a valid token.
- **Approvals:** the app asks for a fresh biometric check before it sends an approval.
- **File access:** the file API refuses paths outside the session's project root.
- **Push payloads:** notifications carry ids and short status only, never file contents or command output.
- **Credentials:** agent credentials stay on your server. The phone never holds them.

## In scope

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
