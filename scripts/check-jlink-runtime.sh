#!/bin/sh
# Checks that a jlink runtime has what push needs, without using the network:
#   - java.net.http and jdk.crypto.ec are in the image,
#   - the CA certificate store (cacerts) is there and holds certificates, so HTTPS to a push service verifies,
#   - the runtime can make an EC P-256 key, the curve Web Push uses.
# Usage: scripts/check-jlink-runtime.sh <runtime folder>
set -eu

runtime=${1:?usage: check-jlink-runtime.sh <runtime folder>}
work=$(mktemp -d "${TMPDIR:-/tmp}/rafiq-runtime.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
pass=$(awk 'BEGIN { srand(); printf "rt%d%d", rand() * 1000000, rand() * 1000000 }')

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

modules=$("$runtime/bin/java" --list-modules)
for module in java.net.http jdk.crypto.ec; do
    printf '%s\n' "$modules" | grep -q "^$module@" || fail "the runtime lacks module $module"
done

# cacerts ships with a well known password. Listing it proves it is present and readable.
count=$("$runtime/bin/keytool" -list -cacerts -storepass changeit 2>/dev/null | grep -c trustedCertEntry || true)
[ "$count" -ge 20 ] || fail "cacerts holds $count certificates, expected at least 20"

"$runtime/bin/keytool" -genkeypair -alias probe -keyalg EC -groupname secp256r1 -dname CN=probe \
    -keystore "$work/probe.p12" -storetype PKCS12 -storepass "$pass" >/dev/null 2>&1 ||
    fail "the runtime cannot make an EC P-256 key"

echo "OK: runtime has java.net.http, jdk.crypto.ec, $count CA certificates and EC P-256"
