# Local build and TV QA guide

This guide records the completed local build/lint and authorized installation, plus checks still pending. The debug APK was installed on a Sony BRAVIA VU3 (Android API 34) but was not launched. No tests, playback, D-pad interaction, screenshots, or runtime checks were performed.

## Local toolchain

The verified toolchain is Amazon Corretto JDK 17.0.17 and Android SDK API 36 at `/Users/lmadhuranga/Library/Android/sdk`. From the repository root, the successful command was:

```sh
cd /Users/lmadhuranga/Desktop/Coding/tv-player
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/Users/lmadhuranga/Library/Android/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew --version
./gradlew :app:assembleDebug :app:lintDebug --console=plain
```

Install Android SDK Platform 36 and Android SDK Build Tools 36 through Android Studio's SDK Manager if they are missing. The Gradle wrapper pins Gradle 8.13; AGP 8.13.2 and Kotlin 2.2.21 are pinned in the version catalog. Lifecycle is pinned to 2.9.4 because 2.11.0 requires SDK37 and AGP9.1. The command above passed; APK: `app/build/outputs/apk/debug/app-debug.apk` (16 MiB). Lint reported 22 warnings and zero errors.

During milestone 2, an earlier scaffold-only build attempt timed out accessing Google Maven; its retry was stopped before Kotlin compilation. The later build on milestone 13 resolved dependencies, exposed and fixed compile/lint blockers, then passed. Gradle also warned about a local NDK folder missing `source.properties`; native libraries were packaged. The lint report includes version advisories, manifest, vector, and KTX warnings; review them during maintenance.

## Optional local installation

For an authorized installation, select the already-connected target explicitly and install without launching:

```sh
TV_SERIAL="<authorized-device-id>"
adb -s "$TV_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

This `adb install -r` command succeeded on the authorized Sony BRAVIA VU3. A read-only package check confirmed path `/data/app/.../dev.lmadhuranga.tvplayer.../base.apk`, versionCode 1 / versionName 0.1.0, minSdk 26, and targetSdk 36. No launch command was run. The APK manifest declares application ID `dev.lmadhuranga.tvplayer` and a Leanback launcher entry; launcher visibility has not been visually checked.

## Release build and signing

The release variant enables code minification and resource shrinking. Build it with:

```sh
./gradlew :app:assembleRelease
```

The project deliberately does not configure or commit a signing key. Gradle's default release output is unsigned and is not a distributable install package until the owner configures a private signing key through a local-only or CI secret mechanism. Never add keystores, passwords, or signing properties to Git. Release shrinking and packaging are unverified.

## Manual TV checklist

Record each result, device model/software, network conditions, test source/container, and any limitations. Leave checks incomplete until personally run.

- [x] Debug assemble and lint complete without errors; record Gradle/JDK/SDK versions. Lint emitted 22 warnings.
- [ ] Confirm package ID, min SDK 26, target SDK 36, landscape layout, Leanback launcher entry, and TV banner.
- [ ] Confirm focus visibility and D-pad reachability on Home, URL entry, options/settings dialogs, history and handoff screens; Back restores focus or returns safely.
- [ ] Enter a supported direct HTTP and HTTPS video link from the TV; test empty, malformed, very long, and unsupported-scheme values.
- [ ] Confirm video starts, pauses, seeks, supports each listed speed, and pauses when leaving the player or backgrounding the app.
- [ ] Test same-Wi-Fi phone/laptop link handoff, pairing code, QR, optional title, optional subtitle, invalid token/origin, oversized request, and server stop/restart. Confirm the local page sends links only and no full URLs or pairing token are logged.
- [ ] Test valid SRT and WebVTT, malformed/unreachable subtitle URLs, on/off, size/color/background, ± timing offset, external-track seek/speed behavior, and available embedded subtitle tracks.
- [ ] Test Fit/Fill/Zoom with different aspect ratios and compare decoded picture dimensions with available stream variant labels.
- [ ] Use media with multiple supported audio tracks and HLS/DASH variants; confirm actual choices switch correctly. Use a single-variant source and confirm no quality selector is shown.
- [ ] Test network loss/recovery, 401/403/404 server responses, unsupported codecs, prolonged buffering, explicit retry, fresh-link entry, and return navigation. Confirm no automatic retry loop.
- [ ] Enable local position history, resume a selected saved position, choose start over, clear history, disable history, restart the app, and verify that video/subtitle URLs are never persisted.
- [ ] Review cleartext HTTP policy, LAN endpoint bounds/session checks, backup policy, permissions, cache cleanup, release shrinking, and signing setup.
- [x] Install the debug APK on Sony BRAVIA VU3 and verify package metadata read-only. The app was not launched.
- [ ] Complete a separate authorized Sony BRAVIA runtime pass; the installation does not demonstrate launcher visibility, playback, focus, network, or subtitle behavior.

## Known boundaries

Source supports direct HTTP(S), progressive playback, and Media3 HLS/DASH modules, but actual protocols, redirects, codecs, devices, and track variants remain unverified. External subtitles are limited to SRT and WebVTT URLs ending with those extensions. History stores only opt-in local title/host/position metadata and never stores URLs. Signed-link renewal, cloud services, downloads, transcoding, media proxying, and optional Seedr integration are out of scope.
