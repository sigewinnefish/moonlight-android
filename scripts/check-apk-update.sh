#!/usr/bin/env bash
# Checks package/certificate identity, not Android version policy or device compatibility.
set -euo pipefail
if [ "$#" -ne 2 ]; then echo 'Usage: check-apk-update.sh installed-baseline.apk candidate.apk' >&2; exit 2; fi
tools="${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/36.0.0"
identity() {
  "$tools/apksigner" verify --print-certs "$1" | sed -n '/^Signer #[0-9]* certificate SHA-256 digest:/p'
}
package_id() {
  "$tools/aapt" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"
}
old_package=$(package_id "$1")
new_package=$(package_id "$2")
old_cert=$(identity "$1")
new_cert=$(identity "$2")
if [ -z "$old_package" ] || [ -z "$old_cert" ]; then echo 'Unable to inspect baseline APK' >&2; exit 2; fi
if [ "$old_package" != "$new_package" ]; then echo "Not an update: different packages ($old_package / $new_package)" >&2; exit 1; fi
if [ "$old_cert" != "$new_cert" ]; then echo 'UPDATE REJECTED: signing certificates differ; preserve the installed app.' >&2; exit 1; fi
echo 'Package and signing certificate match. Android version/device checks still apply.'
