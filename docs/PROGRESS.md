# Progress checkpoint

## Current state

- Date: 2026-10-22
- Implemented through milestone: `19-handoff-server-settings-return` (build/lint/install/launch command complete; Settings failure reproduction pending)
- Current branch: `19-handoff-server-settings-return`
- Planning baseline: `be2f7cc`; milestones 2–19 are committed sequentially on local branches (full mapping below).
- The milestone 19 debug APK was installed and launched on the authorized Sony BRAVIA VU3 (Android API 34). No D-pad input, visual inspection, playback, subtitle, pairing, Settings reopen, or other functional runtime QA was performed.

## Decisions recorded

- Native Kotlin Android TV app; Compose for TV, Media3 ExoPlayer, and DataStore are the initial technology direction.
- App configuration uses minimum API 26 and compile/target API 36. The debug build and lint pass with the JDK17/SDK36 toolchain; runtime behavior remains unverified.
- A TV-hosted page accepts links from a same-Wi-Fi browser. It submits only the URLs; video and subtitle data are fetched by the TV.
- Direct HTTP(S) playback is the baseline. Seedr integration is deferred behind a verified authentication/support gate.
- Remote navigation and reliable focus behavior are core requirements.
- Recent history is local and privacy-conscious, with clear-history and deliberate handling of signed URL replacement/resume.
- Feature work proceeds in numbered branches stacked from the last accepted commit; commit each feature before proceeding. No pushes, repository creation, or merge without an explicit request.
- The app UI, settings, errors, documentation, and examples are English-only; subtitle content may use any language/Unicode supported by playback.
- The user authorized the milestone 13 debug build/lint/install without launch. For milestone 14, only local build/lint is authorized; the updated APK remains local. Player tests and runtime behavior remain unverified.

## Numbered branches and commits

| Milestone | Branch | Commit |
| --- | --- | --- |
| 1 | `1-plan-docs` | `be2f7cc` |
| 2 | `2-android-tv-skeleton` | `715bd34` |
| 3 | `3-tv-shell-focus` | `2a35592` |
| 4 | `4-direct-url-player` | `0e032cd` |
| 5 | `5-link-entry-screen` | `8812c25` |
| 6 | `6-lan-link-handoff` | `3e20ddc` |
| 7 | `7-external-subtitles` | `205e978` |
| 8 | `8-player-menus-display` | `345b715` |
| 9 | `9-history-resume` | `1ee76f8` |
| 10 | `10-playback-errors` | `3caeaf9` |
| 11 | `11-media-tracks` | `7aa31b2` |
| 12 | `12-device-qa-release` | branch HEAD (`git rev-parse 12-device-qa-release`) |
| 13 | `13-build-install` | branch HEAD (`git rev-parse 13-build-install`) |
| 14 | `14-fullscreen-player-controls` | `e3f574c` |
| 15 | `15-four-digit-pairing` | `ec708cb` |
| 16 | `16-dark-buttons-white-focus` | branch HEAD (`git rev-parse 16-dark-buttons-white-focus`) |
| 17 | `17-subtitle-file-upload-filename-and-hidden-skip` | `8f5d72b` |
| 18 | `18-player-back-handoff-screen` | `1019bf4` |
| 19 | `19-handoff-server-settings-return` | `1d929e2` |

## Milestone 2: `2-android-tv-skeleton`

Implemented a Kotlin/Gradle Android TV app skeleton with application ID `dev.lmadhuranga.tvplayer`, a Compose placeholder screen, Leanback and touchscreen-optional declarations, a landscape activity, TV banner and launcher vector assets, and pinned Compose for TV, Media3, and Preferences DataStore dependencies. The app module uses min SDK 26 and compile/target SDK 36. No player functionality, menus, URL entry, or persistence behavior is part of this milestone.

Pinned tool and library versions:

- AGP `8.13.2`, Gradle wrapper `8.13`, Kotlin `2.2.21`, JDK target `17`
- Compose BOM `2026.06.00`, TV Material `1.1.0`, Activity Compose `1.12.3`
- Media3 `1.10.1`, Preferences DataStore `1.2.1`

Version and platform references:

- [Android Gradle Plugin 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Android API level and AGP minimum versions](https://developer.android.com/build/releases/about-agp)
- [Compose for TV stable releases](https://developer.android.com/jetpack/androidx/releases/tv)
- [Compose BOM release mapping](https://developer.android.com/jetpack/compose/bom/bom-mapping)
- [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
- [DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore)

Verification record:

- `java -version` reported Amazon Corretto `17.0.17`.
- `./gradlew --version` succeeded and reported Gradle `8.13` on JDK 17.
- First `./gradlew :app:assembleDebug :app:lintDebug` attempt failed in `:app:checkDebugAarMetadata` when Google Maven timed out reading Compose Android metadata, before compilation.
- The same build/lint retry was stopped at the user's direction during resource processing (`:app:mergeDebugNativeLibs` was the last reported task); Kotlin compilation did not run, and no final build or lint result is available.
- `adb devices -l` was attempted during initial toolchain inspection; the ADB daemon could not start because the sandbox denied its local socket (`Operation not permitted`). No device was contacted or queried. No APK was installed or launched.
- No emulator or physical-TV behavior has been verified.

## Milestone 3: `3-tv-shell-focus`

Implemented a TV Material based home/player placeholder shell. The home and player routes have clear large-screen hierarchy, remote-focused buttons use TV Material focus behavior, and Back returns from player or settings. The settings overlay has its own close focus target and restores focus to the opener when dismissed. This establishes the screen structure and focus handoff for later controls; no playback or settings behavior is included yet.

Status: source implemented and reviewed; build, lint, emulator, and TV behavior are unverified by the current source-only instruction.

## Milestone 4: `4-direct-url-player`

Added a Media3 ExoPlayer controller with direct URI loading, preparation, play/pause, position and duration state, 30-second skip controls, playback speed cycling (1×, 1.25×, 1.5×, 2×), a TV remote control row, and a PlayerView video surface without Media3's touch-oriented controls. The app now declares Internet access for direct media retrieval. Playback errors are presented generically so signed URLs are not reflected in UI text. The URL entry screen remains in milestone 5; the controller API is ready to be connected there.

Status: source implemented and reviewed; build, lint, playback, and device behavior are unverified.

## Milestone 5: `5-link-entry-screen`

Added a remote-operable direct URL entry screen and a bounded validator. It trims outer whitespace, caps links at 4,096 characters, rejects control characters, non-HTTP(S) schemes, malformed or missing hosts, and embedded user-info credentials. Errors are shown inline; the URL is sent to the playback controller only after validation. The app now permits cleartext traffic for the planned HTTP as well as HTTPS playback path. The source label shown by the player is only the URL host; the complete URL is not displayed in that label or written to disk.

Status: source implemented and reviewed; URL parsing and playback behavior are unverified because no tests or builds are being run.

## Milestone 6: `6-lan-link-handoff`

Added a small HTTP endpoint that lives only while the handoff screen is foregrounded. It serves a responsive link form and QR code, generates a fresh 256-bit pairing code per session, and accepts only bounded JSON link submissions. Requests are limited to a 12 KiB body, 16 KiB of headers, and 4,096-character HTTP(S) video/subtitle links. (A manual title field was added later, then removed in milestone 17.) The receiver requires a same-origin request and a constant-time pairing-token match, rejects non-local IPv4 peers and cross-subnet peers, and validates subtitle extensions. The endpoint receives links only; the TV's Media3 player fetches the media. The QR carries the pairing token only in a URL fragment; the page consumes it and removes it from browser history before sending the token in a request header. The full video and subtitle URLs are never logged by this code.

Status: source implemented and reviewed; request handling, LAN interface selection, QR usability, browser compatibility, and lifecycle shutdown remain unverified.

## Milestone 7: `7-external-subtitles`

Added optional SRT and WebVTT URLs to the on-TV form and connected optional subtitles from both the phone handoff form and TV form to Media3. Subtitle downloads run off the main thread, use bounded 8 MiB private-cache files with connection/read timeouts, and fail with separate subtitle-only feedback so video playback can continue. The cached track is added through Media3's `SubtitleConfiguration`; the TV controls can turn it on or off and shift cues in 500 ms steps within a ±30 second range. Timing changes rewrite cue timestamps in a separate local file. Source and generated cache files are removed when a new video replaces the old one or the controller is released; subtitle URLs and content are not written to preferences or logs. The handoff server now invalidates stale request callbacks across stop/restart, closes any active client on stop, rejects stale listener generations, and reports listener failure instead of looping.

The playback controller pauses on activity stop so playback does not continue in the background.

Status: source implemented and reviewed; SRT/WebVTT parsing, offset behavior through seeks/speed changes, cleanup, browser-to-TV subtitle handoff, and actual rendering are unverified.

## Milestone 8: `8-player-menus-display`

Added a D-pad-accessible player options panel for Fit, Fill, and Zoom display modes; subtitle enablement and ±500 ms timing adjustment; subtitle text size, color, and background controls. The options panel takes focus while open and returns it to its opener when closed. Playback speed choices now cover 0.5×, 0.75×, 1×, 1.25×, 1.5×, 1.75×, and 2×. Subtitle timing generation checks cancellation and deletes partial cache output; prior adjusted files are pruned after Media3 switches items, and each new video clears any stale disabled text-track selection. These subtitle style controls were planned in the subtitle milestone but delivered here with the options panel.

Status: source implemented; UI focus, display modes, subtitle style rendering, cancellation cleanup, and speed changes are unverified. No build, lint, or tests were run under the source-only instruction.

## Milestone 9: `9-history-resume`

Added opt-in local playback-position history through Preferences DataStore. It retains at most 20 recent records containing a user-facing label, source host, position, duration, and update time; no video URL, query, subtitle URL, or credential is saved. Settings exposes the local privacy toggle and clear-history action. When a remembered position is available, a newly entered link prompts the user to resume or start over and warns that the replacement may point to different content. The player saves progress periodically and when paused, replaced, or released. Returning from player to Home pauses playback; leaving a player route detaches the view from the controller-owned Media3 player.

Status: source implemented; DataStore persistence, lifecycle saves, replacement prompt and resume behavior are unverified. No build, lint, or tests were run.

## Milestone 10: `10-playback-errors`

Configured Media3 with a bounded 50-second maximum/64 MiB target buffer (15 seconds minimum, 2.5 seconds initial playback, 5 seconds rebuffer threshold). The player reports its buffered-through time and distinguishes active buffering from an initial error. A 45-second continuous buffering timeout exposes retry and fresh-link actions without auto-retry loops. HTTP 401/403, 404, other HTTP statuses, network errors, unsupported/decoding formats, and generic failures receive distinct sanitized messages; error text contains no URL. Retry reuses the current direct video and remembered position/play intent, and a fresh URL remains an explicit user action. Returning home or pressing Back from the player pauses playback and saves progress.

Status: source implemented; bounded buffering, timeout, HTTP/codec classification, retry, and navigation recovery are unverified. No build, lint, or tests were run.

## Milestone 11: `11-media-tracks`

Added selectable audio tracks and stream variants from Media3's supported `Tracks.Group` data only; the options are hidden unless alternatives exist. Added Media3 HLS and DASH source modules at the pinned `1.10.1` version so adaptive manifest formats can be loaded; direct progressive URLs continue to use the base player. The variant list includes Media3 dimensions/bitrate when present and an Auto choice; the player separately reports decoded picture dimensions from `VideoSize`. There is no fabricated quality choice when Media3 reports one video track. Embedded text tracks now enable the general subtitle on/off control, while timing-offset controls remain available only for the app's external SRT/WebVTT track. Added Media3 audio-focus and becoming-noisy handling. Also fixed source review findings in the previous recovery path: the nested subtitle configuration type is imported, recovered stall messages clear on READY, and buffering timeout is paused/restarted with playback intent.

API references: [Media3 TrackSelectionOverride](https://developer.android.com/reference/androidx/media3/common/TrackSelectionOverride), [Media3 Tracks.Group](https://developer.android.com/reference/androidx/media3/common/Tracks.Group), [Media3 TrackSelectionParameters.Builder](https://developer.android.com/reference/androidx/media3/common/TrackSelectionParameters.Builder).

Status: source implemented; dependency resolution, actual track discovery/selection, HLS/DASH compatibility, external-versus-embedded subtitle controls, and audio-focus behavior are unverified. No build, lint, or tests were run.

## Milestone 12: `12-device-qa-release`

Added release-variant minification/resource shrinking configuration without signing credentials. At that milestone, TV and phone/laptop forms accepted an optional 100-character user label; milestone 17 removes the manual title field. Resume selection presents saved positions and requires the user to choose one or start from the beginning; it warns the new link may be different and does not silently resume based on a title match. Added [the local build and TV QA guide](SETUP_AND_QA.md) with toolchain setup, exact debug/lint and release commands, expected APK location, optional installation commands, a manual TV checklist, and known limitations. At this point source implementation through milestone 12 was committed, with verification deferred until milestone 13.

Status: source implemented and reviewed. The separate milestone 13 later built/linted the debug variant and installed it without launch. Release signing is intentionally unconfigured; release packaging, manifest behavior, playback, focus, subtitles, handoff, history, and device runtime compatibility remain unverified.

## Milestone 13: `13-build-install`

Fixed the compile blockers found by the first complete build: removed an invalid `weight` import, made the shared TV background composable visible to the URL screen, removed a duplicate `URI` import, and applied AndroidX `OptIn` annotations for Media3's `UnstableApi`. Pinned Lifecycle to `2.9.4`; `2.11.0` required compileSdk 37 and AGP 9.1, conflicting with this project's SDK36/AGP8.13.2 setup.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed with JDK `17.0.17`, Gradle `8.13`, AGP `8.13.2`, compile/target SDK `36`; Gradle reported `BUILD SUCCESSFUL`.
- Lint completed with 22 warnings and no errors. Warnings include newer-version advisories, fixed-orientation and backup-rule notices, vector size, KTX suggestions, and local SDK/NDK metadata warnings. The local NDK folder lacked `source.properties`; the build still packaged its native libraries.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (16 MiB). APK inspection confirmed ID `dev.lmadhuranga.tvplayer`, minSdk `26`, targetSdk `36`, `MainActivity` `MAIN`/`LEANBACK_LAUNCHER`, and Android debug signing certificate.
- ADB read-only identification confirmed Sony BRAVIA VU3, Android API `34`; no package was installed beforehand. `adb install -r` returned `Success`. Post-install `pm path` and `dumpsys package` confirmed the package path and version `1` / `0.1.0`, minSdk `26`, targetSdk `36`.
- The app was not launched. No app input, navigation, playback, screenshots, or runtime behavior were tested. Existing build/lint warnings and device runtime behavior remain open for QA.

## Milestone 14: `14-fullscreen-player-controls`

Replaced the boxed player and always-visible controls with a full-bleed Media3 surface, immersive system bars, top/bottom branded gradient scrims, a title/back/options header, buffered seek/time row, and playback controls. Controls start visible and hide after four seconds only while actively playing; pause, buffering, video/subtitle errors, and the full-screen options page hold the timeout. Hidden controls are removed from composition/focus traversal; Down reveals them and transfers focus to Play/Pause, while a matching key-up is consumed. Media Play/Pause is handled even when controls are hidden. The focused progress row seeks in ten-second steps with left/right. Back closes player settings, then hides visible controls, then returns Home. CC remains accessible on the control row; gear/audio entry opens the full-screen player settings page, and Back returns focus to its opener. Buffering remains visible while controls hide; incoming playback errors outside the settings page reveal controls and focus Retry or new link, while errors inside settings remain visible there.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36`; the lint report has no errors and 22 warnings.
- No emulator/device commands were run for milestone 14. The prior milestone 13 APK remains installed on BRAVIA VU3; this updated APK has not been installed or launched. Fullscreen layout, system bars, remote focus, timer, Back behavior, media key handling, menus, and playback remain unverified at runtime.

## Milestone 15: `15-four-digit-pairing`

Replaced the long code shown on the TV with a uniformly random, exactly four-digit PIN, preserving leading zeroes. The phone page exchanges the PIN once at same-origin `POST /api/pair` for a fresh 256-bit session token; the page retains that token only in memory and sends it in the existing `/api/play` header. PINs expire after five minutes, the TV can generate a new PIN, and the server invalidates PIN and session token when stopped or restarted. Five wrong guesses rotate the PIN and enforce a 30-second server-side cooldown. Same-origin and same-subnet checks remain in effect, and responses are marked `no-store`; no pairing secrets or full media URLs are logged.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36`; Gradle reported `BUILD SUCCESSFUL` with no errors. A nullable-host warning in the LAN address selector was corrected during implementation; the final build emitted no Kotlin compiler warnings.
- `git diff --check` passed. No tests, emulator/device commands, TV actions, or phone-browser LAN requests were run. The updated APK remains local and has not been installed or launched. PIN expiry, cooldown, one-time exchange, QR behavior, and server lifecycle remain unverified at runtime.

## Milestone 16: `16-dark-buttons-white-focus`

Added one shared TV button wrapper used by all button controls across Home, player playback/settings, privacy/history, resume, URL entry, and LAN handoff. Buttons use a dark neutral fill with white content at rest, switch to white with dark content when focused or pressed, and retain readable muted colors when disabled. Labels and checkmarks continue to express values such as selected audio/quality without a persistent selected fill; white is the current remote focus treatment. The full-screen playback canvas, PlayerView shutter, and top/bottom scrims use black so letterboxed video has a black surround; Fit/Fill/Zoom scaling behavior is unchanged.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36`; Gradle reported `BUILD SUCCESSFUL` with no errors.
- `git diff --check` passed. The milestone 16 APK was subsequently installed by the lead on the authorized Sony BRAVIA VU3 without launch. Focused/unfocused colors, disabled contrast, and black video surround remain unverified visually.

## Milestone 17: `17-subtitle-file-upload-filename-and-hidden-skip`

The app now opens directly to the phone-send QR/PIN screen, and Home no longer contains “Add Video URL” or “Open player”; browser handoff starts submitted links immediately, even when history is enabled. Added an Android document picker in the full-screen player's settings route. SRT/WebVTT files are copied to the app's private subtitle cache, validated using the existing bounded parser, and attached to the active Media3 item; cancellation/replacement cleanup and safe errors preserve video playback. This is a TV-side picker only: the phone/laptop handoff still accepts subtitle URLs, not file uploads. Removed the manual title field from the TV and handoff forms. The player header/history label now derives from the decoded final URL path segment, ignoring query strings, with host/“Video” fallbacks; the header truncates long filenames on one line with an ellipsis. With controls hidden, Left/Right seek by 30 seconds; visible-control navigation remains unchanged. A bottom timeline remains visible while controls are hidden, with dark gray played progress and light gray buffered progress.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36` (`BUILD SUCCESSFUL`, 48 tasks).
- The first sandboxed Gradle invocation stopped before configuration because Gradle could not create its local lock service socket (`Operation not permitted`); the same command passed with the required local build permission.
- After adding the timeline, filename ellipsis, direct browser-handoff playback, and default QR/PIN startup, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36` (`BUILD SUCCESSFUL`, 48 tasks).
- `git diff --check` passed. Before install, ADB identified `192.168.8.100:5555` as manufacturer Sony, model BRAVIA VU3, API 34. `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`; `pm path` and `dumpsys package` confirmed `dev.lmadhuranga.tvplayer`, version `0.1.0`, minSdk 26, targetSdk 36.
- `adb shell am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned `Starting: Intent { cmp=dev.lmadhuranga.tvplayer/.MainActivity }`. No D-pad input, screenshot, visible UI inspection, playback, or feature QA was performed. Picker, subtitle rendering/cleanup, title display, seek, handoff, visual, and playback runtime behavior remain unverified.

## Milestone 18: `18-player-back-handoff-screen`

When the player exits through Back, the app returns to the phone-send handoff route instead of Home. The existing handoff screen continues to own its current QR, displayed phone URL, short PIN and foreground-only server. It now initially focuses a focusable “Go to Player” action and also exposes “Settings”; the player can be opened empty and Settings returns focus to its handoff button. Leaving the handoff route still disposes its server and pairing session; returning creates a fresh session through the existing lifecycle. Browser submissions continue to start playback immediately.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36` (`BUILD SUCCESSFUL`, 48 tasks); `git diff --check` passed.
- Before install, ADB identified `192.168.8.100:5555` as Sony BRAVIA VU3, API 34. `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`; `pm path` and `dumpsys package` confirmed `dev.lmadhuranga.tvplayer`, version `0.1.0`, minSdk 26, targetSdk 36.
- `adb shell am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned `Starting: Intent { cmp=dev.lmadhuranga.tvplayer/.MainActivity }`. No D-pad input, screenshot, visible UI inspection, playback, or feature QA was performed. Back routing, action focus, QR/URL freshness, and session rotation remain unverified at runtime.

## Milestone 19: `19-handoff-server-settings-return`

The handoff screen now keeps its existing listener alive during `ON_PAUSE`, because an in-app Settings dialog may cause a transient pause without leaving the app. It stops the listener on `ON_STOP` and when the screen is disposed, then idempotently starts again on `ON_START`. The listener enables address reuse for quick same-process rebind after recent connections. Startup reports the port conflict message only for `BindException`; other failures include a bounded, sanitized exception reason with network retry guidance. Pairing state continues to be invalidated on stop.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36` (`BUILD SUCCESSFUL`, 48 tasks); `git diff --check` passed.
- Before install, ADB identified `192.168.8.100:5555` as Sony BRAVIA VU3, API 34. `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`; package metadata confirmed `dev.lmadhuranga.tvplayer`, version `0.1.0`, minSdk 26, targetSdk 36. `adb shell am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned `Starting: Intent { cmp=dev.lmadhuranga.tvplayer/.MainActivity }`.
- Runtime reproduction of the user's Settings error has not been performed; install/launch does not confirm the fix. Verify repeated Settings open/close keeps the URL/QR working and genuine port conflicts remain actionable.

## Current execution boundary

Milestones 2–19 are committed on separate local branches, stacked sequentially. The Settings/server fix has not been reproduced on-device; manual functional/runtime QA remains pending. Optional Seedr discovery remains deferred.
