# 0005. Reconnect by sequence replay

## Status

Accepted, 2026-11-20.

## Context

A phone connection is unreliable. Signal drops, the app is backgrounded, and folding or unfolding the device can interrupt the socket. The app must catch up without losing or duplicating events, and it must work after a full app restart.

## Decision

The bridge stores every event of a session in an append-only log, in SQLite. Each event has a `seq` that increases monotonically within its session. When the app connects, it sends the last `seq` it has stored, using the `after` parameter of `WS /events`. The bridge replays every event after that point and then continues live.

The app keeps its own cache of events (Room), so it can show history offline and knows its last `seq` after a restart.

## Consequences

- Reconnecting is idempotent. A client that asks twice gets the same events.
- The protocol has no terminal state to synchronise, and the bridge needs no per-client bookkeeping.
- The log grows with use, and how long to keep it is not yet decided.
- Events must be self-contained, because they are stored and replayed.

## Alternatives considered

**A terminal-state protocol**, where the client and server synchronise the current screen contents. Tools built for terminals use this approach, and it handles lossy links well. We rejected it because Rafiq is not a terminal. Its data is a sequence of structured events, and a log with sequence numbers is simpler and keeps the history intact.
