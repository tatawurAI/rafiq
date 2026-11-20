# 0004. The app knows only generic sessions, threads and events

## Status

Accepted, 2026-11-20.

## Context

Rafiq starts with Claude Code and must work with other coding agents later. Agents differ in their tools, their approval prompts, their sign-in flows and their notion of subagents. If the app learns those differences, every new agent means an app release, and the app fills up with special cases.

## Decision

The app understands only the generic event model: sessions, threads, messages, tool calls, approval requests, status, auth requests, previews and file changes. Everything that is specific to one agent lives in an adapter on the bridge, which translates the agent's output into those events.

Output that does not fit a known event type falls back to a plain message. See [architecture.md](../architecture.md#event-model).

## Consequences

- Adding an agent that speaks ACP needs no app change. This is the milestone M6 test of the design.
- The app stays small, and its UI work is about presentation and fold modes, not agent quirks.
- Features unique to one agent reach the phone as plain text until we add a generic event for them. That is a deliberate trade for a stable app.
- The generic model has to be rich enough for real agents. When it is not, we extend it additively.
