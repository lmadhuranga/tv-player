# Progress checkpoint

## Current state

- Date: 2026-10-22
- Implemented through milestone: `87-network-file-manager` (unit tests, debug build, and lint passed; browser visual and TV validation pending)
- Current branch: `87-network-file-manager`
- Planning baseline: `be2f7cc`; milestones 2–29 are committed sequentially on local branches (full mapping below).
- The milestone 19 APK was previously installed/launched on the authorized Sony BRAVIA VU3 (Android API 34). Milestone 25 later included limited handoff/player inspection, a successful browser URL send, and Settings persistence checks; details and remaining runtime gaps are recorded below.

## Decisions recorded

- Native Kotlin Android TV app; Compose for TV, Media3 ExoPlayer, and DataStore are the initial technology direction.
- App configuration uses minimum API 26 and compile/target API 36. The debug build and lint pass with the JDK17/SDK36 toolchain; runtime behavior remains unverified.
- A TV-hosted page accepts links from a same-Wi-Fi browser. It submits only the URLs; video and subtitle data are fetched by the TV.
- Direct HTTP(S) playback is the baseline. Seedr integration is deferred behind a verified authentication/support gate.
- Remote navigation and reliable focus behavior are core requirements.
- Recent history is local and privacy-conscious, with clear-history and deliberate handling of signed URL replacement/resume.
- Feature work proceeds in numbered branches stacked from the last accepted commit; commit each feature before proceeding. No pushes, repository creation, or merge without an explicit request.
- The app UI, settings, errors, documentation, and examples are English-only; subtitle content may use any language/Unicode supported by playback. Phone access now requires explicit TV remote approval before the browser receives a session token.
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
| 20 | `20-phone-video-subtitle-upload` | `773c65f` |
| 21 | `21-limits-origin-fix` | branch HEAD (`git rev-parse 21-limits-origin-fix`) |
| 22 | `22-buffering-choice` | `54acfe2` |
| 23 | `23-phone-input-mode` | branch HEAD (`git rev-parse 23-phone-input-mode`) |
| 24 | `24-phone-url-history` | branch HEAD (`git rev-parse 24-phone-url-history`) |
| 25 | `25-phone-mode-buttons` | `81b5509` |
| 26 | `26-darker-qr-page` | `3ad257b` |
| 27 | `27-remote-pair-approval` | `08fb00a` |
| 28 | `28-collapsible-subtitle` | `de73547` |
| 29 | `29-minimal-phone-page` | `6e3d3fe` |
| 30 | `30-browser-dark-theme` | branch HEAD (`git rev-parse 30-browser-dark-theme`) |
| 31 | `31-video-send-history` | `0a0d95c` |
| 32 | `32-fullscreen-dark-timeline` | `bd145b5` |
| 35 | `35-icon-player-controls` | branch HEAD (`git rev-parse 35-icon-player-controls`) |
| 56 | `56-fullscreen-progress-time-labels` | pending local commit |
| 57 | `57-repeat-two-minute-hold-seek` | pending local commit |
| 58 | `58-half-progress-opacity` | pending local commit |
| 71 | `71-outline-paused-indicator` | branch HEAD (`git rev-parse 71-outline-paused-indicator`) |
| 72 | `72-home-recent-title-ellipsis` | pending local commit |
| 77 | `77-playback-keep-awake` | branch HEAD (`git rev-parse 77-playback-keep-awake`) |
| 78 | `78-usb-all-folder-files` | pending local commit |
| 79 | `79-usb-folder-counts` | `54eee8c` |
| 80 | `80-hide-unavailable-file-size` | `d1c6b46` |
| 81 | `81-usb-explorer-list-design` | pending local commit |

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

Added a small HTTP endpoint that lives only while the handoff screen is foregrounded. It serves a responsive link form and QR code, uses a same-origin browser pairing exchange for a fresh 256-bit session token, and accepts only bounded JSON link submissions. Requests are limited to a 12 KiB body, 16 KiB of headers, and 4,096-character HTTP(S) video/subtitle links. (A manual title field was added later, then removed in milestone 17.) The receiver requires a same-origin request and a constant-time pairing-token match, rejects non-local IPv4 peers and cross-subnet peers, and validates subtitle extensions. The endpoint receives links only; the TV's Media3 player fetches the media. The QR carries the pairing token only in a URL fragment; the page consumes it and removes it from browser history before sending the token in a request header. The full video and subtitle URLs are never logged by this code.

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

## Milestone 15: `15-four-digit-pairing` (superseded in milestone 27)

This milestone introduced the earlier browser pairing exchange. Milestone 27 replaces that interaction with on-TV remote approval; the current acceptance criteria and runtime checks are documented there.

## Milestone 16: `16-dark-buttons-white-focus`

Added one shared TV button wrapper used by all button controls across Home, player playback/settings, privacy/history, resume, URL entry, and LAN handoff. Buttons use a dark neutral fill with white content at rest, switch to white with dark content when focused or pressed, and retain readable muted colors when disabled. Labels and checkmarks continue to express values such as selected audio/quality without a persistent selected fill; white is the current remote focus treatment. The full-screen playback canvas, PlayerView shutter, and top/bottom scrims use black so letterboxed video has a black surround; Fit/Fill/Zoom scaling behavior is unchanged.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36`; Gradle reported `BUILD SUCCESSFUL` with no errors.
- `git diff --check` passed. The milestone 16 APK was subsequently installed by the lead on the authorized Sony BRAVIA VU3 without launch. Focused/unfocused colors, disabled contrast, and black video surround remain unverified visually.

## Milestone 17: `17-subtitle-file-upload-filename-and-hidden-skip`

The app now opens directly to the phone-send QR handoff screen, and Home no longer contains “Add Video URL” or “Open player”; browser handoff starts submitted links immediately, even when history is enabled. Added an Android document picker in the full-screen player's settings route. SRT/WebVTT files are copied to the app's private subtitle cache, validated using the existing bounded parser, and attached to the active Media3 item; cancellation/replacement cleanup and safe errors preserve video playback. This is a TV-side picker only: the phone/laptop handoff still accepts subtitle URLs, not file uploads. Removed the manual title field from the TV and handoff forms. The player header/history label now derives from the decoded final URL path segment, ignoring query strings, with host/“Video” fallbacks; the header truncates long filenames on one line with an ellipsis. With controls hidden, Left/Right seek by 30 seconds; visible-control navigation remains unchanged. A bottom timeline remains visible while controls are hidden, with dark gray played progress and light gray buffered progress.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36` (`BUILD SUCCESSFUL`, 48 tasks).
- The first sandboxed Gradle invocation stopped before configuration because Gradle could not create its local lock service socket (`Operation not permitted`); the same command passed with the required local build permission.
- After adding the timeline, filename ellipsis, direct browser-handoff playback, and default QR handoff startup, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed on JDK `17.0.17`, Gradle `8.13`, SDK `36` (`BUILD SUCCESSFUL`, 48 tasks).
- `git diff --check` passed. Before install, ADB identified `192.168.8.100:5555` as manufacturer Sony, model BRAVIA VU3, API 34. `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`; `pm path` and `dumpsys package` confirmed `dev.lmadhuranga.tvplayer`, version `0.1.0`, minSdk 26, targetSdk 36.
- `adb shell am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned `Starting: Intent { cmp=dev.lmadhuranga.tvplayer/.MainActivity }`. No D-pad input, screenshot, visible UI inspection, playback, or feature QA was performed. Picker, subtitle rendering/cleanup, title display, seek, handoff, visual, and playback runtime behavior remain unverified.

## Milestone 18: `18-player-back-handoff-screen`

When the player exits through Back, the app returns to the phone-send handoff route instead of Home. The existing handoff screen continues to own its current QR, displayed phone URL, remote approval prompt and foreground-only server. “Go to Player” is shown and initially focused only while current media is available; when no media is loaded the action is hidden and Settings receives initial focus. Leaving the handoff route still disposes its server and pairing session; returning creates a fresh session through the existing lifecycle. Browser submissions continue to start playback immediately.

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

## Milestone 20: `20-phone-video-subtitle-upload`

The paired phone page keeps video URL and optional subtitle URL submission and adds the alternative of uploading a video and/or SRT/WebVTT subtitle file. The browser sends each file as a raw authenticated octet-stream request with `Content-Length`; Android copies it in 64 KiB chunks directly to app-private cache (no full-file heap buffer and no media proxy). The page receives an authenticated effective limit calculated from the 8 GiB video/8 MiB subtitle caps and current usable cache after retaining a 512 MiB reserve. The server rechecks the size before writing and free cache while streaming. Header, request, same-origin, same-subnet, session-token bounds and checks remain active; filenames are sanitized and media extensions/container signatures are checked.

Only one staged file of each kind is kept for a paired session. Cancel or failed uploads remove the incomplete/staged files; a successful `/api/play` atomically transfers files to an app-private playback directory. The playback controller owns the files, keeps their paths out of history, imports uploaded subtitles to its existing parsed/cache path, and removes the uploads on replacement, player Back, app stop/release, playback completion, or media error. App start removes playback-upload leftovers from a previous process. Phone-page URL and upload choices are mutually exclusive per media kind, with upload progress, cancel, and status/error messaging. URL-only traffic still sends a small JSON request and the TV fetches its media directly.

Verification record:

- `git diff --check` passed. With JDK `17.0.17`, Gradle `8.13`, SDK `36`, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks); lint task passed. An initial sandbox attempt was blocked before Gradle configuration by local lock-socket permission; the same build passed with local build permission. No unit, browser, media playback, or cleanup lifecycle tests were run.
- Before final install, `adb devices -l` showed `192.168.8.100:5555` online; getprop identified manufacturer Sony, model BRAVIA VU3, API 34. `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`. Read-only package inspection confirmed `/data/app/.../dev.lmadhuranga.tvplayer.../base.apk`, versionCode 1 / versionName 0.1.0, minSdk 26, targetSdk 36. `adb shell am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned `Starting: Intent { cmp=dev.lmadhuranga.tvplayer/.MainActivity }`.
- Phone-browser upload, URL/file combinations, visible limit/progress, cancel, file decoding, subtitle rendering, cleanup on each exit, handoff focus, and player UI were not exercised. The install and launch prove package deployment/start only, not runtime feature acceptance.

