# 0007. Native Compose for fold and cover-screen APIs

## Status

Accepted, 2026-11-20.

## Context

Rafiq is tuned for a foldable phone with a cover screen. Its value depends on three platform capabilities: reading the fold posture so Flex Mode can split the layout, showing cards and widgets on the cover screen, and offering approve and deny actions directly in notifications. It also needs the Keystore for the device token and BiometricPrompt for approvals.

## Decision

The app is a native Android app written in Kotlin with Jetpack Compose. It uses Jetpack WindowManager for fold posture. It is installed directly, not through the Play Store.

## Consequences

- Direct access to fold, cover-screen, notification, Keystore and biometric APIs, with no bridge layer between us and the platform.
- Compose and the shared Kotlin protocol module fit together (see [ADR 0003](0003-kotlin-on-both-ends-with-a-shared-protocol-module.md)).
- The app runs on Android only. iOS is a non-goal for v1.
- Installing directly means contributors and users sideload builds, and there is no store-managed update path.

## Alternatives considered

**Cross-platform frameworks**, such as Flutter or React Native. They would allow an iOS version from one codebase. We rejected them because the fold posture, cover widgets and notification actions need platform APIs, and reaching them through a framework adds plugin code and lags behind platform releases. Those capabilities are the product, so we want the shortest path to them.
