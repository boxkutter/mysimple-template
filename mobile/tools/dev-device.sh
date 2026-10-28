#!/usr/bin/env bash
# Points an emulator (or a rooted test phone) at the My Simple ISO development stack, for debug
# builds: the device's port 443 reaches this machine's Traefik, and Chrome (which shows the
# sign-in page in a Custom Tab) accepts the stack's own certificates. Run again after the
# emulator restarts. Usage: tools/dev-device.sh [path to the iso repository, default ../../iso]
set -euo pipefail
ISO=${1:-$(cd "$(dirname "$0")/../../.." && pwd)/iso}
ADB=${ADB:-adb}
CERTS="$ISO/deploy/traefik/certs"
[ -f "$CERTS/ca.crt" ] || { echo "No $CERTS/ca.crt: start the iso dev stack first" >&2; exit 1; }
$ADB root >/dev/null 2>&1 || true
sleep 2; timeout 60 $ADB wait-for-device
$ADB reverse tcp:443 tcp:443
spki() { openssl x509 -in "$1" -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64; }
flags="_ --ignore-certificate-errors-spki-list=$(spki "$CERTS/wildcard.crt"),$(spki "$CERTS/ca.crt") --disable-fre --no-default-browser-check --no-first-run"
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
echo "$flags" > "$tmp"
for p in /data/local/chrome-command-line /data/local/tmp/chrome-command-line; do
  $ADB push "$tmp" "$p" >/dev/null && $ADB shell chmod 644 "$p"
done
$ADB shell am force-stop com.android.chrome
echo "Device ready: https://*.mysimple.localhost reaches this machine."