## Current execution boundary

Milestones 2–26 are committed on separate local branches, stacked sequentially. The milestone 25 APK includes the preceding source and has been installed and launched on the approved BRAVIA; the Activity remained top-resumed. Phone-browser controls are being checked separately; media playback/history restoration, upload, subtitle rendering, D-pad, and buffering behavior remain unverified. Optional Seedr discovery remains deferred.

## Milestone 21: `21-limits-origin-fix`

Browsers can omit the `Origin` header on a same-origin GET. `/api/limits` now allows the header to be absent but still requires a valid high-entropy pairing token; if an Origin is present it must match the TV's exact HTTP origin and port. The existing subnet gate remains before request handling, and every POST endpoint retains its strict same-origin check. No video, upload, pairing form, or browser state was submitted or altered during verification.

Verification record:

- `git diff --check` passed. With JDK `17.0.17`, Gradle `8.13`, SDK `36`, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks); lint passed.
- Before install, ADB identified `192.168.8.100:5555` as Sony BRAVIA VU3, API 34. The debug install returned `Success`; `pm path` confirmed `dev.lmadhuranga.tvplayer` was installed. `am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned the expected `Starting: Intent`.
- Read-only GET checks against the running TV server returned HTTP 401 with Origin absent and no token, proving the request reached authentication, and HTTP 403 with a mismatched Origin. The existing browser form was not used. No phone upload, playback, or UI QA was performed.

## Milestone 22: `22-buffering-choice`

Added a persisted Settings choice for a 1-, 2-, or 5-minute maximum forward buffer, defaulting to 1 minute. The selected policy is applied when the next video starts; this avoids replacing the active Media3 player while it owns a current stream or temporary uploaded files. The 15-second minimum buffer, 2.5-second initial startup threshold, and 5-second rebuffer threshold are unchanged, so playback does not wait for the selected duration. The existing 64 MiB target-buffer byte estimate remains (it is not a strict byte cap); memory availability and stream characteristics can shorten actual buffered time, so the selected duration is a maximum target rather than a guarantee.

Verification record:

- `git diff --check` passed. With JDK 17, Gradle 8.13, and SDK 36, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks). Lint passed with 0 errors and 25 warnings.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (16 MiB).
- No TV connection, install, launch, or interaction was performed for this milestone; persistence and playback behavior need manual verification.

## Milestone 23: `23-phone-input-mode`

Updated the paired phone page so video and subtitle each have an independent URL/File radio selector. Both default to URL. Only the selected input mode is visible and enabled; switching modes clears the inactive URL or file selection, preventing stale alternate values from being submitted. The request builder reads only the active video and subtitle modes, while the subtitle remains optional. Mode selectors and media fields are locked while send/upload is in progress so a submission cannot cancel a staged file midway; the explicit Cancel action remains usable. Existing file limits, streaming progress/cancellation, pairing authentication, server-side validation, and cleanup requests are unchanged.

Verification record:

- `git diff --check` passed. The embedded phone-page JavaScript passed `node --check` after extraction from the Kotlin raw HTML string.
- With JDK 17, Gradle 8.13, and SDK 36, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks); lint passed with 0 errors and 25 warnings.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (16 MiB).
- No TV connection, install, or launch was performed. Real browser interaction and URL/file combinations remain unverified.

## Milestone 24: `24-phone-url-history`

Added browser-local history for successfully submitted video and subtitle URLs. The phone page stores full URL values only in that browser's localStorage, deduplicated by kind and URL, capped at 20 total entries, and expires entries after 24 hours. Signed links may expire earlier on their source service. Each section renders a separate list whose button labels show only the hostname and final path segment, with no query string; DOM content is assigned as text rather than HTML. Selecting an entry switches that media type to URL mode and fills its input without submitting or starting playback; expired/cleared entries are rechecked at click time. A clear control deletes local history. Upload files are never stored in this history. Send-time locks cover mode radios, media inputs, history buttons, and clear control, while Cancel remains available. If localStorage access or writes are blocked, history is unavailable but the form's send path remains usable.

Verification record:

- `git diff --check` passed. The embedded phone-page JavaScript passed `node --check` after extraction from the Kotlin raw HTML string.
- With JDK 17, Gradle 8.13, and SDK 36, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks); lint passed with 0 errors and 25 warnings.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (16 MiB).
- No TV connection, install, or launch was performed. Browser runtime, expiry, storage-denial, and no-auto-submit behavior remain unverified.

## Milestone 25: `25-phone-mode-buttons`

Replaced the paired phone page's URL/File radio inputs with two accessible button groups, one for video and one for subtitles. URL is selected by default in each group; the selected mode is visually white and unselected mode dark, with `aria-pressed` and group labels for assistive technology. The existing inactive-field hiding, disabling and clearing, URL-history restoration, and in-progress lock behavior now use these buttons. History still fills URL mode without submitting.

Verification record:

- `git diff --check` and the embedded page JavaScript `node --check` passed.
- With JDK 17, Gradle 8.13, and SDK 36, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks).
- The debug APK was installed with `adb install -r` on the verified Sony BRAVIA VU3 (Android API 34); `am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned `Starting`, the process remained alive, and `dumpsys activity activities` showed `MainActivity` top-resumed. The handoff page and QR became available after initial startup. The then-current short-code flow was exercised in a separate browser check.
- The lead's separate browser check reported successful URL/URL submission, loaded limits and video/subtitle history buttons, unlocked controls, and no JavaScript errors. The TV fetched the synthetic 10-second silent WAV and SRT fixtures (both HTTP 200). The player showed the WAV filename, 0:10 duration, CC On, and no visible error; the captured image was at the end of the silent audio sample, so audible playback and subtitle cue rendering were not confirmed. Back returned to Handoff with QR/URL. Settings showed 1 minute initially; after selecting 2 minutes, closing/reopening Settings retained 2 minutes, and 1 minute was restored. The position-history toggle remained Off after an accidental toggle was immediately reverted. The new mode buttons still need explicit URL/File switching checks; no video image/codec playback was tested.

## Milestone 26: `26-darker-qr-page`

Reduced the handoff QR tile and the QR bitmap background/quiet zone from white to light neutral gray `#D6D6D6`. QR modules remain black, the existing generated URL/content and QR matrix dimensions are unchanged, and the standard four-module quiet zone from ZXing remains intact. For sRGB `#D6D6D6`, black-to-background contrast is approximately 14.4:1; this lowers the pure-white tile's brightness while retaining strong foreground separation for QR scanners. Other handoff text, controls, focus colors, and navigation are unchanged.

Verification record:

