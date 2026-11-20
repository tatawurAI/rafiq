# Architecture decision records

An architecture decision record (ADR) captures one significant decision: the situation that forced it, what we chose, and what follows. We use the format described by Michael Nygard in "Documenting Architecture Decisions".

## Format

Each ADR is a Markdown file with these sections:

- **Title:** `# NNNN. Short imperative or noun phrase`.
- **Status:** `Proposed`, `Accepted`, `Deprecated`, or `Superseded by NNNN`, with the date it took that status.
- **Context:** the forces at play: the problem, the constraints and the options. Write for a newcomer.
- **Decision:** what we will do, in the active voice.
- **Consequences:** what becomes easier and what becomes harder. Include the downsides.

Add an **Alternatives considered** section when rejected options are worth keeping. Say why each lost.

## Numbering and naming

- Numbers are four digits, assigned in order, and never reused.
- The file is `NNNN-short-hyphenated-title.md`.
- Take the next free number when you open the pull request.

## Lifecycle

1. Open a pull request with the ADR at status `Proposed`.
2. When it is accepted, set the status to `Accepted` and the date.
3. Accepted ADRs are not edited, apart from the status line. To change a decision, write a new ADR, and mark the old one `Superseded by NNNN`.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-one-gradle-build-for-protocol-bridge-and-app.md) | One Gradle build for protocol, bridge and app | Accepted |
| [0003](0003-kotlin-on-both-ends-with-a-shared-protocol-module.md) | Kotlin on both ends with a shared protocol module | Accepted |
| [0004](0004-the-app-knows-only-generic-sessions-threads-and-events.md) | The app knows only generic sessions, threads and events | Accepted |
| [0005](0005-reconnect-by-sequence-replay.md) | Reconnect by sequence replay | Accepted |
| [0006](0006-tailscale-only-network-with-a-per-device-token.md) | Tailscale-only network with a per-device token | Accepted |
| [0007](0007-native-compose-for-fold-and-cover-screen-apis.md) | Native Compose for fold and cover-screen APIs | Accepted |
| [0008](0008-formatting-by-ktlint-and-detekt.md) | Formatting by ktlint and detekt | Accepted |
| [0009](0009-tests-earn-their-place.md) | Tests earn their place | Accepted |
| [0010](0010-push-transport.md) | Push transport | Proposed |
