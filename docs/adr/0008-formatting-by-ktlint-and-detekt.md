# 0008. Formatting by ktlint and detekt

## Status

Accepted, 2026-11-20.

## Context

Style arguments in review waste time and hide real problems. A project with human and agent contributors needs formatting that a tool decides, and static checks that catch common defects before review.

## Decision

- ktlint, run through the Spotless Gradle plugin, formats all Kotlin code to the official Kotlin coding conventions. `./gradlew spotlessApply` fixes formatting and `./gradlew spotlessCheck` verifies it.
- detekt performs static analysis. Findings fail the build.
- Rules that the tools cannot express are written in [STYLE.md](../../STYLE.md).

## Consequences

- Formatting is never discussed in review. A contributor runs one command.
- Defect-prone patterns fail CI early.
- Contributors may disagree with a particular rule. They change it by proposing a configuration change with reasons, not by suppressing it locally without a comment.
- Two tools means two configurations to keep up to date.