- `git diff --check` passed. With JDK 17, Gradle 8.13, and SDK 36, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks).
- No TV install, launch, screenshot, or live phone-camera scan was performed for the darker QR change. Visual appearance and scanner compatibility should be checked on-device.

## Milestone 27: `27-remote-pair-approval`

Replaced the phone page's short-code form and QR fragment with a direct handoff URL. A same-origin `POST /api/pair` now waits up to 60 seconds for an explicit TV remote decision. The TV prompt identifies the browser family from a conservative User-Agent mapping and shows its peer IP; Deny is initially focused and Allow is reachable with D-pad navigation. If Settings was already open, it closes so the approval prompt remains visible; after a decision the normal handoff actions are available again. Allow creates a fresh 256-bit session token only after the remote decision; subsequent limits, URL submission, uploads, and play requests keep using the existing token and strict Origin/subnet checks. Deny, Back dismissal, timeout, or server stop wake the pending request with a clear HTTP failure and clear the prompt. One pending request is permitted at a time, with a short retry cooldown to prevent repeated prompts. The server remains foreground-only, and no trusted-browser state is persisted.

Verification record:

- Local debug build/lint, embedded JavaScript syntax check, and `git diff --check` results are recorded after execution below.
- A narrow Python source assertion checked that the old QR fragment/header/state are absent, `/api/pair` has no code header, and the TV Allow action is wired to the approval method. No TV install, launch, or navigation was performed for this milestone. Remote focus, prompt visuals, phone browser behavior, and runtime allow/deny/timeout flows remain unverified on-device.

## Milestone 28: `28-collapsible-subtitle`

The phone handoff now shows an “Add subtitle” button by default; the subtitle URL/File modes, URL history, and size guidance are contained in a collapsed, disabled section. The toggle exposes `aria-expanded` and `aria-controls`, and is locked during submission. Opening reveals the previous subtitle controls. Closing clears both subtitle URL and file selection, resets the mode to URL, and clears any staged handoff upload so a later video-only send cannot reuse a subtitle. Choosing a subtitle-history item reveals the section before filling the URL field; it does not submit the form.

Verification record:

- With JDK `17.0.17`, Gradle `8.13`, and SDK `36`, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks; 25 lint warnings, zero errors).
- Extracted the embedded handoff script to `/tmp/tv-player-handoff.js`; `node --check /tmp/tv-player-handoff.js` passed. Source assertions covered initial collapsed/disabled state, accessibility attributes, clear/reset on close, busy lock, history reveal/fill order, optional subtitle send, and hidden-state guards. `git diff --check` passed.
- No TV contact, install, or launch was performed. Visual phone-browser behavior remains unverified in a browser.


## Milestone 29: `29-minimal-phone-page`

Simplified the paired-phone handoff form into a compact dark card with a concise heading, short field labels, touch-sized full-width controls, and responsive safe-area spacing. The layout stays centered on wider screens and switches to compact padding on narrow screens. Removed the long instructional paragraph and redundant upload-limit helper; the current TV upload limit appears after pairing. The optional subtitle remains collapsed by default with its URL/File controls and history inside. The URL/File selectors, history selection/clear action, remote approval wait, progress/status messaging, and form busy lock remain intact. Upload progress and Cancel are hidden unless a file transfer is active.

Verification record:

- With JDK `17.0.17`, Gradle `8.13`, and SDK `36`, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks; 25 lint warnings, zero errors).
- Extracted the embedded handoff page and script to `/tmp/tv-player-handoff.html` and `/tmp/tv-player-handoff.js`. `node --check /tmp/tv-player-handoff.js`, assertions for responsive styles, accessible mode groups, collapsed subtitle, and upload-only progress/cancel, and `git diff --check` passed.
- No TV contact, install, launch, or visual browser preview was performed. The static HTML preview is available for narrow and wide browser inspection; do not submit its form because it is a local file without the TV backend.
## Milestone 30: `30-browser-dark-theme`

Changed selected URL/File controls and the primary Send button from bright white surfaces to dark blue/teal states with white text. The file-picker button also uses the dark neutral palette. White text has approximately 10.8:1 contrast on the selected mode fill and 12.3:1 on Send. The existing cyan keyboard focus outline, page/card/field colors, disabled treatment, responsive layout, and all browser-form behavior are unchanged.

Verification record:

- With JDK `17.0.17`, Gradle `8.13`, and SDK `36`, `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks; 25 lint warnings, zero errors).
- Extracted the embedded page and script to `/tmp/tv-player-handoff.html` and `/tmp/tv-player-handoff.js`. `node --check /tmp/tv-player-handoff.js`, assertions that selected/Send/file-picker surfaces are dark with visible focus and disabled styles, and `git diff --check` passed.
- No TV contact, install, launch, or browser visual preview was performed. The dark color states are source-checked; browser rendering remains for a later preview.

## Milestone 31: `31-video-send-history`

The paired phone form keeps Send to TV disabled until the active video URL has non-whitespace content or a video file is selected. Validity updates as the user edits the URL, chooses a file, switches source modes, and leaves a busy submission; switching modes still clears the inactive source. Recent video links are collapsed behind an accessible “Recent links” button and collapse after selection or video source entry. The existing localStorage history key, 24-hour expiry, bounded entries, safe labels, and subtitle-history behavior remain intact. The global clear control appears when a history section containing entries is visible.

Verification record:

- Extracted the embedded script to `/tmp/tv-player-handoff.js`; `node --check` and 10 focused source assertions passed for initial disabled state, whitespace trimming, active URL/file selection, mode/busy recalculation, accessible collapsed history, collapse after selection/source entry, local TTL storage, and clear-control visibility. `git diff --check` passed.
- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks; 12 executed, 36 up-to-date).
- No TV contact, install, launch, or browser visual preview was performed.

## Milestone 32: `32-fullscreen-dark-timeline`

Refined the empty fullscreen player into a friendly “Your screen. Your video.” state that explains phone handoff and direct-link entry, with a remote-focusable Add a video link action. The playback controls keep the shared dark-at-rest, white-on-focus `AppButton` colors. Moved the playback seek timeline beneath the controls at the bottom edge, reduced resting timeline brightness, and made elapsed/total time labels smaller and more transparent. Focused seeking remains outlined and brighter, and the auto-hidden timeline remains visible with lower contrast.

Verification record:

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks; 12 executed, 36 up-to-date).
- `git diff --check` and eight focused assertions passed for the empty-state copy/action, shared button colors, timeline placement, seek focus styling, subdued hidden progress, and retained auto-hide timing.
- No TV contact, install, launch, or visual preview was performed; remote focus and on-screen positioning remain source-checked only.

## Milestone 33: `33-handoff-input-actions`

Added Clear and Paste actions beside both URL inputs. Paste reads clipboard text only through the browser Clipboard API from the button gesture; when HTTP clipboard access is unavailable or denied, the page focuses the field and explains how to paste manually. Replaced native file controls with touch-friendly, keyboard-operable dark dropzones that open the native picker, validate supported extensions, show the selected filename and size, and support drag-and-drop. Clear resets the underlying file input. URL/File exclusivity, optional collapsed subtitle behavior, upload-size checks, busy locking, and explicit Send-to-TV uploads remain intact.

Verification record:

- Extracted the embedded handoff page and script to `/tmp/tv-player-handoff.html` and `/tmp/tv-player-handoff.js`; `node --check` and 13 focused assertions passed for URL clearing, clipboard fallback guidance, picker and keyboard activation, DataTransfer drop, extension filtering, source validity, upload-size enforcement, and Send-only transfer.
- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks; 12 executed, 36 up-to-date). `git diff --check` passed.
- No TV contact, install, launch, or browser visual preview was performed.

## Milestone 34: `34-fullscreen-buffer-option`

Added the existing maximum playback buffer setting to the fullscreen Player Options screen. The remote-focusable 1, 2, and 5 minute choices show the selected value and explain that the choice applies to the next video. Selecting a value updates the playback policy immediately and persists it through the existing settings repository; the Privacy & History Settings screen remains available with its existing choice.

Verification record:

- Focused source assertions confirmed all three choices, selected-state labeling, next-video guidance, and both playback and persistence callbacks. `git diff --check` passed.
- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (48 tasks; 12 executed, 36 up-to-date). `git diff --check` passed.
- No TV contact, install, launch, or visual preview was performed. Remote focus, selection, and persistence behavior remain unverified on-device.

## Milestone 35: `35-icon-player-controls`

Replaced the fullscreen transport row's visible “−30 sec”, “+30 sec”, “Pause”, and “Play” labels with text-style rewind, forward, pause, and play symbols. The buttons retain their existing ±30-second seek and toggle actions, and each now exposes an accessible description. CC, Audio, Options, playback speed, timeline, and focus treatment remain as before.

Verification record:

- Focused source assertions confirmed icon-only transport labels, exact ±30,000 ms seek callbacks, play/pause toggle wiring and state, and accessible descriptions. `git diff --check` passed.
- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed successfully.
- No TV contact, install, launch, or visual preview was performed. Android TV font rendering and on-device assistive-technology announcements remain unverified.

## Milestone 36: `36-center-player-controls`

Centered the rewind, play/pause, forward, and playback-speed controls across the fullscreen controller's available width. CC and optional Audio remain aligned at the left. At narrow widths the two groups stack, keeping the centered transport cluster clear of the left controls. Existing button actions, accessible descriptions, focus requester, icon-only labels, shared dark/white focus colors, and timeline position are retained.

Verification record:

- Focused source assertions confirmed the centered transport layout, narrow-width stacking, seek/toggle/speed actions, accessible descriptions, and unchanged timeline placement. `git diff --check` passed.
- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL`.
- No TV contact, install, launch, or visual preview was performed. Runtime focus behavior and visual spacing remain unverified on-device.

