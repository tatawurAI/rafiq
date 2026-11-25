#!/bin/sh
# Prints the release notes for one version, in Markdown, for the body of a GitHub Release.
# Usage: scripts/assemble-changelog.sh <X.Y.Z[-prerelease]> [repository root]
#
# Source, in this order:
#   1. The fragments in changelog.d/, grouped by category. This is the normal case on a release tag.
#   2. The "## [X.Y.Z]" section of CHANGELOG.md, for when the release pull request already moved the
#      fragments there (see CONTRIBUTING.md).
# It prints nothing to CHANGELOG.md and deletes nothing. It exits 1 when there is nothing to say, so a
# release never goes out with an empty body.
set -eu

version=${1:?usage: assemble-changelog.sh <version> [root]}
root=${2:-.}
fragments=$root/changelog.d
changelog=$root/CHANGELOG.md

found=0
# Keep a Changelog order.
for category in added changed deprecated removed fixed security; do
    # A glob that matches nothing stays literal, so test each name before using it.
    items=""
    for file in "$fragments"/*."$category".md; do
        [ -f "$file" ] || continue
        # One bullet per fragment. Newlines inside a fragment become spaces.
        line=$(tr '\n' ' ' <"$file" | sed 's/[[:space:]][[:space:]]*/ /g; s/^ //; s/ $//')
        [ -n "$line" ] || continue
        items="$items- $line
"
    done
    if [ -n "$items" ]; then
        title=$(printf '%s' "$category" | awk '{ print toupper(substr($0, 1, 1)) substr($0, 2) }')
        [ "$found" -eq 0 ] || printf '\n'
        printf '### %s\n\n%s' "$title" "$items"
        found=1
    fi
done

if [ "$found" -eq 1 ]; then
    exit 0
fi

if [ -f "$changelog" ]; then
    section=$(awk -v heading="## [$version]" '
        index($0, heading) == 1 { inside = 1; next }
        inside && /^## \[/ { exit }
        inside { print }
    ' "$changelog" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}' | sed '/./,$!d')
    if [ -n "$section" ]; then
        printf '%s\n' "$section"
        exit 0
    fi
fi

echo "assemble-changelog: no fragments in changelog.d and no '## [$version]' section in CHANGELOG.md." >&2
exit 1
