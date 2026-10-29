#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cc -std=c11 -Wall -Wextra -Werror -pthread -I app/src/main/jni/moonlight-core \
  app/src/main/jni/moonlight-core/touch_diagnostics.c tests/touch-transport/test.c -o "$out/test"
"$out/test"
