#!/bin/sh
# Fetches the third-party sources the native AirPlay library is built from,
# each at a pinned commit, so every build (yours, CI, F-Droid) compiles the
# same code. Re-runnable: an existing checkout is verified, never re-cloned.
#
#   ./setup-deps.sh                 the sources the release is built from
#   LOCALAIR_URL_VIDEO=1 ./setup-deps.sh
#                                   also apply patches/apply_patches.py
#                                   (encrypted HTTP channel + /play URL video).
#                                   Experimental: not in any release, and it
#                                   does not compile against the pinned RPiPlay
#                                   yet (raop.c), so a build with it will fail.
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$ROOT/third_party"

# Bump these deliberately, in their own commit, and rebuild.
RPIPLAY_URL=https://github.com/FD-/RPiPlay
RPIPLAY_REV=64d0341ed3bef098c940c9ed0675948870a271f9      # 2022-03-14
LIBPLIST_URL=https://github.com/libimobiledevice/libplist
LIBPLIST_REV=fe3dc34dc3484006e12b403f7ceb06f0ad40b6f4     # 2026-09-30

fetch_pinned() {
    dest="$1"; url="$2"; rev="$3"
    if [ -d "$dest/.git" ]; then
        have="$(git -C "$dest" rev-parse HEAD)"
        if [ "$have" != "$rev" ]; then
            echo "error: $dest is at $have, expected $rev" >&2
            echo "       remove it and re-run, or check out $rev there" >&2
            exit 1
        fi
        echo "ok   $dest @ $rev"
        return
    fi
    git init -q "$dest"
    git -C "$dest" remote add origin "$url"
    git -C "$dest" fetch -q --depth 1 origin "$rev"
    git -C "$dest" -c advice.detachedHead=false checkout -q FETCH_HEAD
    echo "got  $dest @ $rev"
}

fetch_pinned "$ROOT/third_party/RPiPlay" "$RPIPLAY_URL" "$RPIPLAY_REV"
fetch_pinned "$ROOT/third_party/libplist" "$LIBPLIST_URL" "$LIBPLIST_REV"

RPIPLAY_LIB="$ROOT/third_party/RPiPlay/lib"
if [ "${LOCALAIR_URL_VIDEO:-0}" = "1" ]; then
    if ! grep -q "channel_crypto" "$RPIPLAY_LIB/httpd.c"; then
        echo "applying URL-video patches (experimental)..."
        python3 "$ROOT/patches/apply_patches.py" \
            "$RPIPLAY_LIB/raop.c" "$RPIPLAY_LIB/raop.h" \
            "$RPIPLAY_LIB/httpd.c" "$RPIPLAY_LIB/httpd.h" "$RPIPLAY_LIB/pairing.h"
    else
        echo "URL-video patches already applied"
    fi
elif [ -n "$(git -C "$ROOT/third_party/RPiPlay" status --porcelain)" ]; then
    echo "error: third_party/RPiPlay has local changes (patches applied?)." >&2
    echo "       A release build uses it unmodified:" >&2
    echo "       git -C third_party/RPiPlay checkout -- .   or set LOCALAIR_URL_VIDEO=1" >&2
    exit 1
fi

echo
echo "deps ready. next: ./gradlew :app:assembleDebug"