## Milestone 37: `37-auto-send-history`

Recent video URL buttons now fill URL mode and call the same guarded Send operation as the manual button. Recent subtitle URLs do the same only when a syntactically valid active HTTP(S) video URL or a selected video file is ready; otherwise they fill the subtitle field and direct the user to choose a video first. The shared submit function checks `formBusy` synchronously, updates status before pairing/upload/play submission, and retains the existing approval, token, origin, and upload paths. History storage and manual Send behavior remain in place.

Verification record:

- Embedded handoff JavaScript passed `node --check`; focused source assertions passed for video history auto-submit, subtitle active-source guard, manual Send wiring, the shared `formBusy` guard, and unchanged 24-hour/20-entry history limits. `git diff --check` passed.
- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed with `BUILD SUCCESSFUL`.
- No TV contact, install, launch, or preview was performed.

## Milestone 66: `66-recent-history-highlight-no-motion`

QR/handoff recent replay buttons keep the existing white focused highlight, accessible row label, and replay action, with their focused scale fixed at 1 so focus does not enlarge or animate the row. `AppButton` now accepts an optional scale override and retains its normal TV Material scale by default, leaving all unrelated buttons unchanged.

Verification record:

- Focused source checks confirmed only recent replay buttons set a fixed focused scale; the standard `AppButton` default, row text, and `onReplay` callback remain in place.
- `git diff --check` passed. `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`.
- No TV contact, install, launch, or preview was performed.

## Milestone 67: `67-space-qr-recent-rows`

Added 8dp of vertical space between consecutive QR/handoff recent entries, applying equally to replay buttons and legacy informational rows. Recent-row text size, the fixed focused scale, existing color highlight, and replay behavior remain unchanged.

Verification record:

- Focused source checks confirmed the row gap is applied between every consecutive entry, including legacy entries, while the replay scale, text size, focus colors, and replay callback remain unchanged.
- `git diff --check` passed. `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`.
- No TV contact, install, launch, or preview was performed.

## Milestone 38: `38-seedr-player-controls`

Reshaped the fullscreen controller around the supplied Seedr Android TV reference: circular dark controls turn white when focused, with a circular back/settings header, a thin timeline and elapsed/total labels directly above the controls, subtitle/audio buttons at left, centered transport buttons, and an options button at right. Visible skip buttons seek by 10 seconds and expose matching accessible labels. D-pad Left/Right while controls are hidden still seeks by 30 seconds; timeline focus still seeks by 10 seconds. Playback speed remains available in Player Options, and the existing auto-hide, title, subtitle/audio, buffering, and error behavior remain wired.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 tasks; 4 executed, 33 up-to-date). `git diff --check` and focused source assertions confirmed the circular control styling, timeline above the control row, visible ±10-second seek wiring, hidden-control ±30-second seeking, and retained options and subtitle/audio actions.
- No TV contact, install, launch, or preview was performed. Layout, button focus colors, and remote navigation need visual device verification.

## Milestone 39: `39-hidden-progress-time-labels`

Kept the visible fullscreen timeline above the controller row and added elapsed and total time labels around the minimal progress bar while controls are hidden. The hidden timeline retains its bottom placement and side inset, with compact text styling and no buttons.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 tasks; 4 executed, 33 up-to-date). `git diff --check` passed.
- No TV contact, install, launch, or preview was performed.

## Milestone 40: `40-right-side-playback-speed`

Added a compact playback-speed button to the right side of the fullscreen controller row, adjacent to Player Options. The button shows the current speed, exposes a playback-speed accessibility description, and cycles the existing speed choices. The transport controls remain centered independently of the left and right control groups, and Options retains its existing focus requester.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`; `git diff --check` passed.
- No TV contact, install, launch, or preview was performed.

## Milestone 41: `41-balanced-player-controls`

Balanced the fullscreen controller row by keeping the centered transport group, left subtitle/audio controls, and a single playback-speed control at the far right. The row matches the 64dp play button height, and content is explicitly centered inside each circular button. Transport symbols use centered vector shapes to avoid font glyph baseline differences. Player Options remains available from the header gear, which also returns focus when Options closes; opening Options from Audio returns focus to Audio. Auto-hide, D-pad skips, and both visible and hidden progress/time displays remain in place.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`; `git diff --check` passed.
- No TV contact, install, launch, or preview was performed.

## Milestone 42: `42-half-height-fullscreen-progress`

Reduced the visible fullscreen controller timeline's background, buffered, and played tracks from 4dp to 2dp, with matching 2dp rounded corners. The 12dp focus and seek interaction area, playhead, controller row, and time labels remain unchanged.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`; `git diff --check` passed.
- No TV contact, install, launch, or preview was performed. The visual result remains unverified on-device.

## Milestone 43: `43-handoff-port-retry`

Handoff startup now records the exact `ServerSocket` created by this process. If binding port 8765 fails, it closes and retries once only when that process-local ownership record identifies an earlier app listener; an unknown listener is left alone. The failure text describes the port as unavailable and the handoff screen shows a remote-focusable Retry action. The QR continues to render from the local page URL immediately after a successful bind publishes the LAN address.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 tasks; 4 executed, 33 up-to-date). `git diff --check` passed.
- Source inspection established that Compose retains one remembered server for the Handoff screen and starts/stops it on Activity lifecycle events. The prior `BindException` message did not identify the owning process, so the original incident's actual port owner cannot be proven from repository evidence alone.
- No TV contact, install, launch, or live port-owner check was performed. Retry behavior against a real stale listener and QR visibility remain unverified on-device.

## Milestone 44: `44-hide-fullscreen-time-labels`

Removed the elapsed and total time labels and their side gaps from the visible fullscreen progress row. The seekable 12dp progress area, remote focus and seeking behavior, transport row, and elapsed/total labels on the progress bar shown while controls are hidden remain unchanged.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed successfully; `git diff --check` passed.
- No TV contact, install, launch, or preview was performed.

## Milestone 45: `45-transport-skip-adjacency`

Reordered the fullscreen transport controls to Previous, −10 seconds, Play/Pause, +10 seconds, Next. The enabled skip buttons now sit beside Play/Pause; button behavior, labels, disabled state, focus requester, spacing, and diameters are unchanged.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 tasks; 4 executed, 33 up-to-date); `git diff --check` passed.
- No TV contact, install, launch, or preview was performed.

## Milestone 46: `46-transient-seek-times`

The visible ±10-second skip buttons temporarily show elapsed and total time labels beside the fullscreen progress bar. Repeated skips restart the three-second display timer. The labels disappear when the controls hide or Player Options opens; the progress bar keeps its 12dp seeking area, and hidden-control seeking and progress remain unchanged.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed successfully; `git diff --check` passed.
- No TV contact, install, launch, or preview was performed.

## Milestone 47: `47-player-skip-options-quality`

Dedicated remote rewind/fast-forward and media skip-backward/skip-forward keys now seek by 10 seconds and show the transient elapsed/total labels. Hidden-control D-pad Left/Right remains at 30 seconds, and visible D-pad navigation remains in place. Added an ellipsis Options button beside playback speed at the bottom right while retaining the header gear; closing Options returns focus to the button that opened it. Player Options always shows VIDEO QUALITY: multiple variants retain Auto select and the available choices, while single/no-variant sources explain that no alternate quality is available.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date); `git diff --check` passed.
- No TV contact, install, launch, or preview was performed.

