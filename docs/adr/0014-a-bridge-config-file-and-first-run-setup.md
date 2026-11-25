# 0014. A bridge config file and first-run setup

## Status

Accepted, 2026-11-23.

## Context

The bridge reads all of its settings from environment variables. Setting it up means typing several of them by hand, finding your machine's `*.ts.net` name, and reading a pairing code off the terminal into the phone. That is fine for the people who build Rafiq and too much for anyone who just downloads it.

## Decision

- The bridge reads a config file, `bridge.conf`, in `$XDG_CONFIG_HOME/rafiq/` (by default `~/.config/rafiq/`). It holds the same settings as the environment variables, under the same names, one `NAME=value` per line.
- Environment variables override the file, so every existing setup keeps working.
- The bridge refuses to start when the file can be read or written by anyone other than its owner.
- `rafiq-bridge setup` writes the file. It asks before each change, finds your `*.ts.net` name with `tailscale status --json`, checks that the agent is installed and signed in, and prints or, after asking, runs the `tailscale serve` command.
- Pairing works by QR code. The code holds a `rafiq://pair` link with the bridge's `https://` URL and the short-lived, single-use pairing code from `rafiq-bridge pair`. It never holds a device token. Typing the code stays as the fallback.
- `rafiq-bridge service install` registers the bridge as a `launchd` agent on macOS or a `systemd` user unit on Linux, so it survives a reboot.

## Consequences

- A new install takes one command and one scan.
- Settings now live in two places. `rafiq-bridge setup` prints where each value came from, so an override is never a surprise.
- The file holds no device token: per-device tokens are stored hashed in the bridge's database.
