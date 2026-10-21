# Progress checkpoint

## Current state

- Date: 2026-10-21
- Implemented through milestone: `4-direct-url-player`
- Current branch: `4-direct-url-player`
- Planning baseline: `be2f7cc`; milestone 2 commit: `715bd34`; milestone 3 commit: `2a35592`.
- No TV connection, installation, or live testing has occurred. User authorized continuing core source implementation through milestone 12 without running builds or tests; validation remains pending.

## Decisions recorded

- Native Kotlin Android TV app; Compose for TV, Media3 ExoPlayer, and DataStore are the initial technology direction.
- Tentative minimum API level 26; verify against selected versions and the target device during scaffolding.
- A TV-hosted page accepts links from a same-Wi-Fi browser. It submits only the URLs; video and subtitle data are fetched by the TV.
- Direct HTTP(S) playback is the baseline. Seedr integration is deferred behind a verified authentication/support gate.
- Remote navigation and reliable focus behavior are core requirements.
- Recent history is local and privacy-conscious, with clear-history and deliberate handling of signed URL replacement/resume.
- Feature work proceeds in numbered branches stacked from the last accepted commit; commit each feature before proceeding. No pushes, repository creation, or merge without an explicit request.
- The app UI, settings, errors, documentation, and examples are English-only; subtitle content may use any language/Unicode supported by playback.
- Build and test commands are deferred during this source-only implementation pass. Do not describe implementation as compiled, tested, or device-validated until those checks are explicitly run and recorded.

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

## Current execution boundary

Continue the core roadmap sequentially, with each numbered milestone committed on its own branch stacked on the preceding commit. This implementation pass is source-only: do not run builds, lint, tests, emulator actions, or device commands. Update this log after each milestone and label source-complete milestones as unverified until the user requests and the checks are actually run. The user has authorized the implementation scope through milestone 12. Do not push or merge. Milestone 13 remains optional and out of scope. Real-TV installation and live testing remain pending explicit approval.
