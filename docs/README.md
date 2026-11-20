# Documentation guide

Use this page to decide where a piece of writing belongs.

| You want to write about | Put it in |
|---|---|
| A decision with trade-offs that a future contributor needs explained | An [ADR](adr/README.md) |
| How the system works today: components, protocol, API, behaviour | A reference page in `docs/`, starting with [architecture.md](architecture.md) |
| A plan, a risk, an open question, a task or a bug | A GitHub issue or discussion |
| How to build, test or contribute | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| How code, comments and docs are written | [STYLE.md](../STYLE.md) |
| A change users will notice | A fragment in `changelog.d/` |

Rules:

- Reference pages describe the present. When the code changes, the page changes in the same pull request.
- ADRs record the past. Accepted ADRs are never edited. Supersede them with a new one.
- Plans, risks and status do not go in `docs/`. They go stale, and issues are where they can be tracked and closed.
- Follow the writing rules in [STYLE.md](../STYLE.md#docs-writing).
