# TV Player (Android TV)

A native, remote-first Android TV direct-URL media player. Built for Sony BRAVIA-style TV interaction but **not yet tested on physical BRAVIA hardware**.

## What's in this source snapshot

- Kotlin + Android TV launcher, landscape activity, Compose for TV interface with clearly highlighted D-pad controls.
- Media3 ExoPlayer + foreground-Activity MediaSession playback for TV-accessible HTTP(S) progressive and supported HLS/DASH media; play/pause, ±10 s seeking, playback speeds, buffering and recoverable errors.
- On-TV URL entry (Android TV soft keyboard) and optional SRT/WebVTT external subtitle URL.
- TV-side external subtitle retrieval (2 MB maximum), English UI, font size/color/background and cue timing adjustment (±60 seconds in 500 ms increments). Apply timing reloads the current video/subtitle.
- Locally hosted phone-friendly link-entry page (port 8765): same-LAN Wi-Fi/Ethernet IPv4 bind, rotating 8-character pairing code, QR code, 8 KB request cap, form validation, attempt lockout, origin check. Only **links** cross the LAN handoff. The TV fetches actual video/subtitle bytes.
- Fit / fill / zoom display options, actual available audio/video track choices when available, decoded pixel dimensions separately from track selection.
- Local DataStore history with source fingerprints and positions. Signed queries/URLs are excluded by default. Saving complete URLs is opt-in and can be disabled; clear history is available. Replacement-link resume is presented as a best-effort host+path match, not a guarantee.
- No Seedr integration, user credentials, upstream proxy, cloud account, download service, background playback, or analytics.

## Build

**Requirements:** Android Studio with an Android SDK 36 platform, JDK 17, Gradle 8.13, internet access to Google Maven/Maven Central, and a TV emulator or authorized TV for testing. Android Gradle Plugin 8.13.2 and Kotlin/Compose Compiler plugin 2.2.20 are pinned. `minSdk=26`, `targetSdk=35`.

Open this folder as a Gradle project in Android Studio and sync dependencies. This archive includes the Gradle wrapper distribution configuration, but **does not include the Gradle wrapper JAR or ready-made APK** because the authoring environment did not have Gradle or Android SDK access and could not download them. To generate the official wrapper from an installed Gradle 8.13:

```sh
gradle wrapper --gradle-version 8.13
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

You can also run `gradle :app:assembleDebug` directly with installed Gradle 8.13. A successful build will generate `app/build/outputs/apk/debug/app-debug.apk` (that file is **not** provided here). Install on emulator with `adb install -r app/build/outputs/apk/debug/app-debug.apk` after the build.

## TV usage

1. Launch **TV Player** from the TV app list.
2. For manual entry choose **Enter video URL on TV**, type the URL with the TV's keyboard and press **Play on TV**.
3. For easier phone entry choose **Send link from phone / laptop**. Open the displayed `http://<TV-LAN-IP>:8765/` address or scan the QR code. Enter the 8-character pairing code shown on the TV, your video URL, and optional subtitle URL. The TV plays the media directly. Close or background the TV app to stop the local server.
4. Use the player's focus-highlighted Play/Pause, ±10 sec, Menus, or Home actions. In Menus, access subtitles, display, speed, and genuine media tracks. The Back key returns to the prior screen.
5. In **Settings & privacy**, opt into full URL history only if you want it. Default storage retains labels and playback positions without the URL.

## Important limits and privacy

- A direct HTTP(S) URL is necessary; links to web pages that require browser scraping, cookies, DRM licenses, or cloud authentication may not play.
- Your TV's decoder determines codec support, and actual format/track options depend on the stream.
- The link page is plain HTTP on a **trusted local network**, not HTTPS. Pairing reduces drive-by submissions but does not protect sensitive signed URLs from a malicious network observer. Avoid untrusted/shared Wi-Fi. No router port forwarding.
- Some authenticated/subtitle links may expire. The app does not renew them.
- Signed URL queries are not stored unless the local save-URLs option is ON. Subtitle bytes are kept temporarily in private cache and cleared on app destruction/new subtitle.
- Query-free fingerprints use host and path; two different resources at the same path may match and trigger a resume prompt. Choose restart when unsure.
- The mini-server requires a private IPv4 address on the TV's active Wi-Fi/Ethernet network. Browser and TV need mutual LAN access (not a guest-isolated network).
- Timing adjustment currently supports UTF-8 SRT/VTT cue lines. Non-UTF8 subtitles and unusually formatted timestamps may need a different source.
- This is a **source-code prototype**, not a certified v1 release: no Android SDK/Gradle build or emulator/TV hardware validation was possible in the authoring environment. See `docs/TESTING.md` before claiming release acceptance.

## Source layout

- `app/src/main/java/com/tvplayer/app/MainActivity.kt` – lifecycle, state navigation, app coordination
- `TvPlayerUi.kt` – Compose-for-TV screens and D-pad-focus controls
- `PlaybackEngine.kt` – Media3 lifecycle, player settings, tracks and errors
- `LanHandoffServer.kt` – paired, LAN-only link submission HTTP endpoint
- `SubtitleManager.kt` – TV-side capped fetch and local cue adjustments
- `PlaybackPrefs.kt` – DataStore privacy, settings and recent-item history
- `Models.kt` – pure URL validation, source IDs and subtitle cue timing
- `app/src/test` – unit tests for URL privacy/security and cue adjustments
- `docs/IMPLEMENTATION_PLAN.md` – original implementation plan
- `docs/TESTING.md` – QA checklist and observed validation state

## Git workflow

**No Git remote operations have been performed.** No PR, push, publication or commit was made. The user will provide a repository once the project is ready. The original milestones are described in `docs/IMPLEMENTATION_PLAN.md`; establish the milestone commit history only after review and approval.
