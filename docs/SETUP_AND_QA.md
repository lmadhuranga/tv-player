# Local build and TV QA guide

This guide records completed local build/lint, install, and launch commands, plus checks still pending. The milestone 21 debug APK was installed and launched on a Sony BRAVIA VU3 (Android API 34); a read-only GET check returned 401 without Origin/token and 403 for a mismatched Origin. The milestone 25 APK was also built, installed, and launched on that TV; the handoff screen and QR became available after initial startup. Limited remote input was used to inspect the player and verify a buffer setting across Settings close/reopen; full navigation, video image/audio output, and subtitle rendering are not established. Milestone 26 was built/linted locally, but not installed or launched; no phone-camera scan of the gray QR surface has been performed. Milestone 27 is local source/build verification only; the approval prompt and browser exchange have not been exercised on a TV or phone. Milestone 28 was built and linted locally, but the collapsed subtitle section has not been exercised in a browser.

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

Install Android SDK Platform 36 and Android SDK Build Tools 36 through Android Studio's SDK Manager if they are missing. The Gradle wrapper pins Gradle 8.13; AGP 8.13.2 and Kotlin 2.2.21 are pinned in the version catalog. Lifecycle is pinned to 2.9.4 because 2.11.0 requires SDK37 and AGP9.1. The milestone 21 build passed with 22 lint warnings and zero errors. Milestone 22's `:app:assembleDebug :app:lintDebug` also passed with 25 warnings and zero errors; APK: `app/build/outputs/apk/debug/app-debug.apk` (16 MiB). No milestone 22 TV install or launch was performed.

During milestone 2, an earlier scaffold-only build attempt timed out accessing Google Maven; its retry was stopped before Kotlin compilation. The later build on milestone 13 resolved dependencies, exposed and fixed compile/lint blockers, then passed. Gradle also warned about a local NDK folder missing `source.properties`; native libraries were packaged. The lint report includes version advisories, manifest, vector, and KTX warnings; review them during maintenance.

## Optional local installation

For an authorized installation, select the already-connected target explicitly:

```sh
TV_SERIAL="<authorized-device-id>"
adb -s "$TV_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb install -r` returned `Success` on `192.168.8.100:5555`, verified immediately beforehand as manufacturer Sony, model BRAVIA VU3, API 34. A read-only package check confirmed path `/data/app/.../dev.lmadhuranga.tvplayer.../base.apk`, versionCode 1 / versionName 0.1.0, minSdk 26, and targetSdk 36. `adb shell am start -n dev.lmadhuranga.tvplayer/.MainActivity` returned `Starting: Intent { cmp=dev.lmadhuranga.tvplayer/.MainActivity }`. The milestone 25 install repeated this on the same verified device; the process was alive and `MainActivity` top-resumed. The handoff page and QR appeared in a read-only screen capture after initial startup. No D-pad input, phone-page control operation, or playback test was performed by this agent. The APK manifest declares application ID `dev.lmadhuranga.tvplayer` and a Leanback launcher entry.

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
- [ ] Visually confirm the phone-send QR handoff screen is the default; Back to Home and confirm “Add Video URL” and “Open player” are absent.
- [ ] Confirm focus visibility and D-pad reachability on Home, URL entry, options/settings dialogs, history and handoff screens; Back restores focus or returns safely.
- [ ] From active and empty player states, use Back to return to handoff and confirm the current phone URL/QR. Go to Player should appear and receive initial focus only while current media exists; with no media, it should be hidden and Settings should receive focus. Open Settings and confirm focus returns to its button.
- [ ] Open and close the in-app Settings dialog repeatedly from handoff; confirm the phone URL/QR remains current and browser requests still work. Re-enter handoff after leaving and verify the old approval session is invalidated and a new approval request is required. Confirm a genuine port conflict gives the port-specific error and other startup failures do not blame another app.
- [ ] Verify every button is dark at rest, white only while focused/pressed, has readable disabled contrast, and returns to dark when focus moves. Check Home, player controls/settings, history/resume, URL entry, and handoff. Confirm selected audio/quality remains indicated by its check/label rather than a sticky white fill.
- [ ] Enter a supported direct HTTP and HTTPS video link from the TV; test empty, malformed, very long, and unsupported-scheme values.
- [ ] Confirm video starts, pauses, seeks, supports each listed speed, and pauses when leaving the player or backgrounding the app.
- [ ] Test same-Wi-Fi phone/laptop link handoff, direct QR, TV remote approval, optional subtitle URL, denied/expired/duplicate approval requests, invalid session/origin, oversized request, server stop/restart invalidation, and verify no session token is issued before Allow. Test Back denial and confirm only Allow permits limits, uploads, or playback. Confirm accepted links start playback immediately even when resume history is enabled, the local page sends links only, and no full URLs or pairing secrets are logged.
- [x] Read-only `/api/limits` origin regression check: absent Origin reached authentication and returned 401 without a token; a supplied mismatched Origin returned 403. No existing browser form was submitted.
- [ ] While a video is playing, use the TV's subtitle file picker to select valid SRT and WebVTT files; confirm cues render and subtitle enable/style/timing controls work. Try unsupported, malformed, and over-8-MiB files and verify a sanitized error plus continued video playback; replace a subtitle and leave the player to verify cache cleanup. This is an on-TV picker, not a phone/laptop file upload.
- [ ] Confirm the player title is the decoded last filename from the URL path, query credentials are not shown, long titles remain one line with ellipsis, and host/“Video” fallback is sensible for URLs without a filename.
- [ ] With player controls hidden, press Left/Right and verify one 30-second seek per press; with controls visible, verify existing D-pad focus navigation/seek-row behavior is unchanged.
- [ ] With controls hidden, confirm the bottom timeline stays visible with dark gray played progress and light gray buffered progress.
- [ ] Test valid SRT and WebVTT, malformed/unreachable subtitle URLs, on/off, size/color/background, ± timing offset, external-track seek/speed behavior, and available embedded subtitle tracks.
- [ ] On the phone page, test video URL and video-file paths, subtitle URL and subtitle-file paths, optional combinations, pairing failures, progress and cancel, invalid file types/content, declared oversize rejection, changing free space, and the 512 MiB reserve behavior.
- [ ] On the phone page, confirm video and subtitle each show a separate URL/File choice defaulting to URL; only one field per media type is visible and enabled. Switch modes after entering/selecting values and confirm the inactive value clears; submit URL/URL, URL/file, file/URL, and file/file combinations plus video without a subtitle. Verify network requests include only the currently selected modes and no stale file is staged.
- [ ] On the phone page, successfully submit video and subtitle URLs and confirm each appears in its separate 24-hour recent list. Verify up to 20 total entries, duplicate refresh, expiry/pruning after 24 hours (signed URLs may expire sooner at source), filename/host-only labels without query strings, and clear-history. Tap entries and confirm they only switch that field to URL mode and fill it; playback/send starts only after pressing Send to TV. Confirm history controls lock during a send, uploaded files are not saved to URL history, and localStorage denial does not block sending.
- [ ] Confirm uploaded videos are capped at 8 GiB and effective upload limits reflect currently available cache; subtitles are capped at 8 MiB. Verify files are removed after cancellation, failed/incomplete uploads, replacement, playback completion, player Back, app stop/release, and process restart. Confirm history contains no file path.
- [ ] Test Fit/Fill/Zoom with different aspect ratios and compare decoded picture dimensions with available stream variant labels.
- [ ] Use media with multiple supported audio tracks and HLS/DASH variants; confirm actual choices switch correctly. Use a single-variant source and confirm no quality selector is shown.
- [ ] Test network loss/recovery, 401/403/404 server responses, unsupported codecs, prolonged buffering, explicit retry, fresh-link entry, and return navigation. Confirm no automatic retry loop.
- [ ] Enable local position history, resume a selected saved position, choose start over, clear history, disable history, restart the app, and verify that video/subtitle URLs are never persisted.
- [ ] Review cleartext HTTP policy, LAN endpoint bounds/session checks, backup policy, permissions, cache cleanup, release shrinking, and signing setup.
- [ ] In Settings, choose each 1-, 2-, and 5-minute maximum buffer, restart the app to confirm persistence, and verify the selected maximum applies to the next video only. Confirm a new video still starts after the existing 2.5-second threshold, rebuffer behavior remains 5 seconds, and actual buffer may be shorter because of the 64 MiB target-buffer byte estimate, memory availability, or stream characteristics.
- [x] On the milestone 25 install, Settings initially showed 1 minute; selecting 2 minutes, closing/reopening Settings, and seeing 2 minutes remain selected verified persistence across the Settings screen lifecycle. The one-minute default was restored. App-process restart and actual next-video buffering duration remain unverified.
- [x] Milestone 13 debug APK install on Sony BRAVIA VU3 and package metadata verification; the app was not launched.
- [x] Install and launch the milestone 21 APK on the Sony BRAVIA (install and Activity start command only; visual/runtime behavior unverified).
- [ ] Milestone 22 was built/linted locally; no install, launch, or TV contact was performed for this version. Manual Settings persistence and buffering behavior remain pending.
- [ ] Milestone 23 phone-page mode selectors were built/linted locally; no install, launch, or TV contact was performed for this version. Browser mode switching, submission combinations, and stale-upload behavior remain pending.
- [ ] Milestone 24 browser-local URL history was built/linted locally; no install, launch, or TV contact was performed for this version. LocalStorage TTL, clear, safe labels, selection, and no-auto-submit behavior remain pending.
- [x] Milestone 25 button-selector APK built/linted, installed, and launched; MainActivity remained top-resumed and the handoff QR appeared. The lead reported successful URL/URL form submission with no JavaScript errors; the TV fetched both synthetic WAV and SRT fixtures and the player showed the audio filename and 0:10 duration with CC On. Audible output and rendered subtitle cues were not confirmed.
- [ ] Explicitly switch the new phone-page buttons between URL and File for both media types; confirm visible selected state, `aria-pressed`, inactive clearing, send-time lock, and that selecting history fills URL mode without submitting.
- [ ] On milestone 26, verify the QR tile/quiet-zone gray (`#D6D6D6`) is visibly dimmer than white and scan its QR with a phone camera from normal viewing distance; QR contents and black modules should remain unchanged.
- [x] Milestone 26 assemble and lint passed locally; no install, launch, screen capture, or QR scan was performed for the gray-surface change.
- [ ] Confirm fullscreen video extends under hidden system bars, the player canvas/shutter and Fit letterboxes are black without cropping/stretching, and bars restore after leaving the player.
- [ ] Confirm controls appear initially, hide after about four seconds of active playback, remain visible while paused/buffering/error/settings, and return when pressing Down with Play/Pause focused.
- [ ] Focus the seek/time row and verify left/right seek in ten-second steps, focus remains visible, and time/progress updates.
- [ ] Verify media Play/Pause works with controls hidden; Back closes player settings, hides visible controls, then returns to the phone-send handoff screen.
- [ ] Verify full-screen player settings contain no floating panels and Back restores focus to the originating Options or Audio control.
- [ ] Complete a separate authorized Sony BRAVIA runtime pass; the installation does not demonstrate launcher visibility, playback, focus, network, or subtitle behavior.
- [ ] Exercise browser-to-TV uploads on an approved phone/TV pair; install/launch alone will not validate browser streaming or local Media3 decoding.

