# Rafiq style guide

Rafiq is Kotlin from the wire to the screen. This guide lists the conventions that are specific to this codebase. Spotless and detekt enforce the formatting. The rest is for you and the reviewer to keep.

## Kotlin and Compose

### Tooling

- `./gradlew spotlessApply` formats. ktlint runs inside Spotless and follows the official Kotlin conventions, with the 120-column limit from `.editorconfig`. detekt rules live in `config/detekt/detekt.yml`. See [ADR 0008](docs/adr/0008-formatting-by-ktlint-and-detekt.md).
- Suppress a detekt rule at the smallest scope you can, and put the reason in a comment next to the `@Suppress`.

### Names and units

- A unit that the type does not carry goes in the name: `timestampMs`, `expiresAtMs`, `widthDp`. Prefer `kotlin.time.Duration` for lengths of time inside one process.
- Kotlin property names are for readers of Kotlin. The wire name is a separate decision (see [Wire protocol](#wire-protocol)), so `timestampMs` can be `ts_ms` on the wire.
- Say what a thing is in Rafiq's vocabulary: session, thread, event, approval. A `Thread` here is a conversation within a session, so avoid naming anything `Worker` or `Job` when it means one.

### Null and unknown

- On a protocol type, null means "the sender left this out". It never means "unknown value". An enum value the receiver does not recognise becomes that enum's `UNKNOWN` entry.
- Outside the protocol, prefer a non-null type with a sensible default, or a sealed type with a case for the absent state.
- Do not use `!!` in main code.

### Coroutines and Flow

- The bridge ties work to the session it serves. When a session ends, everything launched for it is cancelled with it.
- The app collects the event stream in a scope that follows the screen's lifecycle, so a folded or backgrounded app does not hold a socket open for nothing. Resuming goes through replay (see [ADR 0005](docs/adr/0005-reconnect-by-sequence-replay.md)).
- Take a `CoroutineDispatcher` as a constructor parameter in any class that does blocking work, so tests can substitute one.
- Never swallow `CancellationException`. A `catch (e: Exception)` around a suspending call must rethrow it.
- Keep `MutableStateFlow` and `MutableSharedFlow` private and expose read-only `StateFlow` and `Flow`.

### Compose

- Composables take state and callbacks as parameters. State is hoisted to the screen's state holder, and the screen is the only place that reads from it.
- Colour comes from `RafiqColors` or the Material scheme in `ui/theme`. The private `Palette` is not for screens. No hex literals, no `Color(...)` in a composable.
- Text uses the theme's typography: Archivo for headings, IBM Plex Sans for body, IBM Plex Mono for code and labels. Do not name a font family in a screen.
- Anything you can tap is at least 44dp in both directions.
- Layout decisions that depend on the fold (cover screen, open, Flex Mode) are driven by a posture value passed into the composable. Reading window state inside a leaf composable makes it impossible to preview.
- Every composable that a screen reuses has a `@Preview`, including the states that differ in layout: empty, long text, and the half-folded posture where it applies.
- Put user-visible strings in `strings.xml`.
- The app's `minSdk` is 31 (Android 12). Do not add compatibility shims for older versions.

## Wire protocol

The `:protocol` module is the contract between the bridge and the app, and the only code both sides share. A released app and a newer bridge have to keep talking, so these rules are strict. The event model is described in [docs/architecture.md](docs/architecture.md#event-model).

- Every serialised property and every subtype carries `@SerialName`. The Kotlin name can then be refactored without changing bytes on the wire.
- Wire names are snake case. A time is an integer of epoch milliseconds in a field ending in `_ms`: `ts_ms`, `expires_at_ms`.
- A wire name is permanent once a release uses it. Do not rename it and do not reuse it for a different meaning.
- Change the protocol by adding: a new optional field with a default, or a new payload type. Removing, renaming or reinterpreting something needs an ADR.
- A new field has a default so that an event from an older bridge still decodes.
- Decoders ignore fields they do not know. A payload type they do not know decodes to `EventPayload.Unknown`, and the app shows it as a plain message. Never make the app throw on a new type.
- Events are stored and replayed, and push payloads are built from them, so an event never carries a token, a credential or a file's full contents unless that is the event's whole purpose.
- Identifiers are opaque strings. The app must not parse them.

### KDoc on protocol types

Write the KDoc for the person deciding whether to send or render a type, not for the compiler.

- Say when the bridge emits it and what the app does with it, for example "carries the whole session" or "the app concatenates deltas in `seq` order".
- State units and time bases on the property: "milliseconds since the Unix epoch".
- Say what null means for each nullable property.
- Do not repeat the property type or name.

## Bridge

- Routes stay thin. Parsing, validation and the work itself live in plain classes that tests can call without a server.
- `GET /health` is the only route that needs no device token. Every route you add behind it requires one.
- Anything that takes a path from a request resolves it against the session's project root and rejects the result if it lands outside.
- A class that holds a secret overrides `toString` so the secret cannot be logged. `BridgeConfig` is the model to follow.
- Settings come from environment variables, read once at start-up. A bad value fails the start with a message that names the variable.

## Tests

Test where a break would hurt and the check is cheap.

| Layer | What to test | How |
|---|---|---|
| `:protocol` | Every payload kind survives a round trip. The wire shape of the kinds the app depends on is pinned. An unknown type, an unknown field and an unknown enum value all decode without error. | Plain JVM unit tests with `protocolJson` |
| Bridge replay and storage | Replaying after a sequence number returns exactly the later events, in order, once. | Unit tests against in-memory SQLite |
| Bridge routes | Status codes, bodies, and rejection of bad paths and missing tokens. | Ktor's `testApplication`, no real port |
| ACP adapter | Mapping of ACP updates to events, including ones it does not recognise. | A small fake agent process that speaks ACP over stdio |
| Android logic | State holders: how events turn into screen state, including unfold and reconnect. | JVM unit tests with a test dispatcher |
| Android UI | Not covered by snapshot tests. Check layouts through previews and on a device or foldable emulator. | Manual |

- A bug fix lands with a test that reproduces the bug at the lowest layer where it shows up.
- No sleeps and no real clocks. Use the test dispatcher and pass time in.
- Test names are plain sentences with underscores, in the style of the existing tests.
- A test that only raises a coverage figure does not belong here.

## Writing for this repo

- Plain punctuation. Use full stops, commas and colons, and reword instead of reaching for a dash.
- Write to the reader as "you", and say "your server" and "your phone".
- Examples and fixtures use neutral project names such as `weather-app`, `docs-site` and `api-server`.
- A `TODO` in code is a missing issue. File the issue and delete the comment.
- Code and comments describe the current design. History lives in git.

## Before you open a pull request

- [ ] No real hostnames, tailnet names, IP addresses or device tokens in fixtures, logs, docs or screenshots. Use `100.64.0.1` style placeholders and `replace-with-a-long-random-value`.
- [ ] No `.env` file and no secrets in the diff.
- [ ] No home-directory paths and no links to private systems.
- [ ] Protocol touched? The change is additive, or an ADR is linked.
- [ ] You ran `./gradlew spotlessApply` and then `./gradlew spotlessCheck detekt test`.
