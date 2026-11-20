# 0002. One Gradle build for protocol, bridge and app

## Status

Accepted, 2026-11-20.

## Context

Rafiq has three pieces of code that must agree exactly: the protocol types, the bridge that produces events, and the Android app that consumes them. A change to an event usually touches all three. If they lived in separate repositories or builds, each protocol change would need coordinated releases and version pinning.

## Decision

The repository is one Gradle multi-project build with three modules: `:protocol`, `:bridge` and `:android`. Both `:bridge` and `:android` depend on `:protocol` as a project dependency. Dependency versions live in a single version catalog, `gradle/libs.versions.toml`.

## Consequences

- A protocol change and the code that uses it land in one pull request, and CI builds them together, so a mismatch fails the build.
- One toolchain and one command set cover every module.
- Versions of shared libraries (Kotlin, coroutines, serialisation) cannot diverge between bridge and app.
- A build now includes the Android toolchain even when you only work on the bridge. Contributors need the Android SDK installed.
- The bridge and the app release together from one version number, so an old app and a new bridge are not guaranteed to match. The additive-only protocol rules in [STYLE.md](../../STYLE.md#wire-protocol) are what keep adjacent versions compatible.
