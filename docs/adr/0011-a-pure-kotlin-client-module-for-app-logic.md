# 0011. A pure Kotlin client module for app logic

## Status

Accepted, 2026-11-20. Supersedes [0002](0002-one-gradle-build-for-protocol-bridge-and-app.md).

## Context

[ADR 0002](0002-one-gradle-build-for-protocol-bridge-and-app.md) set up one Gradle build with three modules: `:protocol`, `:bridge` and `:android`. The app's hardest logic does not need Android at all. It turns a stream of events into what the screens show: threads, streaming and replaced messages, tool cards, open approvals and status. It also tracks the replay cursor. In `:android`, that logic could only be tested with the Android toolchain, which is slow, and only on the host JDK through extra setup.

## Decision

The build has four modules: `:protocol`, `:client`, `:bridge` and `:android`.

- `:client` is pure Kotlin on the JVM, with no Android dependencies. It holds the screen-state types, the reducer from events to screen state, the replay cursor, and the repository interfaces the screens use.
- `:client` depends on `:protocol`. `:android` depends on `:client`. `:bridge` depends on `:protocol` only.
- `:android` stays thin: Room, OkHttp, the Keystore and Compose, wired to `:client`.
- Dependency versions still live in one version catalog, `gradle/libs.versions.toml`.

## Consequences

- The reducer is tested on recorded event streams on any JDK, in seconds, without the Android SDK.
- The screens and the data layer build in parallel against the interfaces in `:client`.
- A protocol change, the bridge, the client logic and the app still land in one pull request and build together, as in ADR 0002.
- The rest of ADR 0002 holds: one toolchain, shared library versions, one release version, and the additive-only protocol rules in [STYLE.md](../../STYLE.md#wire-protocol).
- `:client` must not grow Android imports. If it needs a platform service, it declares an interface and `:android` implements it.
