# 0001. Record architecture decisions

## Status

Accepted, 2026-11-20.

## Context

Rafiq is an open-source project that contributors join at different times. The reasons behind a design choice are easy to lose, and a newcomer who cannot see them will either repeat an old debate or undo a decision by accident.

## Decision

We record each significant architecture decision as an ADR in `docs/adr/`, in the format described in [README.md](README.md). Accepted ADRs are immutable. A changed decision gets a new ADR that supersedes the old one.

A decision is significant if a future contributor would reasonably ask "why is it like this?", or if reversing it would be costly.

## Consequences

- Reasons survive turnover and are reviewable in a pull request.
- Contributors can challenge a decision with evidence by proposing a superseding ADR.
- Writing an ADR costs time. We accept that for decisions that are expensive to reverse, and we do not write one for routine choices.