## Milestone 48: `48-resume-history-backup`

Enabled Android backup for the app and scoped cloud backup, device transfer, and legacy full backup to the app's `file/datastore/` directory, which stores playback history/resume positions and player settings. Resume history and settings can restore after reinstall only when Android system backup is enabled and a backup has completed for the same Google account or device-transfer dataset. Android Auto Backup may run roughly nightly, so uninstalling soon after a change can happen before that data is backed up. The fullscreen seek rails, buffered/played tracks, and elapsed/total labels now use lower opacity for a translucent appearance; playhead visibility, dimensions, and behavior are unchanged.

Verification record:

- `./gradlew :app:assembleDebug --console=plain` completed successfully; `git diff --check` passed.
- No physical restore test, TV contact, install, launch, or preview was performed.

## Milestone 49: `49-manual-send-after-subtitle`

Recent video and subtitle history selections now fill their URL fields without starting a TV submission. Selecting a recent video leaves the form ready for an optional subtitle, and the status tells the user to press Send to TV when ready. A recent subtitle without a video still directs the user to choose a video first. The explicit Send button remains the only action that invokes the submission flow, preserving pairing, upload validation, and URL/File modes.

Verification record:

- The embedded handoff JavaScript passed `node --check`; focused source assertions confirmed recent-link clicks do not invoke `submitSend`, both recent-link status messages prompt explicit Send, and the Send button remains wired to submission. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- Confirmed the connected ADB target as `BRAVIA_VU3` / `BRAVIA_VU3_4K`, installed `app-debug.apk`, launched `dev.lmadhuranga.tvplayer/.MainActivity`, and verified it is the top resumed activity. The app was left running. No handoff interaction, playback, or subtitle behavior was tested on the TV.

## Milestone 50: `50-long-press-minute-seek`

With fullscreen controllers hidden, D-pad Left/Right short taps still seek 30 seconds, now on KeyUp so an initial press cannot add a duplicate skip before a hold is recognized. The first repeated KeyDown during a hold seeks 60 seconds in that direction; later repeats do not seek again, and the matching KeyUp is consumed. When controllers are visible, Left/Right remains available for focus/navigation.

Verification record:

- Focused source assertions confirmed ±30-second KeyUp actions, ±60-second first-repeat actions, and hidden-key KeyUp consumption; `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- No TV contact, install, launch, or preview was performed. Remote hold timing and visible-controller focus behavior remain unverified on a device.

## Milestone 51: `51-hidden-ok-play-pause`

When fullscreen playback controls are hidden during loaded playback, D-pad Center/OK and Enter/Numpad Enter now toggle play/pause on the initial KeyDown. Repeated KeyDown events do not toggle again, and the matching KeyUp is consumed so a held select press cannot reveal or activate controls. With controls visible, select events continue to normal focus activation. Unloaded playback keeps its prior select behavior, and the media play/pause key is unchanged.

Verification record:

- Focused source assertions passed for select key codes, hidden/loaded gating, initial-KeyDown-only toggling, matching KeyUp consumption, and retained media play/pause handling; `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- No TV contact, install, launch, or preview is authorized or performed.

## Milestone 52: `52-progressive-arrow-seek`

With fullscreen playback controllers hidden, each D-pad Left/Right press begins at the 30-second tap seek, applied on KeyUp only if Android sends no repeat. Android `repeatCount` 1, 2, and 3 advance through 1-minute, 3-minute, and 5-minute seeks respectively; each newly reached stage is applied once, even if an event skips an intermediate repeat count. Counts above 3 are capped, so subsequent OS repeats add no further seek. The matching KeyUp is consumed and resets that direction's stage for the next press. Visible-controller arrow navigation is unchanged.

Verification record:

- Focused source assertions confirmed initial KeyDown does not seek, repeatCount stages map to ±60,000/180,000/300,000 ms at most once, no-repeat KeyUp seeks ±30,000 ms, later repeats are capped, and hidden arrow KeyUp is consumed. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed successfully.
- No TV contact, install, launch, or preview was performed. Remote repeat timing and visible-controller focus behavior remain unverified on a device.

## Milestone 53: `53-remove-slow-speed-options`

Removed 0.5× and 0.75× from playback speed cycling. The remaining choices stay in order: 1×, 1.25×, 1.5×, 1.75×, and 2×. If the player's current speed is outside that list, the next cycle selects 1×. The player does not persist speed as a saved setting, so no saved-speed migration is needed.

Verification record:

- Focused source assertions confirmed the supported speed list and wraparound/fallback behavior; `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed successfully.
- No TV contact, install, launch, or preview was performed.

## Milestone 54: `54-reduce-player-ticker-work`

Split playback-state publication into time-varying playback fields and track-menu data. The 500 ms ticker still updates position, duration, buffered position, playing/buffering state, and speed, but no longer scans Media3 track groups or rebuilds audio/video choices and track overrides. Track choices and embedded subtitle availability refresh on Media3 track changes and explicit audio/video selection actions. This removes repeated track traversal from the timer; it does not establish that device playback performance improved.

Verification record:

- `git diff --check` passed. `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- No TV contact, install, launch, or playback verification was performed.

## Milestone 55: `55-two-minute-arrow-hold`

With fullscreen controllers hidden, D-pad Left/Right taps still seek 30 seconds on KeyUp when the key press has no repeats. The first repeated KeyDown for a held direction seeks exactly two minutes in that direction; all later repeats are ignored. The matching KeyUp is consumed and resets hold state for the next press. Visible-controller arrow navigation remains unchanged.

Verification record:

- Focused source assertions confirmed ±120,000 ms occurs only on the first repeated KeyDown, further repeats are ignored, no-repeat KeyUp seeks exactly ±30,000 ms, hold state resets on KeyUp, and the existing hidden-controls gate preserves visible navigation; `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed successfully.
- No TV contact, install, launch, or preview was performed. Remote repeat timing and visible-controller focus behavior remain unverified on a device.

## Milestone 56: `56-fullscreen-progress-time-labels`

Reduced the visible-controls progress tracks from 2dp to 1dp and the hidden-controls strip from 5dp to 2.5dp, while retaining the 12dp visible seek/focus target and the hidden strip's current placement. Visible controls now continuously show elapsed time on the left and total duration on the right; both labels and the played track use 90% opacity. Base and buffered tracks stay visible at lower opacity. Hidden controls keep the minimal progress strip without persistent time labels; continuous visible labels replace the prior transient time-label display.

Verification record:

- Focused source assertions confirmed persistent labels are gated only by visible controls/options, hidden-control presentation retains no persistent labels, visible seek target remains 12dp, and track heights and opacity match the request. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed successfully.
- No TV contact, install, launch, or preview was performed.

## Milestone 57: `57-repeat-two-minute-hold-seek`

Hidden-control fullscreen Left/Right taps continue to seek 30 seconds on KeyUp. While held, every distinct increase in Android's repeatCount seeks an additional two minutes; duplicate repeat events are ignored, and a skipped count applies all missed two-minute increments. Left and right track their repeat counts independently, and their matching KeyUp is consumed and resets the next press to tap behavior. Visible-control arrow navigation remains unchanged.

Verification record:

- Focused source assertions confirmed tap seeking occurs only on KeyUp when no repeats were received, repeat deltas apply each two-minute increment once, repeat state resets on release, and the hidden-controls gate continues to preserve visible navigation. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed successfully.
- No TV contact, install, launch, or preview was performed.

## Milestone 58: `58-half-progress-opacity`

Halved the opacity of the progress-bar tracks in both fullscreen control states. Visible rail, buffered, and played tracks now use 8%, 14%, and 45% opacity; hidden-control tracks use 6%, 14%, and 45%. The focused playhead, track heights, and time-label opacity remain unchanged.

Verification record:

- Focused source assertions confirmed both track-alpha triplets and that playhead, track heights, and time-label opacity stayed unchanged; `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed successfully.
- No TV contact, install, launch, or preview was performed.

## Milestone 59: `59-tv-playback-history`

Playback history now saves direct HTTP(S) video URLs and positions by default, while preserving an explicit opt-out. Rows deduplicate by normalized scheme, host, and path, so refreshed signed queries replace the saved URL without creating duplicate items. Home offers focusable Recently played actions, legacy history remains readable, temporary uploads stay non-replayable, and Settings explains on-device URL retention, link expiry, and app-data backups. Common refused, expired, and missing-link responses direct the user to enter a fresh link and retry.

