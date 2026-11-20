# 0003. Kotlin on both ends with a shared protocol module

## Status

Accepted, 2026-11-20.

## Context

The bridge and the app exchange a typed event stream. The event definitions must stay identical on both ends. The bridge also has to speak the Agent Client Protocol (ACP) to coding agents.

The official ACP Kotlin SDK, [`agentclientprotocol/kotlin-sdk`](https://github.com/agentclientprotocol/kotlin-sdk), makes Kotlin a first-class choice for the bridge. The app must be Kotlin anyway, for native Android (see [ADR 0007](0007-native-compose-for-fold-and-cover-screen-apis.md)).

## Decision

The bridge is written in Kotlin on the JVM, using Ktor and the ACP Kotlin SDK. The event model lives in a `:protocol` Kotlin module, serialised with kotlinx.serialization, and both the bridge and the app depend on it.

The toolchain is concrete: the bridge, the protocol module and the app all target JVM 21, and Android Gradle Plugin (AGP) 9 compiles the Kotlin in the app. Versions are pinned in `gradle/libs.versions.toml`.

## Consequences

- One language, one set of coding rules, and one definition of each event. The compiler catches a protocol mismatch.
- Contributors can move between bridge and app code without changing languages.
- The bridge needs a JVM on your server, which is heavier than a small native binary or a Node process.
- Anything that exists only as a TypeScript or Python library is not directly usable. The Claude Agent SDK is one example: it is available in TypeScript and Python, not Kotlin. We expect ACP to cover what we need. If the subagent spike in milestone M1 shows ACP cannot expose subagents as threads, using that SDK from a sidecar process would be a separate decision with its own ADR.

## Alternatives considered

**A TypeScript bridge with a schema generated for the app.** The TypeScript ecosystem has more agent tooling, and a generated schema (for example JSON Schema) can describe the protocol. We rejected it because it needs two toolchains, two sets of dependencies and a code-generation step, and the generated types can drift from what the bridge actually sends. A shared Kotlin module removes that class of bug.
