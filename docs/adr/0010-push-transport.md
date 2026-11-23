# 0010. Push transport

## Status

Accepted, 2026-11-23. Proposed 2026-11-20.

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

Rafiq uses [UnifiedPush](https://unifiedpush.org), with the ntfy app as the distributor we document. There is no FCM and no push service run by the Rafiq project.

The deciding fact is that everyone runs their own bridge. FCM can only be sent with the server key of the Firebase project the app is registered with. Every bridge would have to hold that key, or the project would have to run a relay that holds it for everyone. The first leaks a shared secret to every install. The second puts a service we run between your server and your phone, which this project avoids.

With UnifiedPush:

- The app asks the distributor on the phone for an endpoint URL and sends it to the bridge with `PUT /devices/me/push`, together with a Web Push key pair. The bridge keeps it per device and deletes it when the device is revoked.
- The bridge posts each push to that endpoint, encrypted with Web Push message encryption ([RFC 8291](https://www.rfc-editor.org/rfc/rfc8291)), so the push server sees neither the ids nor the kind.
- A payload holds ids and a kind only. The app fetches what it shows from the bridge over the tailnet.
- You can use the public ntfy.sh server or run your own. Either works without an account.
- The bridge's sender sits behind a small interface, so another transport can be added later without touching the rest of the bridge.

## Consequences

- Push needs a UnifiedPush distributor on the phone. The app explains how to install ntfy when none is found, and Rafiq works without push.
- On phones with aggressive battery optimisation the distributor needs a battery exemption. The app walks you through it.
- Delivery can be later than with FCM. We accept that for not holding a shared key or running a relay.
- The bridge now makes outbound HTTPS requests to the endpoint a paired phone registered. Only `https://` endpoints are accepted, redirects are not followed, and requests have a short timeout and a size cap.
- Notification actions for approve and deny work without opening the app: deny straight from the notification, and approve after you unlock the phone, so command text never shows on the lock screen.