Verification record:

- Focused source assertions passed for canonical URL identity and hashing, legacy entries without `mediaUrl`, default-on/explicit-off behavior, start and periodic saves, upload URL exclusion, Home replay and identity matching, expiry status copy, and subtitle URL exclusion. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed successfully.
- ADB identified `192.168.8.100:5555` as `product:BRAVIA_VU3 model:BRAVIA_VU3`; the debug APK installed successfully, and `dumpsys activity` reported `dev.lmadhuranga.tvplayer/.MainActivity` as `ResumedActivity`. Playback of a media URL and on-screen D-pad behavior were not exercised.

## Milestone 60: `60-handoff-recent-history`

The initial QR/handoff screen now shows up to three deduplicated recent videos with their title, source host, and saved position. Entries with a valid saved URL are focusable replay buttons; replay restores positions beyond five seconds and starts at the beginning otherwise. Legacy rows without a URL remain informational and cannot be replayed. The history sits in the existing scrollable content, leaving the QR tile, phone pairing approval, and existing controls in place.

Verification record:

- `git diff --check` passed. `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- ADB identified `192.168.8.100:5555` as Sony `BRAVIA VU3` (`BRAVIA_VU3_4K`), API 34. The latest debug APK installed successfully. `dumpsys activity activities` reported `dev.lmadhuranga.tvplayer/.MainActivity` as top-resumed and focused, and the app was left open.
- No D-pad input, screenshot, playback, or visible history-row interaction was exercised. The list layout and replay interaction still need visual/runtime confirmation.

## Milestone 61: `61-history-subtitle-url`

Playback history now stores validated HTTP(S) subtitle URLs alongside replayable HTTP(S) video URLs. Older entries without `subtitleUrl` still decode with a null subtitle. History updates retain a prior subtitle when the same normalized video is played without a new subtitle URL; a newly provided valid URL replaces it. Home and QR/handoff history replay both restore the saved subtitle and position. Resume prompts only match a valid same-video identity, and a newly submitted subtitle URL remains pending through that prompt. Settings now explains that video and subtitle URLs can be stored and backed up and may expire. Clear History removes the containing records and subtitle metadata. Temporary uploaded subtitle files remain deleted after playback and are not persisted or replayable.

Verification record:

- Focused Python source assertions passed for legacy decoding, validated subtitle URL encode/decode, URL-only persistence, preservation/replacement on same-identity saves, Home and handoff replay arguments, same-identity resume matching, clear-history removal, and the privacy copy. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- ADB discovery found `192.168.8.100:5555` and verified `product:BRAVIA_VU3`, model `BRAVIA VU3`, device `BRAVIA_VU3_4K`, API 34. The debug APK installed successfully, `MainActivity` launched, and `dumpsys activity` reported it as `ResumedActivity`; the app was left open. Uploaded subtitle file replay and subtitle rendering were not exercised.

## Milestone 62: `62-handoff-history-layout`

Each recent handoff item is now rendered as one single-line row. Replayable entries remain AppButtons and preserve the existing replay callback; legacy entries remain informational and remote-focusable. Long titles, sources, and positions truncate with an ellipsis. Settings and Back now sit in a vertically scrollable right-side column under the QR code, while the existing handoff text and QR content remain in scrollable columns. Retry and Go to Player remain alongside the left-side content when available.

Verification record:

- Focused source assertions verified the one-line/truncated row content, replay callback preservation, legacy-row focusability, and relocation of the Settings requester/button to the QR-side column. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- ADB rediscovered `192.168.8.100:5555` and confirmed `product:BRAVIA_VU3`, model `BRAVIA_VU3`, device `BRAVIA_VU3_4K`. The debug APK installed, `am start -W` confirmed `dev.lmadhuranga.tvplayer/.MainActivity`, and `dumpsys activity` reported it as `topResumedActivity`; the app was left open. Remote interaction and visual layout were not separately exercised.

## Milestone 63: `63-simplify-qr-page-copy`

Reduced the QR/handoff page's static copy to “SEND TO TV,” a short scan instruction, and a direct `http://IP:8765` fallback labeled “Or open.” Recent history now uses the concise “RECENT” heading and “No videos yet” empty state. Dynamic server messages, QR, pending approval dialog, recent rows, Settings/Back, and layout/focus behavior remain in place.

Verification record:

- Focused source checks verified concise labels, the dynamic IP:8765 URL, server messages, recent items, approval dialog, Settings and Back controls, and unchanged row/focus structure. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`.
- No TV contact, install, launch, or preview was performed.

## Milestone 64: `64-swap-fullscreen-time-labels`

Swapped the fullscreen playback-controls time labels so total duration appears to the left of the progress bar and elapsed/current position appears to the right. Their accessibility descriptions move with the corresponding values. Progress calculation, seeking, and the hidden-controls progress strip are unchanged.

Verification record:

- Focused source checks confirmed total duration precedes the progress bar, elapsed time follows it, the accessibility descriptions match each displayed value, and seek/progress wiring and hidden-controls labels are unchanged. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- No TV contact, install, launch, or preview was performed.

## Milestone 65: `65-center-pause-icon-and-handoff-back`

Added a centered pause indicator over the fullscreen video while loaded media is paused and not buffering or in an error state. The indicator uses the existing pause glyph, has a “Video paused” accessibility description, and does not take remote focus. On the QR/handoff page, Settings and Back now sit in one row with Back immediately to the right of Settings. Existing callbacks and the Settings focus requester are retained.

Verification record:

- Focused source checks confirmed centered placement and paused-state gating, retained semantic description, side-by-side button order, and preserved callbacks and focus requester.
- `git diff --check` passed. `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- No TV contact, install, launch, or preview was performed.

## Milestone 68: `68-home-to-qr-navigation`

Added a Home “Send to TV” action beside Settings that opens the existing QR/Handoff screen. Settings keeps its focus requester and return-focus behavior; Recently played actions and Handoff Back-to-Home routing are unchanged.

Verification record:

- Focused source checks confirmed the Home action calls `AppRoute.HANDOFF`, Settings retains its focus requester, Recently played replay wiring remains present, and Handoff Back still targets Home. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (37 actionable tasks; 4 executed, 33 up-to-date).
- No TV contact, install, launch, or preview was performed.

## Milestone 69: `69-qr-build-version-label`

The QR/Handoff page now displays the app version from generated `BuildConfig.VERSION_NAME` below its Settings and Back actions. Gradle explicitly enables BuildConfig generation; the configured version name and code are unchanged.

Verification record:

- Focused source checks confirmed the label reads `BuildConfig.VERSION_NAME`, BuildConfig generation is enabled, and version metadata remains unchanged. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`.
- No TV contact, install, launch, or preview was performed.

## Milestone 70: `70-fix-centered-paused-icon`

The centered pause indicator now sets its Surface content color to white. Its canvas glyph reads `LocalContentColor`, so the explicit value makes the glyph visible over the translucent dark circle. The indicator remains centered and retains its loaded, paused, non-buffering, error-free condition; it remains non-focusable and non-clickable. Hidden progress and time labels are unchanged.

Verification record:

- Focused source assertions confirmed explicit white Surface content color, unchanged center alignment and state condition, no focus/click modifier, and unchanged hidden progress and label order. `git diff --check` passed.
- `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL`.
- No TV contact, install, launch, input, or screenshot was performed; device visibility remains unverified.

## Milestone 71: `71-outline-paused-indicator`

Replaced the paused overlay Surface and its theme-dependent Canvas glyph with an explicitly drawn transparent circle, white outline, and white pause bars outlined in black. It appears centered only when loaded media is paused, not buffering or in an error state, and playback controls are hidden. The Canvas draws the complete symbol with explicit colors, so visibility does not depend on a Material content-color local. When controls are hidden, both timeline time labels are now hidden too; they appear for 1.2 seconds after Left/Right seek feedback, then disappear while the progress bar remains visible.

Verification record:

- Source review confirmed explicit circle outline and glyph drawing, hidden-controls and paused-state gating, centering, and the “Video paused” accessibility description. `git diff --check` passed.
- `git diff --check` passed. `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (50 actionable tasks; 12 executed, 38 up-to-date).
- The final debug APK installed and launched on the authorized Sony BRAVIA VU3 (Android 14, `192.168.8.100:5555`). Using a hidden Left seek, the live screen showed both time labels briefly, then removed them while retaining the bottom progress bar. The saved media links subsequently buffered only 0–4 seconds, so seek movement and the final white outline in a paused frame could not be confirmed on the final installation. An earlier revision showed the centered pause bars on the TV while paused; the circle color was corrected to white afterward. The app was returned to the handoff page and its QR/send URL were visible.

