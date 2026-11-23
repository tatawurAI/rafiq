# Install the bridge without a JDK

Each release has a self-contained bridge download for macOS and Linux, on arm64 and x64. It carries its own Java runtime, so your server needs no JDK, no Gradle and no checkout. This page installs it from a release and starts it.

To build from source instead, follow the [README](../README.md#quickstart).

## What you need

- A server running macOS or Linux, on arm64 or x64.
- `curl` and `tar`, which every supported system has.
- Python 3. The bridge's hook program is a Python script, and it runs on your server.
- [Tailscale](https://tailscale.com) on your server and your phone. [docs/setup.md](setup.md) covers it.
- tmux, only if you turn on Terminal and the Chat view for tmux sessions.

## 1. Install the agent adapter

The bridge talks to your coding agent through an ACP adapter. It is a separate install, and the release does not include it. For Claude Code, install the adapter and sign in to Claude Code on your server:

```sh
npm install -g @agentclientprotocol/claude-agent-acp
claude
```

`claude` opens Claude Code, where you sign in once. The bridge never signs in for you.

## On macOS or Linux with Homebrew

If you use Homebrew, it installs the same self-contained download:

```sh
brew install tatawurAI/tap/rafiq-bridge
```

Then skip to step 4. The formula is published with each release.

## 2. Download the bridge

Pick the release you want from the [releases page](https://github.com/tatawurAI/rafiq/releases), then run this on your server. Set `version` to that release without the leading `v`.

```sh
version=0.1.0
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)  platform=macos-arm64 ;;
  Darwin-x86_64) platform=macos-x64 ;;
  Linux-x86_64)  platform=linux-x64 ;;
  Linux-aarch64) platform=linux-arm64 ;;
  *) echo "No bridge download for $(uname -s) $(uname -m)." >&2; false ;;
esac

base=https://github.com/tatawurAI/rafiq/releases/download/v$version
archive=rafiq-bridge-$version-$platform.tar.gz
curl --fail --location --remote-name "$base/$archive"
curl --fail --location --remote-name "$base/SHA256SUMS"
```

Check the download against the checksum list:

```sh
grep " $archive\$" SHA256SUMS | shasum -a 256 -c -
```

You should see `OK`. On a Linux system without `shasum`, use `sha256sum -c -` instead. The list comes from the same release, so it catches a damaged download. It does not prove who built the file.

## 3. Unpack it

```sh
mkdir -p ~/.local/opt ~/.local/bin
tar -xzf "$archive" -C ~/.local/opt
ln -sf ~/.local/opt/rafiq-bridge-$version-$platform/bin/rafiq-bridge ~/.local/bin/rafiq-bridge
```

The folder holds `bin/`, `lib/`, `hooks/` and `runtime/`, which is the Java runtime. Keep them together. The `rafiq-bridge` link works from any folder. Make sure `~/.local/bin` is on your `PATH`.

## 4. Set it up

```sh
rafiq-bridge setup
```

Setup asks before each change. It saves your settings, checks that the agent adapter from step 1 is on your `PATH` and prints a pairing QR code. [docs/setup.md](setup.md) walks through each question, `tailscale serve` and pairing your phone.

## 5. Run it

To try it in a terminal:

```sh
rafiq-bridge
```

In a second terminal, check that it is up:

```sh
curl http://127.0.0.1:8740/health
```

You should get `{"status":"ok","version":"0.1.0"}`. The bridge listens on `127.0.0.1` only.

To keep it running in the background and start it again after a reboot, install a service:

```sh
rafiq-bridge service install
```

If you installed with Homebrew, you can run `brew services start rafiq-bridge` instead. Pick one of the two, never both, because each starts a bridge and two bridges cannot share one port. Both read the settings that `rafiq-bridge setup` saved. Details are in [docs/setup.md](setup.md).

## Install the app

Each release also has `rafiq-<version>.apk`, signed with the project's release key. Download it and check it against `SHA256SUMS` as above, then open it on your phone. Android asks you to allow installs from the app you opened it with. The app needs Android 12 or newer.

## Upgrade

Repeat steps 2 and 3 with the new version. The link in `~/.local/bin` now points at the new folder. Your data stays in `RAFIQ_DATA_DIR`, so your paired phones keep working. Restart the bridge, and delete the old folder in `~/.local/opt` when you no longer need it.

## Uninstall

```sh
rm -rf ~/.local/opt/rafiq-bridge-* ~/.local/bin/rafiq-bridge
```

This leaves `RAFIQ_DATA_DIR`, which holds your event log and your paired devices. Delete that folder as well to remove them.
