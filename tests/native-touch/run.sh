#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir -p "$out/com/limelight/nvstream/jni"
# Use the real protocol constants without loading JNI in the host JVM.
python3 - "$out/com/limelight/nvstream/jni/MoonBridge.java" <<'PY'
import re, sys
s = open('app/src/main/java/com/limelight/nvstream/jni/MoonBridge.java').read()
constants = re.findall(r'public static final (?:byte|int) LI_(?:TOUCH_EVENT_\w+|ERR_UNSUPPORTED)\s*=\s*[^;]+;', s)
assert len(constants) == 9, constants
open(sys.argv[1], 'w').write('package com.limelight.nvstream.jni; public class MoonBridge {\n' + '\n'.join(constants) + '\n}')
PY
javac -d "$out" "$out/com/limelight/nvstream/jni/MoonBridge.java" \
    app/src/main/java/com/limelight/binding/input/touch/NativeTouchState.java \
    tests/native-touch/NativeTouchStateTest.java
java -cp "$out" NativeTouchStateTest
