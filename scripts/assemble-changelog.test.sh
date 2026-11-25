#!/bin/sh
# Checks scripts/assemble-changelog.sh on throwaway trees. Run it from anywhere: scripts/assemble-changelog.test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
script=$here/assemble-changelog.sh
work=$(mktemp -d "${TMPDIR:-/tmp}/rafiq-changelog-test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# Fragments: grouped in Keep a Changelog order, one bullet each, a multi-line fragment joined to one line.
mkdir -p "$work/a/changelog.d"
printf 'Fixes a crash.\n' >"$work/a/changelog.d/crash.fixed.md"
printf 'Adds a thing\nover two lines.\n' >"$work/a/changelog.d/thing.added.md"
printf 'Closes a hole.\n' >"$work/a/changelog.d/hole.security.md"
: >"$work/a/changelog.d/.gitkeep"
out=$("$script" 1.2.3 "$work/a")
expected="### Added

- Adds a thing over two lines.

### Fixed

- Fixes a crash.

### Security

- Closes a hole."
[ "$out" = "$expected" ] || fail "fragments were not grouped and ordered as expected. Got: $out"

# No fragments: take the version's section from CHANGELOG.md, stop at the next version.
mkdir -p "$work/b/changelog.d"
cat >"$work/b/CHANGELOG.md" <<'MD'
## [Unreleased]

## [1.2.3] - 2026-01-01

### Added

- Adds a thing.

## [1.2.2] - 2025-12-01

### Fixed

- Old fix.
MD
out=$("$script" 1.2.3 "$work/b")
expected="### Added

- Adds a thing."
[ "$out" = "$expected" ] || fail "section was not extracted as expected. Got: $out"

# Nothing to say: it must fail, so a release never gets an empty body.
if "$script" 9.9.9 "$work/b" >/dev/null 2>&1; then
    fail "an unknown version with no fragments must exit non-zero"
fi

echo "OK"
