# 0009. Tests earn their place

## Status

Accepted, 2026-11-20.

## Context

Tests have a cost: they take time to write, run and maintain, and a flaky or slow suite teaches people to ignore failures. A coverage target tends to produce tests that exercise code without protecting behaviour. We want a suite small enough to stay fast and trusted.

## Decision

Every test must protect a behaviour someone would notice breaking, and it runs at the cheapest level that can catch the failure: unit before integration before UI. A bug fix includes a test that fails without the fix. We do not set coverage targets, and we delete tests that only raise a number. Tests are deterministic, with no sleeps, wall-clock time or real network.

The detailed rules are in [STYLE.md](../../STYLE.md#testing).

## Consequences

- The suite stays fast, so contributors run it before every push.
- A passing suite is a meaningful signal, and a failing test is worth reading.
- Some code has no direct test. That is acceptable when nobody would notice it breaking, and reviewers decide case by case.
- Reviewers must judge whether a test earns its place, which takes more thought than checking a percentage.
