# Contributing to Rafiq

Thanks for helping. This guide covers how to build the project, how a change moves from branch to merge, and what we expect in a pull request. By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Contributions are licensed under Apache-2.0, like the rest of the repository.

## Development setup

You need:

- JDK 21
- The Android SDK (command-line tools or Android Studio), with `ANDROID_HOME` or `local.properties` pointing at it
- A phone or emulator for the app

Clone the repository and use the Gradle wrapper for everything. Do not install Gradle separately.

| Task | Command |
|---|---|
| Format the code | `./gradlew spotlessApply` |
| Check format, static analysis and tests | `./gradlew spotlessCheck detekt test` |
| Run the bridge | `./gradlew :bridge:run` |
| Install the debug app on a connected device | `./gradlew :android:installDebug` |

Run `./gradlew spotlessApply` before you commit. Run the check line before you mark a pull request ready.

## Branches and pull requests

1. Open or find an issue that describes the change. For anything larger than a small fix, agree on the approach there first.
2. Branch from `main`. Use a short, descriptive name such as `fix-replay-gap` or `add-diff-viewer`.
3. Keep each pull request to one change. Small pull requests get reviewed faster.
4. Open the pull request as a draft. CI skips drafts, so you can push freely.
5. When it is ready, mark it ready for review. CI runs at that point and must pass.
6. Fill in the pull request template: what changed, why, how you tested it.

## Changelog fragments

Rafiq keeps its changelog in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format, assembled from fragments so pull requests never conflict on `CHANGELOG.md`.

Add one file to `changelog.d/` per user-facing change:

- Name: `<slug>.<category>.md`, for example `voice-reply.added.md`. The slug is a PR number or short name, using lowercase letters, digits and hyphens.
- Category: one of `added`, `changed`, `deprecated`, `removed`, `fixed`, `security`.
- Content: one sentence a user would understand, in the past tense or as a plain statement. Describe the effect, not the code.

Example, `changelog.d/replay-gap.fixed.md`:

```markdown
Reconnecting after a dropped signal no longer skips events from the gap.
```

If the change has no effect on users (CI, refactors, docs, tests), add the `skip-changelog` label to the pull request instead. The `changelog` CI check enforces one or the other.

To cut a release, maintainers run `./gradlew assembleChangelog -Pversion=X.Y.Z` (optionally `-PreleaseDate=YYYY-MM-DD`). It moves the fragments into a new `## [X.Y.Z]` section of `CHANGELOG.md`, grouped by category, and deletes them. Because the release pull request deletes fragments instead of adding one, it carries the `skip-changelog` label.

## Architecture decisions

If your change alters something a future contributor would need an explanation for, such as the wire protocol, a major dependency, the network model or a toolchain, propose an Architecture Decision Record (ADR).

1. Copy the format described in [docs/adr/README.md](docs/adr/README.md).
2. Use the next free number and a short, hyphenated title: `docs/adr/0011-your-decision.md`.
3. Set the status to `Proposed` and open it as a pull request, ideally before the code.
4. When it is accepted, change the status to `Accepted` and the date.

Accepted ADRs are not edited. To change a decision, write a new ADR that supersedes the old one. Read the existing ADRs before you challenge a decision.

## Tests

Tests earn their place. Each test protects a behaviour someone would notice breaking, at the cheapest level that can catch it. See [STYLE.md](STYLE.md#testing) for the rules.

In short:

- A bug fix comes with a test that fails without the fix.
- Prefer a plain unit test over an integration test, and an integration test over a UI test.
- Do not add tests to raise a coverage number.
- No sleeps. Control time and concurrency with the test dispatcher.

## Style and documentation

[STYLE.md](STYLE.md) is the style guide, and `spotlessCheck` and `detekt` enforce its mechanical parts. Update [docs/architecture.md](docs/architecture.md) when you change the behaviour it describes.

## Security

Do not report vulnerabilities in public issues or pull requests. See [SECURITY.md](SECURITY.md).
