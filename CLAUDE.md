# Rafiq: rules for coding agents

Rafiq is an open-source (Apache-2.0) native Android app and a JVM bridge server. Together they let you run and steer coding-agent sessions on your own server from your phone, through a chat interface. Everything is Kotlin. The app knows only generic sessions, threads and events. Agent-specific logic lives in adapters on the bridge.

## Rules that hold everywhere

1. [STYLE.md](STYLE.md) wins. When this file and STYLE.md disagree, follow STYLE.md.
2. The ADRs in `docs/adr/` are decisions, not suggestions. Read the relevant ADR before you challenge or work around one. To change a decision, propose a new ADR that supersedes it.
3. This repository is public. No personal paths, private links, internal tracker ids, secrets, or names of people and companies other than the copyright holder `tatawur.ai` and upstream projects we depend on. Use neutral sample names such as `weather-app`.
4. Test at the layer where a break would show up, as laid out in STYLE.md. A bug fix includes a test that reproduces the bug. No UI snapshot tests, no coverage targets.
5. Never commit secrets or `.env` files. Fixtures use obvious placeholders.
6. Protocol changes are additive only without an ADR, and every wire type has `@SerialName`.
7. No `TODO` or `FIXME` comments. File an issue instead.
8. Docs: short sentences, plain punctuation, no em dashes, "your server" and "your phone".
9. Every user-facing change adds a fragment to `changelog.d/`. See [CONTRIBUTING.md](CONTRIBUTING.md).
10. Pull requests open as drafts. CI runs when they are marked ready.

## Commands

Wrap build and test commands in `timeout`, for example `timeout 900 ./gradlew test`.

| Task | Command |
|---|---|
| Format | `./gradlew spotlessApply` |
| Check format, static analysis, tests | `./gradlew spotlessCheck detekt test` |
| Run the bridge | `./gradlew :bridge:run` |
| Build the bridge script | `./gradlew :bridge:installDist` (output in `bridge/build/install/rafiq-bridge/bin/`) |
| Install the debug app | `./gradlew :android:installDebug` |

## Layout

```
rafiq/
├── protocol/      Kotlin module: event model, shared by bridge and app
├── bridge/        JVM app: Ktor, ACP Kotlin SDK, SQLite event log
├── android/       ai.tatawur.rafiq, Jetpack Compose app
├── docs/          architecture.md, adr/
├── changelog.d/   changelog fragments
├── gradle/        version catalog (libs.versions.toml)
└── .github/       issue forms, PR template, workflows
```

Further reading: [docs/architecture.md](docs/architecture.md), [docs/adr/](docs/adr/), [docs/README.md](docs/README.md).
