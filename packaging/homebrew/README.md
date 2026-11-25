# Homebrew formula

`rafiq-bridge.rb.in` is the template for the Rafiq bridge formula. It installs the self-contained build from a GitHub Release, which carries its own Java runtime.

The release workflow renders it with the real version and checksums and attaches the result to each release as `rafiq-bridge.rb`. For a final version, its `update-tap` job also commits that file to [tatawurAI/homebrew-tap](https://github.com/tatawurAI/homebrew-tap) as `Formula/rafiq-bridge.rb`. Prereleases are not sent to the tap. The one-time setup is in [../README.md](../README.md).

Users then run:

```sh
brew install tatawurAI/tap/rafiq-bridge
```

To render a formula by hand from a release's checksums:

```sh
scripts/render-homebrew-formula.sh 0.1.0 SHA256SUMS > rafiq-bridge.rb
```

The script stops with an error if `SHA256SUMS` lacks any of the four archives.

## Running as a service

Run `rafiq-bridge setup` first. It writes your settings, including the data folder, to the bridge's config file, and the service reads them from there. Then use either `brew services start rafiq-bridge` or `rafiq-bridge service install`, never both. Each starts a bridge, and two bridges cannot share one port.
