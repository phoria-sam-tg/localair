#!/bin/sh
# Clones third-party deps and applies patches for AirPlay video streaming
# with encrypted HTTP channel (ChaCha20-Poly1305). Re-runnable.
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$ROOT/third_party"

clone_if_missing() {
    dest="$1"; url="$2"
    if [ -d "$dest" ]; then echo "skip $dest (already cloned)"
    else git clone --depth 1 "$url" "$dest"; fi
}

clone_if_missing "$ROOT/third_party/RPiPlay" https://github.com/FD-/RPiPlay
clone_if_missing "$ROOT/third_party/libplist" https://github.com/libimobiledevice/libplist

# ─── Patch RPiPlay ────────────────────────────────────────────────────
RAOP_C="$ROOT/third_party/RPiPlay/lib/raop.c"
RAOP_H="$ROOT/third_party/RPiPlay/lib/raop.h"
HTTPD_C="$ROOT/third_party/RPiPlay/lib/httpd.c"
HTTPD_H="$ROOT/third_party/RPiPlay/lib/httpd.h"
PAIR_H="$ROOT/third_party/RPiPlay/lib/pairing.h"

if ! grep -q "channel_crypto" "$HTTPD_C" 2>/dev/null; then
    echo "applying encrypted channel patches..."

    python3 "$ROOT/patches/apply_patches.py" \
        "$RAOP_C" "$RAOP_H" "$HTTPD_C" "$HTTPD_H" "$PAIR_H"

    echo "patches applied"
else
    echo "patches already applied"
fi

echo
echo "deps ready. next: ./gradlew :app:assembleDebug"
