#!/usr/bin/env bash
# Build the debug app, install it on the phone over wireless debugging and launch it.
# Installs for the phone's current user, so it works from any profile.
# Extra arguments go to Gradle, e.g. ./run-on-phone.sh -Pplatform.baseUrl=https://…
set -euo pipefail
cd "$(dirname "$0")"

PACKAGE=app.simpletemplate.debug
ACTIVITY=app.simpletemplate.android.MainActivity
APK=androidApp/build/outputs/apk/debug/androidApp-debug.apk

sdk_dir=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)
ADB=${ADB:-${sdk_dir:-${ANDROID_HOME:-$HOME/Android/Sdk}}/platform-tools/adb}
[[ -x $ADB ]] || { echo "adb not found at $ADB (set ADB=…)" >&2; exit 1; }

# A stale wireless connection still lists as "device" but hangs, so probe with a timeout.
adb_ok() { timeout 5 "$ADB" -s "$1" shell true >/dev/null 2>&1; }

find_phone() {
    local serial addr
    for serial in $("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
        if adb_ok "$serial"; then echo "$serial"; return; fi
        "$ADB" disconnect "$serial" >/dev/null 2>&1 || true
    done
    # The phone's IP changes with Wi-Fi network and with GrapheneOS profile; mDNS has the current one.
    for addr in $(timeout 10 "$ADB" mdns services | awk '$2 ~ /_adb-tls-connect/ { print $3 }'); do
        timeout 10 "$ADB" connect "$addr" >/dev/null 2>&1 || continue
        if adb_ok "$addr"; then echo "$addr"; return; fi
    done
    return 1
}

serial=$(find_phone) || {
    echo "No phone found. Turn on Wireless debugging (pair once first: adb pair IP:PORT CODE)." >&2
    exit 1
}
user=$(timeout 10 "$ADB" -s "$serial" shell am get-current-user | tr -d '\r')
echo "Phone $serial, user $user"

./gradlew :androidApp:assembleDebug "$@"
timeout 180 "$ADB" -s "$serial" install -r --user "$user" "$APK"
timeout 10 "$ADB" -s "$serial" shell am start --user "$user" -n "$PACKAGE/$ACTIVITY" >/dev/null
echo "Launched $PACKAGE"
