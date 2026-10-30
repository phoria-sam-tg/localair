#!/usr/bin/env python3
"""
The closed dev loop Sam asked for on ticket #1019: cast a fixed video to a
running localair receiver from a script, no phone required. Does a real
DIAL launch, reads the screen_id straight off the status XML's
additionalData (see DialHttpServer.kt — skips the pairing-code round trip,
which is only there for real senders that don't expose that), pairs as a
Lounge remote, and sends setPlaylist.

Usage:
    python3 cast_test_harness.py <http-port> [video_id] [host]

<http-port> is DialCastService's random port for this run — read it from
`adb logcat -s DialCastService:*` ("DIAL up: ..., http=<port>") after
installing and launching the app; it changes every run.

Then confirm it landed:
    adb -s <device>:5555 logcat -d -s LoungeClient:* DialCastService:*

No multicast involved (SSDP discovery still needs a real phone or a non-WSL
box — see ticket #1019), this only proves the DIAL-launch + Lounge-pairing
+ command path once a sender already has the receiver's address.
"""
import re
import sys
import uuid
from xml.etree import ElementTree

import requests

API_BASE = "https://www.youtube.com/api/lounge"
DEFAULT_VIDEO_ID = "dQw4w9WgXcQ"
DEFAULT_HOST = "192.168.1.16"


def dial_launch(host: str, http_port: int) -> None:
    pairing_code = str(uuid.uuid4()).upper()
    r = requests.post(
        f"http://{host}:{http_port}/apps/YouTube",
        data={"pairingCode": pairing_code, "theme": "cl", "dialLaunch": "watch"},
    )
    print(f"DIAL launch -> {r.status_code}, Location={r.headers.get('Location')}")


def get_screen_id_from_status(host: str, http_port: int) -> str:
    r = requests.get(f"http://{host}:{http_port}/apps/YouTube")
    ns = {"": "urn:dial-multiscreen-org:schemas:dial"}
    root = ElementTree.fromstring(r.text)
    el = root.find(".//additionalData/screenId", ns)
    if el is None or not el.text:
        raise RuntimeError(f"no screenId in status response: {r.text}")
    return el.text


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    http_port = int(sys.argv[1])
    video_id = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_VIDEO_ID
    host = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_HOST

    dial_launch(host, http_port)
    screen_id = get_screen_id_from_status(host, http_port)
    print(f"screen_id={screen_id}")

    lounge_token = requests.post(
        f"{API_BASE}/pairing/get_lounge_token_batch", data={"screen_ids": screen_id}
    ).json()["screens"][0]["loungeToken"]

    body = {
        "app": "web", "mdx-version": "3", "name": "cast-test-harness", "id": str(uuid.uuid4()),
        "device": "REMOTE_CONTROL", "capabilities": "que,dsdtr,atp,vsp",
        "magnaKey": "cloudPairedDevice", "ui": "false",
        "deviceContext": "user_agent=dunno&window_width_points=&window_height_points=&os_name=android&ms=",
        "theme": "cl", "loungeIdToken": lounge_token,
    }
    r = requests.post(f"{API_BASE}/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error", data=body)
    print(f"connect -> {r.status_code}")

    # SID/gsession come back inside the chunked body. A real parser (see
    # LoungeClient.kt) walks the length-prefixed chunks properly; a regex
    # is good enough for a one-shot script that only needs these two values.
    sid_match = re.search(r'"c","([^"]+)"', r.text)
    gsession_match = re.search(r'"S","([^"]+)"', r.text)
    if not sid_match or not gsession_match:
        print("failed to parse sid/gsession from:", r.text)
        sys.exit(1)
    sid, gsession = sid_match.group(1), gsession_match.group(1)
    print(f"sid={sid} gsession={gsession}")

    params = {
        "name": "cast-test-harness", "loungeIdToken": lounge_token, "SID": sid, "AID": 0,
        "gsessionid": gsession, "device": "REMOTE_CONTROL", "app": "youtube-desktop",
        "VER": "8", "v": "2", "RID": 2,
    }
    cmd_body = {"count": 1, "ofs": 1, "req0__sc": "setPlaylist", "req0_videoId": video_id}
    r = requests.post(f"{API_BASE}/bc/bind", data=cmd_body, params=params)
    print(f"setPlaylist({video_id}) -> {r.status_code} {r.text[:100]}")


if __name__ == "__main__":
    main()
