# Local build and TV QA guide

This guide records the commands and checks for the project owner to run later. The current implementation pass did not build, lint, test, install, or launch the app. No debug or release APK has been produced by this pass.

## Local toolchain

The source pass found Amazon Corretto JDK 17 and Android SDK API 36 at `/Users/lmadhuranga/Library/Android/sdk`. On that Mac, open Terminal and run:

```sh
cd /Users/lmadhuranga/Desktop/Coding/tv-player
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/Users/lmadhuranga/Library/Android/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew --version
./gradlew :app:assembleDebug :app:lintDebug
```

Install Android SDK Platform 36 and Android SDK Build Tools 36 through Android Studio's SDK Manager if they are missing. The Gradle wrapper pins Gradle 8.13; AGP 8.13.2 and Kotlin 2.2.21 are pinned in the version catalog. The debug APK, if the build succeeds, should be at `app/build/outputs/apk/debug/app-debug.apk`.

During milestone 2, a scaffold-only build attempt reached Gradle dependency metadata resolution but timed out while accessing Google Maven; its retry was stopped before Kotlin compilation at the user's direction. No build, lint, or test was run for the completed milestone 2–12 source. Allow Gradle network access and retry the commands above when the user requests verification.

## Optional local installation

Only after the user confirms a TV or emulator is available for installation, connect it using the user's approved method and run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p dev.lmadhuranga.tvplayer 1
```

These commands are instructions only; this implementation pass did not connect to, query, install on, or launch an emulator or TV. The app should appear in the Android TV launcher as **TV Player**. Its application ID is `dev.lmadhuranga.tvplayer`.

## Release build and signing

The release variant enables code minification and resource shrinking. Build it with:

```sh
./gradlew :app:assembleRelease
```

The project deliberately does not configure or commit a signing key. Gradle's default release output is unsigned and is not a distributable install package until the owner configures a private signing key through a local-only or CI secret mechanism. Never add keystores, passwords, or signing properties to Git. Release shrinking and packaging are unverified.

## Manual TV checklist

Record each result, device model/software, network conditions, test source/container, and any limitations. Leave checks incomplete until personally run.

- [ ] Debug assemble and lint complete without errors; record Gradle/JDK/SDK versions.
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
- [ ] Complete a separate approved Sony BRAVIA pass; an emulator or successful build alone does not demonstrate TV playback, focus, network, or subtitle behavior.

## Known boundaries

Source supports direct HTTP(S), progressive playback, and Media3 HLS/DASH modules, but actual protocols, redirects, codecs, devices, and track variants remain unverified. External subtitles are limited to SRT and WebVTT URLs ending with those extensions. History stores only opt-in local title/host/position metadata and never stores URLs. Signed-link renewal, cloud services, downloads, transcoding, media proxying, and optional Seedr integration are out of scope.
