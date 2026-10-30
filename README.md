# localair

**AirPlay, plus YouTube casting, for Android TVs whose built-in Chromecast
Google refuses.**

Some Android TV devices, many Xiaomi / Fengmi projectors among them, still
ship Google's Cast receiver, but Google no longer issues it a device
certificate. The receiver shows up in your phone's cast menu, every cast
fails, and nothing on the device can fix it. localair is a small native
receiver that gives those devices their casting back:

- **AirPlay** from iPhone, iPad and Mac: screen mirroring, and video URLs
  handed over by apps.
- **YouTube casting** from the YouTube app on any phone. localair appears
  in the cast menu as **Projector**. It speaks DIAL plus YouTube's own
  Lounge protocol, which needs no Google device certificate, and plays
  the video in the YouTube app already installed on the TV.
- **Ready at boot.** Both receivers start when the TV starts, so you can
  cast without opening anything first.

No ads, no telemetry, no account.

## Status

| Feature | State |
| --- | --- |
| AirPlay 2 pairing + FairPlay v2 | ✅ |
| AirPlay screen mirroring (H.264 → MediaCodec) | ✅ |
| AirPlay video URL playback (ExoPlayer) | ✅ ¹ |
| AirPlay audio (AAC-ELD) | ✅ ¹ |
| YouTube: discovery (DIAL/SSDP) and launch | ✅ |
| YouTube: play a video, at a start time | ✅ hands off to the YouTube TV app |
| YouTube: pause / play / seek / stop from the phone | ✅ relayed to the YouTube app's media session (needs the one-time grant below) |
| YouTube: now-playing status shown back on the phone | ❌ not yet |
| Start at boot | ✅ |
| Tested hardware | Xiaomi Mi Laser projector MiProjL1 (Android 9, armv7) |

¹ Built on the April AirPlay work; working before 1.0, not retested for
this release. The 1.0 test pass covered YouTube casting (launch, play,
pause, play, seek, stop), boot start, and AirPlay advertising after a reboot.

## What it doesn't do

- It isn't a Google Cast receiver. Apps that only speak Cast (Netflix,
  Disney+, Chrome tab casting, Google Photos) still can't cast to it. YouTube
  works because it also supports DIAL and Lounge pairing.
- YouTube playback happens in the official YouTube TV app
  (`com.google.android.youtube.tv`), so that app must be installed. Its
  sign-in, ads and recommendations apply.
- The phone's YouTube app doesn't show progress for localair yet, so the
  sender's scrubber won't move.

## Install

1. Turn on **Developer options → USB debugging** (or network debugging) on
   the TV and connect: `adb connect <tv-ip>:5555`.
2. Install the APK from the
   [latest release](https://github.com/phoria-sam-tg/localair/releases/latest):
   ```sh
   adb install -r localair-1.0.0.apk
   ```
3. Allow localair to control the YouTube app's playback. Android TV has no
   settings screen for this, so grant it once over adb:
   ```sh
   adb shell cmd notification allow_listener \
     com.localair.airplay/com.localair.airplay.dial.MediaAccessListener
   ```
   Without the grant, casting still starts videos. Pause, seek and stop
   from the phone just don't reach the TV.
4. Open localair once. Android keeps a newly installed app stopped until it has been opened, so the boot start only works after that. From then on it starts by itself whenever the TV starts.

On a phone, open YouTube, tap the cast icon and pick **Projector**. For
AirPlay, pick the TV's name in Control Centre → Screen Mirroring.

If the TV's built-in receiver also appears (often as "Living Room TV" or
similar), that's the broken one. Pick **Projector**.

## Architecture

```
┌────────────── :app (Kotlin) ──────────────┐
│  MainActivity  → SurfaceView + waiting UI │
│  AirPlayService→ foreground svc, mDNS     │
│  MdnsAdvertiser→ NsdManager _airplay/_raop│
│  VideoDecoder  → MediaCodec → Surface     │
└─────────────────┬─────────────────────────┘
                  │  JNI
┌─────────────────▼─────────────────────────┐
│              :airplay (C/C++)              │
│  jni_bridge.cpp  raop_init / raop_start    │
│  video_sink.cpp  NAL → JNI callback        │
│  dnssd_stub.c    no-op libdns_sd shim      │
│                                            │
│  third_party/RPiPlay/lib  (RTSP, FairPlay, │
│                            mirror buffer)  │
│  third_party/libplist     (in-tree build)  │
│  com.android.ndk.thirdparty:openssl (AAR)  │
└────────────────────────────────────────────┘
```

The Kotlin layer never touches RTSP or crypto — it just owns the Surface
and feeds NAL units it gets from the JNI bridge into MediaCodec. mDNS is
done in Kotlin via `NsdManager`, replacing RPiPlay's libdns_sd-based
`dnssd.c` with a minimal stub (`dnssd_stub.c`) that satisfies the API.

## Build

Requires JDK 17 or newer (Temurin recommended), Android SDK 35,
NDK 27.0.12077973 and CMake 3.22.1; Gradle pins the last two.

```sh
./setup-deps.sh                                # RPiPlay + libplist at pinned commits
cp local.properties.example local.properties   # then edit sdk.dir
./gradlew :app:assembleDebug                   # installs as com.localair.airplay.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build is reproducible: `setup-deps.sh` fetches each dependency at a
pinned commit (and refuses a checkout that has drifted), the Gradle wrapper
is checksum-verified, and the native library maps the checkout path out of
its debug info. Two clean `./gradlew :app:assembleRelease` runs from
different directories produce a byte-identical unsigned APK (verified with
JDK 17.0.21 on macOS arm64).

The AirPlay URL-video patches in `patches/` are not part of any release;
`LOCALAIR_URL_VIDEO=1 ./setup-deps.sh` applies them, but they do not compile
against the pinned RPiPlay yet.

Release builds are signed when `local.properties` names a keystore
(`release.storeFile` and friends, see `local.properties.example`). Keep the
keystore outside the repo.

`tools/cast_test_harness.py` casts to a running receiver from a script, with
no phone: a DIAL launch, Lounge pairing, then `setPlaylist`, and with
`--controls` also pause, play, seek and stop. Use it for a build-install-cast
loop.

If you don't have a wireless ADB pairing UI on your Android TV
(common on older Xiaomi / FengOS builds), enabling **Developer options →
USB debugging** and rebooting once is usually enough — `adb connect <ip>:5555`
will then accept after you confirm the RSA prompt on the TV.

## Licensing

GPL-3.0-or-later. This project statically links RPiPlay, which is GPL-3.0,
so the combined work inherits GPL-3.0. See `LICENSE`.

Third-party components and their licenses:

| Component           | License           |
| ------------------- | ----------------- |
| RPiPlay             | GPL-3.0           |
| libplist            | LGPL-2.1-or-later |
| OpenSSL (libcrypto) | OpenSSL / Apache-2.0 dual |
| AndroidX Media3 (ExoPlayer) | Apache-2.0 |

## Credits

Standing on the shoulders of:

- [RPiPlay](https://github.com/FD-/RPiPlay) — entire AirPlay 2 / FairPlay 2 stack
- [shairplay](https://github.com/juhovh/shairplay) — RAOP groundwork RPiPlay forked from
- [libplist](https://github.com/libimobiledevice/libplist) — Apple plist parsing
