#!/bin/sh
# Fills in packaging/homebrew/rafiq-bridge.rb.in and prints the formula on stdout.
# Usage: scripts/render-homebrew-formula.sh <version> <SHA256SUMS file>
# It fails when the checksums file lacks one of the four bridge archives, so a formula never ships with a
# blank or stale hash.
set -eu

version=${1:?usage: render-homebrew-formula.sh <version> <SHA256SUMS>}
sums=${2:?usage: render-homebrew-formula.sh <version> <SHA256SUMS>}
here=$(cd "$(dirname "$0")" && pwd)
template=$here/../packaging/homebrew/rafiq-bridge.rb.in

hash_for() {
    # SHA256SUMS lines are "<hash>  <file>". Match the file name exactly.
    awk -v name="rafiq-bridge-$version-$1.tar.gz" '$2 == name { print $1 }' "$sums"
}

out=$(cat "$template")
out=$(printf '%s\n' "$out" | sed "s/@VERSION@/$version/g")
for platform in macos-arm64 macos-x64 linux-arm64 linux-x64; do
    hash=$(hash_for "$platform")
    case $hash in
        [0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ;;
        *)
            echo "render-homebrew-formula: no checksum for rafiq-bridge-$version-$platform.tar.gz in $sums" >&2
            exit 1
            ;;
    esac
    [ "${#hash}" -eq 64 ] || { echo "render-homebrew-formula: bad checksum length for $platform" >&2; exit 1; }
    token=$(printf '%s' "$platform" | tr 'a-z-' 'A-Z_')
    out=$(printf '%s\n' "$out" | sed "s/@SHA256_$token@/$hash/g")
done
printf '%s\n' "$out"
