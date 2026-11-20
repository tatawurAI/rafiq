# 0009. Tests earn their place

## Status

Accepted, 2026-11-20.

## Context

Rafiq has two failure modes that users feel. The first is a protocol mismatch between the bridge and an app that was installed weeks earlier. The second is a reconnect that loses or repeats events. Both are cheap to catch with plain JVM tests. By contrast, the Compose UI changes shape constantly while the design settles, and snapshot tests of it would break on every adjustment without catching anything a person would not see in a preview.

A suite that people trust has to be fast and deterministic, and every test has to be worth its running time.

## Decision

We test the layers where a break would hurt and the check is cheap:

- The `:protocol` module: round trips for every payload kind, pinned wire shapes, and the fallbacks for unknown types, fields and enum values.
- Replay and storage on the bridge, against in-memory SQLite.
- Bridge routes through Ktor's test host, with no real port.
- The ACP adapter, against a small fake agent process.
- Android state holders on the JVM with a test dispatcher.

We do not write UI snapshot tests, and we do not set coverage targets. A bug fix includes a test that reproduces the bug at the lowest layer where it shows. Tests use no sleeps and no real network. The details are in [STYLE.md](../../STYLE.md#tests).

## Consequences

- The suite stays fast enough to run before every push, so a red build means something.
- The compatibility promise in [ADR 0005](0005-reconnect-by-sequence-replay.md) and the additive-only protocol rule are backed by tests and not just convention.
- Screen layout, including fold postures, is checked by eye on previews and a device. A visual regression can slip through, and we accept that over maintaining brittle snapshots.
- Some code has no test of its own. Reviewers judge case by case whether a break would be noticed.
