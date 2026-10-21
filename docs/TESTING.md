# Validation and release gate

## What has been checked in this environment

- [x] Kotlin-only `Models.kt` compiled with locally installed `kotlinc 1.9.0` (standalone logic check, **not** an Android app build).
- [x] Standalone smoke checks passed for HTTP(S) URL validation, signed query exclusion from fingerprints and labels, and SRT/VTT subtitle cue shifting.
- [x] Android manifest, Kotlin/Gradle files, source tree, drawable assets, unit test files and original plan are included in the archive.
- [ ] Gradle dependency resolution / full Kotlin Android compile (Android SDK/Gradle not available here).
- [ ] JVM JUnit Gradle test task (requires resolving JUnit and Gradle dependencies).
- [ ] Emulator installation, D-pad traversal, IME entry, focus restoration, subtitle rendering.
- [ ] Same-LAN phone/browser submission tests, pairing lockout, origin checks, oversized and malformed requests.
- [ ] Network/codec/DRM and error recovery tests on real media.
- [ ] Sony BRAVIA installation, actual playback, input, UX, focus, picture modes and codec verification (**requires explicit owner approval**).
- [ ] Release build signing, security review, target-SDK policy review and release QA.

## Suggested verification sequence

1. In Android Studio, sync project and run `:app:assembleDebug`; resolve any source/dependency compatibility issues found by the actual compiler.
2. Run `:app:testDebugUnitTest`; add tests for LAN parser and DataStore migrations. Rerun static Android lint (`:app:lintDebug`).
3. Install to a 1080p Android TV emulator (API 26+), launch from LEANBACK launcher and exercise Back/Enter/D-pad controls with an emulator remote. No off-screen or inaccessible focus controls.
4. Play a public, legal test `.mp4`, `.m3u8`, `.mpd`; note actual codecs, tracks and results. Check seek/pause/skip/speed; intentionally induce 403, 404, network timeout and unsupported codec cases.
5. Test subtitle SRT and WebVTT with different languages, cue styles and timing offsets, including invalid links and 2 MB rejection.
6. On a trusted, non-isolated LAN, open the QR page from a phone, submit with/without a correct code, test external origin, 8 KB limit, lockout and app background server shutdown. Verify actual media requests originate from the TV, not the browser page.
7. Toggle full-URL history OFF and ON; check DataStore persisted history, restarting the app, signed URL stripping, resume with replacement link and clear history.
8. Only **with device owner approval**, repeat the essential tests on an actual Sony BRAVIA and record model, firmware, network and source formats.
9. Security and accessibility QA: signed URLs not logged; foreground-only server; focus reentry; labels and font legibility on a 10-foot interface; network isolation.

## Outstanding engineering follow-ups

- Android build/compiler and unit tests remain unexecuted; do not treat the source snapshot as a verified installable application.
- Add instrumentation UI/focus regression tests and LAN-request fuzz tests after first successful build.
- Review the TV subtitle styling, QR display and IME behavior on BRAVIA hardware, not only emulator.
- Consider a user-visible connection refresh button when LAN address changes during the foreground session.
- Review signed URL threat model on untrusted Wi-Fi before distribution; plaintext local form traffic is not confidential.
- Integrate official `gradle-wrapper.jar` with `gradle wrapper --gradle-version 8.13` when build tools become available.
