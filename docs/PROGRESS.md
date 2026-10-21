# Progress checkpoint

## Current state

- Date: 2026-10-21
- Implemented through milestone: `12-device-qa-release` (source complete; all verification pending)
- Current branch: `12-device-qa-release`
- Planning baseline: `be2f7cc`; milestones 2–12 are committed sequentially on separate local branches (full commit mapping below).
- No TV connection, installation, or live testing has occurred. User authorized continuing core source implementation through milestone 12 without running builds or tests; validation remains pending.

## Decisions recorded

- Native Kotlin Android TV app; Compose for TV, Media3 ExoPlayer, and DataStore are the initial technology direction.
- App configuration uses minimum API 26 and compile/target API 36. Build compatibility and target-device behavior remain unverified.
- A TV-hosted page accepts links from a same-Wi-Fi browser. It submits only the URLs; video and subtitle data are fetched by the TV.
- Direct HTTP(S) playback is the baseline. Seedr integration is deferred behind a verified authentication/support gate.
- Remote navigation and reliable focus behavior are core requirements.
- Recent history is local and privacy-conscious, with clear-history and deliberate handling of signed URL replacement/resume.
- Feature work proceeds in numbered branches stacked from the last accepted commit; commit each feature before proceeding. No pushes, repository creation, or merge without an explicit request.
- The app UI, settings, errors, documentation, and examples are English-only; subtitle content may use any language/Unicode supported by playback.
- The user authorized continuous source implementation through milestone 12 without intermediate approvals. Build and test commands were deferred during this source-only pass. Do not describe implementation as compiled, tested, or device-validated until those checks are explicitly run and recorded.

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
| 12 | `12-device-qa-release` | pending this commit |

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

Added a small HTTP endpoint that lives only while the handoff screen is foregrounded. It serves a responsive link form and QR code, generates a fresh 256-bit pairing code per session, and accepts only bounded JSON link submissions. Requests are limited to a 12 KiB body, 16 KiB of headers, and 4,096-character HTTP(S) video/subtitle links plus an optional 100-character title. The receiver requires a same-origin request and a constant-time pairing-token match, rejects non-local IPv4 peers and cross-subnet peers, and validates subtitle extensions. The endpoint receives links only; the TV's Media3 player fetches the media. The QR carries the pairing token only in a URL fragment; the page consumes it and removes it from browser history before sending the token in a request header. The full video and subtitle URLs are never logged by this code.

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

Added release-variant minification/resource shrinking configuration without signing credentials. TV and phone/laptop link forms now accept an optional 100-character user label. Resume selection presents the saved positions and requires the user to choose one or start from the beginning; it warns that the new link may be different and does not silently resume based on a title match. Added [the local build and TV QA guide](SETUP_AND_QA.md) with toolchain setup, exact debug/lint and release commands, expected APK location, optional installation commands, a manual TV checklist, and known limitations. Updated this checkpoint to show that core source work through milestone 12 is committed, while all requested builds, tests, and device verification remain pending.

Status: source implemented and reviewed; no APK was produced. The scaffold build timed out during dependency metadata resolution, and the retry was stopped before Kotlin compilation. No build, lint, test, emulator, or device command was run after the user directed source-only continuation. Release signing is intentionally unconfigured; debug/release packaging, manifest behavior, playback, focus, subtitles, handoff, history, and device compatibility remain unverified.

## Current execution boundary

Milestones 2–12 are source-complete on separate numbered branches, each stacked from its predecessor. This implementation pass is source-only: builds, lint, tests, emulator actions, and device commands were deferred. The initial scaffold build encountered Google Maven timeouts, and its retry was canceled before compilation. No APK was produced. Do not push or merge. Milestone 13 remains optional and out of scope. Real-TV installation and live testing remain pending explicit approval.