## Milestone 72: `72-home-recent-title-ellipsis`

Limit each recently played title on the Home screen to one line and show an ellipsis when the filename exceeds the available width. QR/Handoff history and all other behavior remain unchanged.

Verification record:

- Focused source checks confirmed the single-line ellipsis is applied only to Home's recent-title Text and the Handoff screen source is unchanged. `git diff --check` passed.
- `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (50 actionable tasks; 12 executed, 38 up-to-date).
- No TV contact, installation, launch, input, or screenshot was performed.

## Milestone 73: `73-usb-video-browser`

Added a Home “Browse USB videos” action and an in-app TV-remote browser that lists indexed video files only from mounted removable MediaStore volumes. The browser shows each filename, USB folder, and size; selecting a row plays its MediaStore `content://` URI in the existing Media3 player and uses the provider's display name as the player title. The app requests Android's video-read permission at runtime (`READ_MEDIA_VIDEO`, or `READ_EXTERNAL_STORAGE` on older Android); it does not read raw file paths or request all-files access. Home content scrolls when needed so the new action does not push existing controls out of reach.

Verification record:

- `git diff --check` passed. `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed successfully (50 actionable tasks; 13 executed, 37 up-to-date; lint reported existing warnings but no errors).
- The connected device was verified as Sony BRAVIA VU3, Android 14 / API 34, at `192.168.8.100:5555`. `/storage/A230-1BEC` was mounted as a removable FAT volume. The TV does not provide a DocumentsUI picker activity; an initial `OpenDocument` attempt showed “You don't have an app that can do this.” A read-only MediaStore query confirmed videos are indexed under volume `a230-1bec`, so the feature uses a custom MediaStore browser scoped to mounted removable volumes.
- Installed and launched the debug APK. Home's new button was reachable using the D-pad. The TV granted `READ_MEDIA_VIDEO`; the in-app list populated with the USB's indexed videos and displayed file names, folders, and sizes. Selecting `50.First.Dates.2004.720p.BrRip.x264.YIFY.mp4` opened the existing player. The player showed the selected filename, video dimensions `1280 × 528`, and an advancing playhead (0:36) with the playing control state.
- Temporary captures: `/private/tmp/tv-player-73-home.png`, `/private/tmp/tv-player-73-browser.png`, and `/private/tmp/tv-player-73-playback-state.png`. The playback capture's video surface is black, so the capture confirms URI opening, media metadata, and advancing playback state but does not independently confirm a visible decoded frame on the TV panel.

## Milestone 76: `76-usb-folders-subtitles`

Expanded the USB browser from a flat MediaStore list to mounted removable-volume and folder navigation. Android 11+ uses `StorageManager` volume roots and direct file paths after the user enables the app's all-files access setting, allowing sibling subtitle discovery where the TV has no document picker. Videos show a same-folder subtitle choice: a unique case-insensitive basename match is preselected; if there is no basename match, only a sole SRT/WebVTT file is preselected. Multiple candidates are left unselected and require the user to choose. Selected USB videos play through the existing Media3 path, while sidecars are copied into the private subtitle cache without modifying/deleting the USB file. The prior indexed MediaStore video path remains as an explicit fallback when broad access is unavailable; it does not provide folder or subtitle browsing.

Verification record:

- Added unit coverage for basename precedence, sole-subtitle fallback, ambiguous folders, duplicate basename matches, and ignored extensions.
- Android documentation confirms `MANAGE_EXTERNAL_STORAGE` grants shared-storage paths and USB OTG roots and requires a system Settings route; this app checks the grant rather than assuming manifest declaration is sufficient.
- `git diff --check` passed. `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (55 actionable tasks; 14 executed, 41 up-to-date). All five subtitle-matcher tests passed. Lint reports Android's expected `ScopedStorage` warning for `MANAGE_EXTERNAL_STORAGE` because Google Play restricts this permission; other SDK XML and pre-existing NDK metadata warnings remain.
- No device contact, install, launch, input, or runtime validation was performed for this milestone.

## Milestone 77: `77-playback-keep-awake`

The player now sets Android's `FLAG_KEEP_SCREEN_ON` while a media source has an active play intent and Media3 has not ended or failed. This covers URL, USB/document, and local-file playback, including startup and buffering. Pausing, playback errors/stalls, ending, leaving the player, or stopping the activity clears the flag; disposal of the fullscreen player also clears it defensively. Android TV platform guidance identifies this flag as the supported way to prevent Ambient Mode during active video playback.

Verification record:

- Cause evidence: neither the activity nor player view previously set `FLAG_KEEP_SCREEN_ON` / `keepScreenOn`; Android's TV guidance says an activity without this flag enters Ambient Mode after inactivity.
- Added unit coverage for intended playback during buffering and flag release after pause, end, playback error, or missing media.
- `git diff --check` and `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` passed. Lint reports 28 existing warnings and 0 errors, including the known Google Play restriction warning for `MANAGE_EXTERNAL_STORAGE`.
- No TV contact, installation, or runtime verification was performed.

## Milestone 78: `78-usb-all-folder-files`

USB folders now display every regular file and directory, including hidden entries, with folders sorted first. Folder and supported-video rows remain actionable; subtitle and unsupported-file rows show their type and size but cannot start playback. Subtitles remain available from the existing video selection flow. A truly empty folder has a dedicated message, and an enumeration failure is reported as a read error.

Verification record:

- [x] `git diff --check` passed. `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (50 actionable tasks; 13 executed, 37 up-to-date). No TV runtime verification was performed.
- [ ] TV browsing, D-pad focus, playback selection, and empty/read-error states verified manually.
- No TV contact, installation, or runtime validation is implied.

## Milestone 79: `79-usb-folder-counts`

USB drive and directory rows now show immediate child directory/file counts, including hidden entries, with singular/plural labels. Counts are collected alongside the existing directory listing on IO dispatchers; unreadable directories show “Contents unavailable” instead of a false zero. File rows retain their type and size labels. Counts are not recursive.

Verification record:

- [x] `git diff --check` passed. `./gradlew :app:assembleDebug :app:lintDebug --console=plain` completed `BUILD SUCCESSFUL` (50 actionable tasks; 12 executed, 38 up-to-date).
- [ ] Verify on the TV that counts match mixed folders, singular/plural labels read correctly, and unreadable folders report unavailable counts.
- No TV contact, installation, or runtime verification was performed.

## Milestone 80: `80-hide-unavailable-file-size`

USB file rows now omit the size and its separator when size metadata is unavailable. Indexed MediaStore rows preserve missing or negative size values as unknown, while readable direct files retain real sizes, including zero-byte files. Known sub-megabyte sizes display in bytes or kilobytes. Folder and file counts and row type labels are unchanged.

Verification record:

- [x] `git diff --check` and `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed (`BUILD SUCCESSFUL`; 50 actionable tasks, 12 executed, 38 up-to-date).
- [ ] TV runtime verification not performed.
- No TV contact, installation, or runtime validation is implied.

## Milestone 81: `81-usb-explorer-list-design`

USB drives, parent navigation, folders, indexed videos, and direct file entries now use compact explorer rows rather than pill-shaped AppButtons. Rows use simple drawn folder, drive, video, file, and up icons, a single metadata line, and a trailing chevron for folders. Focus adds a restrained background and outline without changing row geometry or text size. Unsupported and subtitle files remain visible as subdued non-actionable rows. The browser uses a keyed `LazyColumn` so large folders are composed as needed; existing Back handling and the separate subtitle-choice screen are unchanged.

Verification record:

- [x] `./gradlew :app:assembleDebug :app:lintDebug --console=plain` passed (50 actionable tasks; 13 executed, 37 up-to-date).
- [ ] D-pad focus, row activation, long-folder scrolling, and parent navigation remain pending TV runtime verification.
- No TV contact, installation, or runtime validation was performed.

## Milestone 82: `82-player-loading-network-speed`

