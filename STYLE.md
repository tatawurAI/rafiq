# Style guide

This guide covers how code, comments, protocol types, tests and documentation are written in Rafiq. Tools enforce the mechanical parts. The rest is judgement, and this file is the reference when reviewers disagree.

## Kotlin

### Formatting and static analysis

- We follow the official [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- ktlint, run through Spotless, owns formatting. Run `./gradlew spotlessApply` and do not argue with the result.
- detekt owns static analysis. Fix findings. Suppress one only with a `@Suppress` that has a comment saying why.
- See [ADR 0008](docs/adr/0008-formatting-by-ktlint-and-detekt.md) for the reasoning.

### Naming

- Types are `UpperCamelCase`. Functions and properties are `lowerCamelCase`. Constants are `SCREAMING_SNAKE_CASE`.
- Name things for what they are in the domain: `Session`, `Thread`, `ApprovalRequest`. Avoid `Manager`, `Helper` and `Util`.
- Boolean names read as a question: `isStreaming`, `hasPendingApproval`.
- Put units in names when the type does not carry them: `timeoutMs`, `widthDp`. Prefer `kotlin.time.Duration` where you can.
- A file holding one top-level type is named after it. Group small related types in a file named for the group.

### Nullability

- Make types non-null unless absence is a real state in the domain.
- Never use `!!` in production code. Handle the null, or restructure so it cannot occur.
- Model "unknown" as a sealed type or a default, not as a null with a hidden meaning.
- Platform types from Java APIs get an explicit nullable or non-null type at the boundary.

### Coroutines and Flow

- Every coroutine has an owner with a defined lifetime. Do not use `GlobalScope`.
- Inject dispatchers. Do not hard-code `Dispatchers.IO` inside a class that tests need to control.
- Suspending functions are main-safe: they switch dispatcher themselves when they block.
- Do not swallow `CancellationException`. Rethrow it.
- Expose `Flow` or `StateFlow` from a class, and keep the mutable `MutableStateFlow` private.
- Collect flows in the UI with lifecycle awareness (`collectAsStateWithLifecycle`).
- Use `StateFlow` for state that always has a current value, and `SharedFlow` or a channel for one-off events.

### Compose

- Composables are stateless where they can be. They take state and callbacks as parameters.
- Hoist state to the lowest common caller that needs it. Screens read from a view model. Components never do.
- Give every reusable composable a `modifier: Modifier = Modifier` parameter, as the first optional parameter.
- Use theme tokens for colour, type and spacing. Never write a hex colour, a raw `sp` size or a font family inside a composable.
- Touch targets are at least 44dp.
- Every reusable composable has a `@Preview`. Include the states that differ: empty, loading, error, long text.
- Do not read from a repository or start a coroutine inside a composable body. Use effects (`LaunchedEffect`) or the view model.

## Comments and KDoc

- KDoc on public API says what the thing is for and why it exists, plus any contract the signature cannot show. Do not restate the signature.
- Inline comments explain why: an invariant, a unit, a platform quirk, a rejected alternative. If a comment only repeats the code, delete it.
- No `TODO` or `FIXME` comments. File an issue and, if it helps, link it from the pull request.
- No migration history in code ("was X before", "changed from Y"). Version control keeps history.
- Write comments in complete sentences, in plain English.

Good:

```kotlin
/** Sequence number of the last event the phone has stored. The bridge replays everything after it. */
val lastSeenSeq: Long

// OkHttp reports a closed socket on the reader thread, so hop to the main scope before touching state.
```

Not useful:

```kotlin
// Set the title
fun setTitle(title: String)
```

## Protocol rules

The `:protocol` module defines the wire contract between the bridge and the app. See [docs/architecture.md](docs/architecture.md#event-model).

- Every serialisable wire type and every enum value carries an explicit `@SerialName`. Renaming a Kotlin class or property must never change the wire format.
- Changes are additive only: new optional fields, new event types. Removing a field, renaming one or changing its meaning needs an ADR.
- New fields have defaults, so an older peer can still decode newer messages and a newer peer can decode older ones.
- Decoders ignore unknown fields.
- An event type the app does not know falls back to a plain message, so a new agent feature degrades to readable text and never to a crash.
- Every event carries `session_id`, `seq` and `ts`. `seq` is monotonic per session.

## Testing

Tests earn their place. Each test protects a behaviour someone would notice breaking, at the cheapest level that can catch it.

- Test behaviour through the public API, not private structure.
- Prefer a unit test, then an integration test, then a UI test. Move up only when the cheaper level cannot see the failure.
- A bug fix has a test that fails without the fix. Confirm that it does.
- Do not chase coverage. A test that exists to raise a percentage is deleted in review.
- Do not test the framework, generated code or simple data classes.
- Use real collaborators over mocks where they are cheap, such as an in-memory SQLite database or a fake ACP agent process. Mock at the system boundary only.
- Tests are deterministic. No sleeps, no wall-clock time, no real network. Use the test dispatcher and a fake clock.
- Name a test for the behaviour: `replays_events_after_last_seen_seq`.
- Keep the suite fast. A slow test needs a reason in a comment.

## Docs writing

- Use short sentences and plain punctuation. No em dashes: use a full stop, a colon or a comma, or reword the sentence.
- Write "your server" and "your phone". Address the reader as "you".
- Expand an acronym the first time it appears in a file, for example "Agent Client Protocol (ACP)".
- Front-load the point. Put the key fact in the first sentence of a section.
- Use numbered lists for steps and bullets for unordered items. Use a table to compare things along the same dimensions.
- Never count things in prose that will drift, such as "the six screens" or "twelve endpoints". Name them, or point to the source.
- Put commands and file paths in code formatting. Make examples copy-pasteable.
- Use neutral names in examples: `weather-app`, `docs-site`, `api-server`.
- Pick one term for one concept and keep it. Do not alternate between "session" and "conversation".
- Docs describe the present. Plans, risks and discussion go in issues.

## Open-source hygiene

This repository is public. Everything committed to it is read by strangers.

- No personal paths, such as a home directory, in code, docs, scripts or fixtures.
- No links to private systems, and no internal tracker ids.
- Do not name people, companies or other products as the reason for a decision. A decision is justified by its trade-offs. The exceptions are `tatawur.ai` as the copyright holder, and the upstream projects and licences we depend on.
- No secrets in the repository, including in tests and fixtures. Fixtures use obvious placeholders such as `test-token-0000`.
- `.env` files are never committed. Configuration examples use placeholder values.
- Sample data uses neutral names and example domains.
