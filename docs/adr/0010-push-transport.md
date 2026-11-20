# 0010. Push transport

## Status

Proposed, 2026-11-20.

## Context

When the app is closed, the bridge must still be able to tell you that an agent needs you. Android apps normally receive such pings through push. The app then opens its WebSocket again on a tap and replays what it missed (see [ADR 0005](0005-reconnect-by-sequence-replay.md)).

Push payloads carry ids and short status only, so a push service never sees file contents or command output.

Two transports are candidates.

**Firebase Cloud Messaging (FCM).**

- High-priority messages are the standard way to wake a device, and they interact well with Android battery optimisation.
- It needs a Firebase project and a server key, and the bridge talks to Google's service. Notification metadata passes through that service.
- It is the least setup for you, since nothing extra runs on your server.

**Self-hosted ntfy.**

- You run an [ntfy](https://ntfy.sh) server, or use one you trust, and no third party sees the pings.
- It keeps the project's private-by-default stance, and it needs no Google account for the bridge.
- Reliable delivery on a phone with aggressive battery optimisation needs either a persistent connection from the ntfy app or a battery exemption, and delivery can be later than with FCM.
- It is one more service for you to run and keep reachable over Tailscale.

## Decision

Pending. We will measure delivery delay and battery impact for both on a real device, then record the choice by changing this ADR's status to Accepted or by superseding it. The bridge's push sender should sit behind a small interface so the transport can change without touching the rest of the bridge.

## Consequences

To be completed when the decision is made. Whichever transport wins, notification actions for approve and deny must work without opening the app.