The fullscreen player now shows an animated “Loading...” pill whenever Media3 reports buffering, including while controls are visible. The receive-speed label uses the app UID's `TrafficStats` RX byte counter, samples about once per second, and formats bytes as KB/s or MB/s. Sampling runs only while media is loaded, controls are visible, and the Activity lifecycle is started; it resets to a fresh baseline after hidden/background periods. The header Options gear is removed, while the bottom-row Options entry continues to open settings and restore focus. Added focused unit coverage for throughput calculations, unsupported/reset samples, and byte-based formatting.

Verification record:

- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` passed (`BUILD SUCCESSFUL`; 55 actionable tasks, 17 executed, 38 up-to-date).
- [ ] URL, browser-upload, USB buffering states, visible/hidden speed behavior, and bottom Options focus remain pending runtime verification.
- No TV contact, installation, or runtime validation was performed for this change.

## Milestone 83: `83-buffering-dots-only`

The centered buffering indicator now displays only its existing animated dots. In the visible player controls, receive speed appears beside the resolution or other playback metadata instead of at the far right of the header. Buffering detection and the speed sampling/visibility rules are unchanged.

Verification record:

- [x] `git diff --check` passed.
- [x] `./gradlew :app:assembleDebug --console=plain` passed.
- [ ] Buffering indicator appearance remains pending TV runtime verification.
- No TV contact, installation, or runtime verification was performed for this change.

## Milestone 84: `84-explorer-text-contrast`

USB explorer row names now use explicit white text when actionable and readable medium gray when disabled. Metadata keeps its existing muted color for enabled rows and uses a lighter gray for disabled rows, improving legibility over both normal and focused dark row backgrounds. Font sizes, focus highlight, row layout, and actions are unchanged.

Verification record:

- [x] `git diff --check` passed. `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (39 actionable tasks; 4 executed, 35 up-to-date).
- [ ] TV runtime appearance pending manual verification; no TV contact, installation, or runtime validation performed.

## Milestone 85: `85-player-timer-order`

The visible player timeline now shows elapsed playback time on the left and total movie duration on the right, with matching accessibility descriptions. The hidden-controller seek row already used this order and remains unchanged. USB root and folder counts now list files before folders while preserving pluralization and unavailable-count handling.

Verification record:

- [x] `./gradlew :app:assembleDebug --console=plain` passed (39 actionable tasks; 4 executed, 35 up-to-date).
- [x] `git diff --check` passed.
- No TV contact, installation, or runtime validation performed.

## Milestone 86: `86-usb-bottom-parent`

The USB explorer shows a friendly `USB drive > …` breadcrumb in a fixed footer below the scrolling folder list, with the complete path preserved for accessibility when the visible line truncates. The header keeps the current folder title. The `../` parent row follows folder contents, including empty-folder and error views, and the Back to Home button is removed. The existing remote Back handler leaves the browser from the drive list, and Left navigates to the parent from folder context. Initial focus scrolls to and targets the first actionable folder or file, the parent row when a folder has no actionable entries, or the first available access, indexed-video, scan, or drive action. Explorer rows, immediate folder counts, and subtitle matching omit dot-prefixed entries; the synthetic `../` parent row remains visible.

Verification record:

- [x] `git diff --check` passed.
- [x] `./gradlew :app:assembleDebug --console=plain` passed (39 actionable tasks; 4 executed, 35 up-to-date).
- [ ] USB navigation, focus, and hidden-directory behavior remain pending TV runtime verification.
- No TV contact, installation, or runtime verification was performed.

## Milestone 87: `87-network-file-manager`

Added an “Open File Manager” link to the existing same-network handoff page and a responsive dark browser page with a storage/folder tree, back/up/refresh controls, breadcrumbs, grid/list views, item counts, known file sizes, and destination free space. Browsing and file writes use the existing TV-approved browser token, and the TV approval prompt clearly includes storage management. Reads reject a supplied cross-origin Origin; every write requires the exact local origin and active token. Server-side roots are rediscovered from mounted removable volumes and the public Movies folder. Requests use root IDs and relative paths; canonical containment, hidden/reserved-name filtering, symlink rejection (including links targeting another location inside the same root), mount revalidation, and write-permission checks protect the storage boundary. Uploads stream up to 8 GiB to same-directory temporary files, preserve a 16 MiB reserve, reject overwrites, sync before a no-replace move, report progress, and clean incomplete files on interruption or cancellation. Before a new upload, only files matching the app-generated temporary filename pattern are removed; concurrent uploads and symlinks are skipped. The browser supports creating folders and deleting selected files or empty folders after naming the selected item in a confirmation. No rename, move, download, or folder-upload action is exposed.

When Android's all-files permission is unavailable on Android 11+, removable drives are omitted and the browser reports the access requirement. Android 8 and 9 request both read and write storage permissions for browsing; Android 10 remains read-only because scoped storage blocks these direct writes. Public Movies is shown only when it is readable, and each destination is checked again during operations.

Verification record:

- [x] `./gradlew :app:testDebugUnitTest` passed after adding symlink and cancelled-upload regression cases.
- [x] After integrating the compact explorer change into branch 92, `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` passed (`BUILD SUCCESSFUL`; 55 actionable tasks, 16 executed, 39 up-to-date).
- [x] Embedded File Manager JavaScript passed `node --check`.
- [x] `git diff --check` passed for the integration.
- [ ] Browser screenshot/interaction verification remains pending because the sandbox refused the local mock server bind and the shared Playwright browser profile was locked; no TV contact, install, or runtime verification was performed.

## Milestone 88: `88-tv-explorer-space`

The USB browser now uses compact page padding, a single 24 sp folder heading, 52 dp rows, narrower list insets, and a smaller fixed breadcrumb footer. This yields roughly seven visible rows on a 960 × 540 logical display, up from about four. The synthetic `../` row is removed from normal, empty, and error listings; Left still opens the parent folder and remote Back retains its existing navigation. Empty or unsupported-only folder views request focus on the browser container so Left and Back remain available without targeting a removed row, while error views and populated lists target their first available action.

Verification record:

- [x] `git diff --check` and integrated branch `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` passed (`BUILD SUCCESSFUL`; 55 actionable tasks, 16 executed, 39 up-to-date); embedded File Manager JavaScript passed `node --check`.
- [ ] TV visual and remote runtime behavior remain pending; no TV contact or installation was performed.

## Milestone 95: `95-usb-subtitle-actions`

Removed the “Play without subtitles” and “Back to USB folders” actions from the USB subtitle-choice screen. Subtitle selection and “Play video” remain available, and remote Back continues to return to the USB browser. Initial focus now targets the selected subtitle, the first subtitle when none is preselected, or “Play video” when no subtitles are available.

Verification record:

- [x] `git diff --check` passed. `./gradlew :app:assembleDebug --console=plain` completed `BUILD SUCCESSFUL` (39 actionable tasks; 4 executed, 35 up-to-date).
- [ ] TV remote focus behavior pending runtime verification; no TV contact or installation performed.

## Milestone 97: `97-usb-playback-return-focus`

USB file playback now returns to the retained USB browser folder, rechecks that the drive is still mounted, reloads the folder, and restores scroll and focus to the played video. If the drive was removed, the browser stays open with a reconnect message. Indexed USB playback also retains the USB browser as its return route. URL playback, recent URL replay, browser uploads, and resume prompts continue returning to Send to TV. The subtitle choice screen now initially focuses **Play video** while keeping the automatically selected subtitle and allowing the user to change it.

Verification record:

- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` passed (`BUILD SUCCESSFUL`; 55 actionable tasks, 15 executed, 40 up-to-date).
- [x] `git diff --check` passed.
- [ ] Remote-control route and focus behavior, including unplugging the drive during playback, remain pending physical TV verification; no TV contact or installation was performed.

## Milestone 98: `98-show-hidden-files-folders`

Added a persisted **Show hidden files and folders** option to TV Settings, defaulting to Off. The preference controls dot-prefixed entries in mounted USB root and folder listings, MediaStore indexed-video results, and immediate child counts. When enabled, dot-prefixed subtitle files appear for manual selection; automatic subtitle matching still ignores them so metadata sidecars such as `._movie.srt` cannot be selected by fallback. Changing the preference refreshes a visible USB browser folder or root list. The web File Manager's independent hidden-file policy and its security checks are unchanged.

Verification record:

- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` passed (`BUILD SUCCESSFUL`; 55 actionable tasks, 18 executed, 37 up-to-date). Lint completed without errors.
- [x] `git diff --check` passed.
- [ ] Physical TV Settings focus, persistence after app restart, and USB visibility/count behavior remain pending manual validation; no TV contact or installation was performed by this branch implementation.
