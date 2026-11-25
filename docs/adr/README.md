# Architecture decision records

Each file here records one decision that shapes Rafiq: what prompted it, what we chose, and what it costs us. The layout follows Michael Nygard's "Documenting Architecture Decisions".

## File layout

- Name: `NNNN-short-hyphenated-title.md`, with a four-digit number that is never reused.
- Sections, in order: Status, Context, Decision, Consequences. Add Alternatives considered when a rejected option is worth explaining.
- Status is one of `Proposed`, `Accepted`, `Deprecated` or `Superseded by NNNN`, followed by a date.
- Write Context so someone who joined last week can follow it.

## Proposing and changing

1. Pick the next free number and open a pull request with status `Proposed`.
2. On acceptance, set the status to `Accepted` and add the date.
3. To reverse a decision, add a new ADR and mark the old one `Superseded by NNNN`. Leave the old text as it was written.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-one-gradle-build-for-protocol-bridge-and-app.md) | One Gradle build for protocol, bridge and app | Superseded by 0011 |
| [0003](0003-kotlin-on-both-ends-with-a-shared-protocol-module.md) | Kotlin on both ends with a shared protocol module | Accepted |
| [0004](0004-the-app-knows-only-generic-sessions-threads-and-events.md) | The app knows only generic sessions, threads and events | Accepted |
| [0005](0005-reconnect-by-sequence-replay.md) | Reconnect by sequence replay | Accepted |
| [0006](0006-tailscale-only-network-with-a-per-device-token.md) | Tailscale-only network with a per-device token | Superseded by 0012 |
| [0007](0007-native-compose-for-fold-and-cover-screen-apis.md) | Native Compose for fold and cover-screen APIs | Accepted |
| [0008](0008-formatting-by-ktlint-and-detekt.md) | Formatting by ktlint and detekt | Accepted |
| [0009](0009-tests-earn-their-place.md) | Tests earn their place | Accepted |
| [0010](0010-push-transport.md) | Push transport | Accepted |
| [0011](0011-a-pure-kotlin-client-module-for-app-logic.md) | A pure Kotlin client module for app logic | Accepted |
| [0012](0012-https-through-tailscale-serve.md) | HTTPS through tailscale serve, with a per-device token | Accepted |
| [0013](0013-agents-in-tmux.md) | Agents in tmux | Proposed |
| [0014](0014-a-bridge-config-file-and-first-run-setup.md) | A bridge config file and first-run setup | Accepted |