## Known boundaries

Source supports direct HTTP(S), progressive playback, and Media3 HLS/DASH modules, but actual protocols, redirects, codecs, devices, and track variants remain unverified. External subtitles are limited to SRT and WebVTT URLs ending with those extensions. History stores only opt-in local title/host/position metadata and never stores URLs. Signed-link renewal, cloud services, downloads, transcoding, media proxying, and optional Seedr integration are out of scope.

- [ ] On the handoff screen, submit a phone request and verify the TV identifies the browser family and source IP; Deny is focused initially, Allow is reachable by D-pad, and Allow unlocks the existing limits/URL/upload flow.
- [ ] Verify Deny, Back, 60-second timeout, duplicate request throttling, leaving/stopping the server, and foreground restart all release or invalidate pending requests with clear messages; verify there is no token or persistent trusted-device record before explicit approval.

- [ ] On the phone handoff page, verify the subtitle section starts collapsed with only “Add subtitle” available; activating it reveals URL/File controls, history, and size guidance. Check `aria-expanded` and `aria-controls`, and confirm the toggle is disabled while sending.
- [ ] Close the subtitle section after entering a URL or choosing a file; confirm both selections clear, mode resets to URL, and the next video-only send contains no subtitle. Select a subtitle-history entry and confirm it opens the section and fills the URL without submitting.

- [ ] On the phone handoff page, verify the compact dark card at narrow and wide widths, no horizontal overflow, readable focus states, and touch-sized full-width controls. Confirm the subtitle section starts collapsed, URL/File modes and history still work, approval status is visible, and progress/Cancel appear only during file transfers.
- [x] Milestone 29 source/build checks are recorded in `docs/PROGRESS.md`; no browser visual preview or TV contact was performed for the layout change.

- [ ] Verify on a phone/desktop browser that selected URL/File controls and Send stay dark blue/teal with readable white text, the keyboard focus ring remains clear, and disabled controls remain legible. Confirm no bright white button surface appears in the browser.
- [x] Milestone 30 source/build checks are recorded in `docs/PROGRESS.md`; no browser visual preview or TV contact was performed for the color change.
