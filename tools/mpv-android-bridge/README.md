# mpv-android EndFile Bridge

This directory contains the minimal native patch needed to preserve
`mpv_event_end_file.reason`, `error`, and `playlist_entry_id` across the
mpv-android JNI bridge.

The patch is based on the exact source revision used for the packaged Android
libraries. It changes `libplayer.so` only. It does not rebuild or replace
`libmpv.so`, FFmpeg, or the other official mpv-android libraries.

## Fixed source

See `upstream.txt`:

- mpv-android: `3018d47277d5b3ca02acdd96466f261c1d23ee08`
- Release: `2026-04-25`
- NDK: `r29`

## GitHub Actions build

Run the manual `Build mpv Android bridge` workflow. It checks out the fixed
mpv-android revision, verifies and extracts the official arm64 release APK,
applies the patch, and rebuilds only `libplayer.so` with NDK r29. It builds a
fixed mpv prefix solely to obtain ABI-correct headers, then links the bridge
against the verified official APK libraries. The uploaded artifact includes the
bridge, SHA-256, provenance, and build logs; the workflow does not modify the
checked-in WindPlayer binaries.

## Linux build

mpv-android's native build scripts do not support Windows or WSL. Use a Linux
CI runner, Linux VM, or macOS environment with the Android SDK and NDK.

```sh
git clone https://github.com/mpv-android/mpv-android.git
cd mpv-android
git checkout 3018d47277d5b3ca02acdd96466f261c1d23ee08
git apply --unidiff-zero /path/to/WindPlayer/tools/mpv-android-bridge/0001-pass-end-file-data.patch
./buildscripts/download.sh
./buildscripts/buildall.sh --arch arm64 mpv
./buildscripts/buildall.sh -n --arch arm64
```

The exact upstream scripts may require their documented dependency setup. The
second native build produces the patched `app/src/main/jniLibs/arm64-v8a/
libplayer.so`. Copy only that patched `libplayer.so` into WindPlayer and retain
the official hashes for all other `.so` files.

After rebuilding, record the new `libplayer.so` SHA-256 in
`Documents/Native-Assets-SHA256.txt`. Do not replace the official mpv-android
APK provenance with the patched bridge provenance: they are separate assets.

## ABI and compatibility

The Kotlin bridge exposes `endFile(reason: Int, error: Int, playlistEntryId: Long)`.
An unpatched old `libplayer.so` continues to call `event(7)`, and the Kotlin
fallback remains conservative. A patched bridge is required before claiming
that Android reports the exact native EndFile reason.
