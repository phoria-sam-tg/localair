#!/bin/sh
# Clones the third-party C libraries and applies minimal patches for
# AirPlay video streaming. Re-runnable; skips existing clones.
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$ROOT/third_party"

clone_if_missing() {
    dest="$1"; url="$2"
    if [ -d "$dest" ]; then
        echo "skip $dest (already cloned)"
    else
        git clone --depth 1 "$url" "$dest"
    fi
}

clone_if_missing "$ROOT/third_party/RPiPlay" https://github.com/FD-/RPiPlay
clone_if_missing "$ROOT/third_party/libplist" https://github.com/libimobiledevice/libplist

#
# Patch RPiPlay's raop.c to forward unhandled HTTP requests to our
# airplay_video handler (/play, /stop, /rate, /playback-info).
#
RAOP_C="$ROOT/third_party/RPiPlay/lib/raop.c"
if ! grep -q "airplay_video_handle" "$RAOP_C" 2>/dev/null; then
    echo "patching raop.c for AirPlay video streaming..."

    # Add include after raop_handlers.h
    sed -i '' '/#include "raop_handlers.h"/a\
#include "airplay_video.h"
' "$RAOP_C"

    # Replace the closing brace before 'if (handler != NULL)' with
    # an else-clause that delegates to our video handler.
    python3 -c "
import re
with open('$RAOP_C', 'r') as f: src = f.read()
old = '''    }
    if (handler != NULL) {'''
new = '''    } else {
        int datalen_v = 0;
        const char *data_v = http_request_get_data(request, &datalen_v);
        airplay_video_handle(method, url, data_v, datalen_v, &response_data, &response_datalen);
    }
    if (handler != NULL) {'''
# Only replace the LAST occurrence (the one after TEARDOWN)
idx = src.rfind(old)
if idx >= 0:
    src = src[:idx] + new + src[idx+len(old):]
    with open('$RAOP_C', 'w') as f: f.write(src)
    print('patched')
else:
    print('WARNING: patch target not found in raop.c')
"
    echo "raop.c patched"
else
    echo "raop.c already patched"
fi

echo
echo "deps ready. next: ./gradlew :app:assembleDebug"
