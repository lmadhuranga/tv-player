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

- [x] On the BRAVIA VU3 (Android 14), Home's Browse USB videos action was D-pad reachable; granting READ_MEDIA_VIDEO populated the in-app list from mounted volume `a230-1bec`. Selecting `50.First.Dates.2004.720p.BrRip.x264.YIFY.mp4` opened the player, reported 1280 × 528, and advanced to 0:36. The screen capture remained black, so a visible decoded frame was not confirmed; Back/no-drive states were not exercised.

- [x] Debug assemble and lint complete without errors; record Gradle/JDK/SDK versions. Lint emitted 22 warnings.
- [ ] Confirm package ID, min SDK 26, target SDK 36, landscape layout, Leanback launcher entry, and TV banner.
- [ ] Visually confirm the phone-send QR handoff screen is the default; Back to Home and confirm “Add Video URL” and “Open player” are absent.
- [ ] Confirm focus visibility and D-pad reachability on Home, URL entry, options/settings dialogs, history and handoff screens; Back restores focus or returns safely.
- [ ] From active and empty player states, use Back to return to handoff and confirm the current phone URL/QR. Go to Player should appear and receive initial focus only while current media exists; with no media, it should be hidden and Settings should receive focus. Open Settings and confirm focus returns to its button.
- [ ] Open and close the in-app Settings dialog repeatedly from handoff; confirm the phone URL/QR remains current and browser requests still work. Re-enter handoff after leaving and verify the old approval session is invalidated and a new approval request is required. Confirm a genuine port conflict gives the port-specific error and other startup failures do not blame another app.
- [ ] Verify every button is dark at rest, white only while focused/pressed, has readable disabled contrast, and returns to dark when focus moves. Check Home, player controls/settings, history/resume, URL entry, and handoff. Confirm selected audio/quality remains indicated by its check/label rather than a sticky white fill.
- [ ] Enter a supported direct HTTP and HTTPS video link from the TV; test empty, malformed, very long, and unsupported-scheme values.
- [ ] Confirm video starts, pauses, seeks, cycles through 1×, 1.25×, 1.5×, 1.75×, and 2× playback speeds, and pauses when leaving the player or backgrounding the app.
- [ ] Test same-Wi-Fi phone/laptop link handoff, direct QR, TV remote approval, optional subtitle URL, denied/expired/duplicate approval requests, invalid session/origin, oversized request, server stop/restart invalidation, and verify no session token is issued before Allow. Test Back denial and confirm only Allow permits limits, uploads, or playback. Confirm accepted links start playback immediately even when resume history is enabled, the local page sends links only, and no full URLs or pairing secrets are logged.
- [x] Read-only `/api/limits` origin regression check: absent Origin reached authentication and returned 401 without a token; a supplied mismatched Origin returned 403. No existing browser form was submitted.
- [ ] While a video is playing, use the TV's subtitle file picker to select valid SRT and WebVTT files; confirm cues render and subtitle enable/style/timing controls work. Try unsupported, malformed, and over-8-MiB files and verify a sanitized error plus continued video playback; replace a subtitle and leave the player to verify cache cleanup. This is an on-TV picker, not a phone/laptop file upload.
- [ ] Confirm the player title is the decoded last filename from the URL path, query credentials are not shown, long titles remain one line with ellipsis, and host/“Video” fallback is sensible for URLs without a filename.
- [ ] With player controls hidden, press Left/Right and verify one 30-second seek per press; with controls visible, verify existing D-pad focus navigation/seek-row behavior is unchanged.
- [ ] With controls hidden, confirm the bottom timeline stays visible with dark gray played progress and light gray buffered progress, with current-time and duration labels hidden except briefly after a hidden Left/Right seek. Press and hold Left/Right and confirm labels update during seek feedback, then disappear again.
- [ ] Pause a loaded video, hide controls with Back, and confirm a centered pause glyph remains visible inside an unfilled circle with a white outline; resume playback and confirm the indicator disappears.
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

### Milestone 76 USB folders and subtitles

- [ ] On Android 11+, grant TV Player “Allow access to manage all files” in Android Settings, then browse each mounted removable USB volume and nested folders. Confirm Back moves up one folder and the drive root returns to the drive list.
- [ ] Select USB videos with a matching SRT/WebVTT basename, with one unrelated subtitle, with multiple unrelated subtitles, and with duplicate basename subtitles. Confirm exact single basename match wins, a sole supported subtitle is selected, and ambiguous cases remain unselected until the user chooses manually.
- [ ] Select a sibling subtitle manually, start playback, and verify the video continues if the sidecar is removed or unreadable. Confirm removing the USB produces a readable error and does not persist a stale path.
- [ ] Revoke all-files access and confirm the app offers the explicit Settings route and preserves the indexed MediaStore video fallback. The fallback does not browse folders or discover sidecar subtitles.
- [ ] On Android 10, confirm the app explains that direct folder browsing is unavailable and offers indexed-video playback where permitted. On older supported versions, verify READ_EXTERNAL_STORAGE and mounted-volume traversal.
- [x] Milestone 76 `git diff --check`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:lintDebug` passed locally. Lint warns that Google Play restricts `MANAGE_EXTERNAL_STORAGE`; no TV installation, launch, or runtime validation was performed.

### Milestone 77 playback keep-awake

- [ ] During URL, USB/document, and local-file playback, wait past the TV's Ambient Mode timeout and confirm video remains visible. Confirm initial loading and network rebuffering also keep the TV awake.
- [ ] Pause, allow media to end, trigger a playback/stall error, leave the player, and background the app; confirm each path releases the keep-screen-on behavior and normal TV sleep resumes.
- [ ] `FLAG_KEEP_SCREEN_ON` state follows active playback intent and terminal playback status; fullscreen disposal clears it as a defensive lifecycle cleanup.
- [x] `git diff --check`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:lintDebug` passed locally. Lint reports 28 warnings and 0 errors, including the existing `MANAGE_EXTERNAL_STORAGE` policy warning. No TV runtime verification was performed.

### Milestone 78 USB file explorer listing

- [ ] Browse a mixed USB folder and confirm files, hidden entries, and folders appear, with folders first and type/size labels.
- [ ] Confirm folders navigate, videos open the existing subtitle choice screen, and subtitle/unsupported file rows cannot start playback.
- [ ] Confirm a genuinely empty folder says “This folder is empty” and read failures show an error.
- [ ] `git diff --check`, `:app:assembleDebug`, and `:app:lintDebug` results are recorded in `docs/PROGRESS.md`; TV behavior remains pending until manually checked.
- [x] Milestone 30 source/build checks are recorded in `docs/PROGRESS.md`; no browser visual preview or TV contact was performed for the color change.

### Milestone 79 USB folder counts

- [ ] Browse a USB drive with empty, mixed, hidden, and nested folders; confirm root and folder rows count only immediate child folders/files and include hidden entries.
- [ ] Confirm singular/plural labels and that folder rows show counts while file rows retain type and size.
- [ ] Confirm unreadable child directories show “Contents unavailable” rather than zero; counts do not recurse into descendants.
- [ ] `git diff --check`, `:app:assembleDebug`, and `:app:lintDebug` results are recorded in `docs/PROGRESS.md`; TV validation remains pending.

### Milestone 81 USB explorer rows

- [ ] Browse USB roots and nested directories; confirm rows show drawn drive/folder/video/file icons, concise metadata, and a chevron on folders.
- [ ] Use the remote to focus, activate, scroll through a long directory, open a folder, and use the parent row to navigate back. Confirm focus changes only the row background/outline and does not scale or resize text.
- [ ] Confirm subtitle and unsupported files remain visible in a subdued style and cannot be activated; confirm video rows still open subtitle selection.
- [x] `git diff --check`, `:app:assembleDebug`, and `:app:lintDebug` passed as recorded in `docs/PROGRESS.md`; no TV contact, install, or runtime validation was performed.

### Milestone 82 player loading and network speed

- [ ] Play a URL, browser-uploaded video, and USB video; confirm initial preparation and rebuffering show animated “Loading...” while subtitle loading alone does not.
- [ ] With player controls visible, confirm the top-right receive rate updates about once per second in KB/s or MB/s. Hide controls and confirm both the rate label and sampling stop; background the app and confirm sampling pauses and resumes with a fresh baseline.
- [ ] Confirm the header has no Options button and the bottom-row Options button still opens settings and restores focus on close.
- [ ] Run `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, and `git diff --check`; physical BRAVIA validation remains separate.

### Milestone 87 network file manager

- [ ] Pair from the existing handoff page, open File Manager, and confirm removable roots and the accessible public Movies folder appear with honest read-only/free-space status.
- [ ] Browse nested folders from the tree, breadcrumb, grid, and list views; confirm dot entries, reserved Android/data/obb paths, and all symlinks stay unavailable.
- [ ] Upload a small file and a larger file; observe progress and cancel an in-flight transfer. Confirm partial temporary files are removed, low-space requests fail safely, and existing files are never overwritten.
- [ ] Create a folder; delete a file and an empty folder after the exact-name confirmation; confirm a non-empty folder is rejected and its contents remain.
- [ ] Verify unpaired or expired tokens cannot list or mutate, supplied cross-origin GETs are rejected, and cross-origin or tokenless POSTs are rejected.
- [x] `:app:testDebugUnitTest` passed with traversal, symlink, overwrite, partial cleanup, cancellation, stale temporary cleanup, and non-empty-folder checks.
- [ ] Final `:app:assembleDebug`, `:app:lintDebug`, embedded JavaScript `node --check`, and `git diff --check` are pending after feature 88 integration.
- [ ] Browser visual QA is pending: the sandbox denied binding the local mock fixture port, and the shared Playwright browser profile was locked by another session. No TV access was attempted.

## File Manager: Download and trusted browser access (branch 87)

1. Build, install and launch with `bash scripts/tv.sh deploy`, then keep **TV Player → Send to TV** open.
2. On a computer or phone on the same private LAN, open `http://TV_IP:8765/files`.
3. At the first connection, the TV offers **Deny**, **Allow once**, or **Trust 30 days**. Choose **Trust 30 days** to remember this browser. The browser retains a random secret in same-origin local storage; the TV stores only its hash, bound to the browser's User-Agent, for up to 30 days.
4. Reopening `/files`, restarting the app, or switching between the `/files` and `/` pages should reuse trust without the approval dialog. A browser with cleared site data, a private session, or a significantly changed User-Agent must be approved again.
5. Select any normal visible file and choose **Download**, or use **Download** from the image/video preview. The TV sends the original bytes to your browser's download folder, through a time-limited LAN-only link. Video playback remains dependent on the receiving browser's codec support.
6. From the TV's **Send to TV** screen, choose **Forget trusted browsers → Forget all** to revoke every saved trusted browser and active sessions. Existing preview and download streams are interrupted.
7. When the TV Player handoff screen closes or the application leaves the foreground, its local server stops. Keep it open during larger transfers.

**Security:** Browser trust is for your own devices on your private home network. The server uses local plain HTTP (not TLS), so browser trust secrets could be intercepted by someone with access to untrusted LAN traffic. Do not use this facility on guest/public Wi-Fi or expose port 8765 through router forwarding.

**QA checklist (pending physical-device validation):**
- Verify a trusted browser reconnects without a second approval after refresh and TV-app restart.
- Verify **Allow once** requires approval again after session restart.
- Verify **Forget all** immediately prevents downloading, uploading, and listing files.
- Download an image, MP4/MOV, and a non-media document; compare the received bytes and file names.
- Verify Range seeking works for browser media preview and that a second browser cannot reuse the download ticket from a different IP.
- Verify interrupted downloads leave no altered file on the TV (downloads are read-only) and that USB disconnection reports an error.
- Check large downloads against TV memory/storage constraints and report any TV lifecycle interruptions.


## Contextual file actions (branch 89)

The phone/computer web File Manager now offers contextual actions when a file, folder or storage root is selected.

- **Single-click or tap a file tile:** select it and show relevant actions below the toolbar.
- **Right-click on a tile or folder in the Storage sidebar:** open the action menu at the pointer.
- **Three-dot (⋮) button:** open the same menu on any tile or sidebar folder, including touch devices.
- **Touch and hold a tile:** open its context menu after a short press.
- **Shift+F10 or the Context Menu key:** open actions for the currently selected item.
- **Double-click or keyboard-activate a video:** open its browser preview/player; an image opens the preview.
- **Double-click or keyboard-activate a folder:** browse inside it.

| Selection | Available actions |
| --- | --- |
| Video | Play video, Download file, Delete file |
| Image | Preview image, Download file, Delete file |
| Other file | Download file, Delete file |
| Folder | Open folder, New folder inside, Upload here, Delete folder |
| Storage root | Open drive, New folder, Upload here |

Delete is disabled on read-only storage. Nonempty folders cannot be deleted; this restriction is enforced by the server, not just the UI. Folder downloads as ZIP archives are **not included** in this branch; ordinary individual file downloads are supported.

**Manual QA required on the Sony BRAVIA and computer/phone browser:** verify menu placement near screen edges; mouse right-click; long-press; ⋮ buttons; selection toolbar in grid/list views; Escape/menu dismissal; playback/preview; download; upload into a chosen nested folder; deleting an empty folder and a file after confirmation; no delete for populated folders; read-only drive behavior; keyboard Shift+F10. Build/run using `bash scripts/tv.sh deploy` from branch `89-file-context-actions`. No hardware run is claimed here.


## Branch 90 — LAN HTTPS (opt-in debug provisioning)

### Why HTTPS isn't just a URL change

The TV previously listened on plain HTTP at `http://TV_IP:8765/`. Browser access to a private LAN IP cannot be made securely HTTPS just by changing the scheme. TLS requires a unique certificate with an **IP address Subject Alternative Name** and the client browser must trust the certificate issuer.

A trusted public certificate for `192.168.x.x` is not available through Let's Encrypt IP validation: Let's Encrypt's new IP certificates require public proof via HTTP-01 or TLS-ALPN-01 and expire after about six days. For this local-TV developer workflow we use **mkcert** and a local CA instead.

### Mac and Sony BRAVIA setup

1. Check out the separate feature branch `90-lan-https`. Keep Android SDK 36, JDK 17, and ADB available.
2. Build and install the debug app first: `bash scripts/tv.sh deploy`.
3. On the Mac: `brew install mkcert`. Run **`mkcert -install`**, which installs a development CA in this Mac's trust store. Only do this on your own trusted computer.
4. With ADB connected to the BRAVIA, run `bash scripts/https-tv.sh install`. This generates a certificate for the TV's current IP, packages it as PKCS12, verifies the transfer, and stores it in the *debug app's private files directory*. The certificate, key, password and PKCS12 files remain under `.local-https/` on the Mac, which is Git-ignored.
5. Open **`https://TV_IP:8765/files`**, not the old HTTP URL. The Send to TV screen's QR and text automatically switch to HTTPS once provisioning succeeds.
6. For other phones/computers, export **only** `$(mkcert -CAROOT)/rootCA.pem` from your Mac and install it as a trusted CA on the receiving device. On iOS, installing a profile is not sufficient: full trust must be enabled in Settings → General → About → Certificate Trust Settings. Never distribute `rootCA-key.pem`, `.p12`, `.pass`, or the private leaf key.
7. Re-approve browser trust once after switching schemes: HTTP and HTTPS have **different browser storage origins**. After that, the existing 30-day trusted-browser mechanism works under HTTPS.

The same port **8765** is used for either protocol, not both. Once HTTPS is enabled, HTTP requests to port 8765 will fail; no insecure HTTP listener or silent downgrade is added.

### Operational rules

- `bash scripts/https-tv.sh status`: check whether HTTPS provisioning is enabled.
- If the TV's LAN IP changes, rerun `bash scripts/https-tv.sh install` using the new address and update any saved browser bookmark. A certificate made for one IP does not validate a different IP.
- A missing, expired, invalid, or IP-mismatched certificate causes the TV handoff listener to **fail closed** and display an error; it will **not** drop to HTTP.
- To explicitly roll back the development configuration, run `bash scripts/https-tv.sh disable`. This removes the app-private TLS files and **restores HTTP**. Do not use on shared/untrusted networks.
- ADB `run-as` provisioning is for **debug builds only**. Release deployment needs a secure TV-side certificate import and renewal UI before HTTPS can be enabled for retail distribution.
- There is no need for an always-on Mac *after* provisioning. The Android TV serves TLS itself whenever the Send to TV screen is running.
- HTTPS encrypts the browser connection; it does not automatically fix 401 pairing-token errors, media codecs, transfer timeouts, or disconnects when the app is backgrounded.
- Protect the mkcert root CA and its *private key* on your Mac, and use the trusted-browser approval/revocation controls on your TV.

### Validation still required on the physical TV

- Build `./gradlew testDebugUnitTest assembleDebug --console=plain` and install.
- Provision certificate and confirm the displayed TV QR code and URL are HTTPS.
- Verify `curl --cacert "$(mkcert -CAROOT)/rootCA.pem" "https://TV_IP:8765/files"` returns the file manager with successful certificate/IP validation.
- Confirm plain HTTP on port 8765 does *not* return the file manager after provisioning.
- Confirm approval/trusted-browser reconnect, download, preview seeking, uploads, browser CSP, and Same-Origin POSTs succeed over HTTPS.
- Confirm an IP change, expired certificate, or deleted PKCS12 blocks listener startup instead of downgrading.
- Verify macOS, iPhone Safari, and Android Chrome trust instructions on devices you personally control.

**Status:** Source changes and setup script are committed; no Mac/BRAVIA build, certificate provisioning, browser tests, or successful TLS handshake is claimed yet.


## Branch 92 — play-first media preview modal

A screenshot of the old preview showed a dark media element and endless-looking browser loading indicator before the user even asked to play. Branch `92-video-preview-first-impression` refines the web File Manager's media dialog without changing its server API.

- On **Play video**, show a polished movie preview cover first: media icon, filename, file format, size, **Play video** action, Download, and Close. **No media request is made before Play is clicked.**
- Once Play is clicked, show branded loading feedback while the browser requests a read-only media ticket and obtains a playable frame. The native player controls appear when ready.
- The player has a 20-second initial-loading fallback with **Try again** and a useful explanation; unsupported codecs and disconnected TVs show a failure state rather than an indefinite spinner. Download of the original file remains available.
- Image previews show a consistent loading placeholder while retrieving the full-resolution image, then display the image itself.
- Closing the modal stops video playback, clears its media source and loading timer, and ignores late ticket/image responses. Responsive layouts support desktop and smaller screens; reduced-motion preferences are respected.

**Source-level verification:** JavaScript parses successfully, and a mocked DOM test confirmed that opening a video triggers **zero** ticket requests; clicking Play starts the ticket request, native video controls appear on load, and Close stops/clears the video. Real Android TV/browser testing, video decoding, and HTTPS streaming are not yet confirmed.

**Manual QA:**
1. `git switch 92-video-preview-first-impression`; run `bash scripts/tv.sh deploy`.
2. Open `https://TV_IP:8765/files` after your HTTPS configuration, or use the URL shown on TV Player.
3. Double-click an MP4: verify that the initial modal has a visible **Play video** button, not a spinning/blank video.
4. Click Play: verify loading state, playback controls, seeking, fullscreen, sound, Download, and Close.
5. Verify unsupported MKV/codecs, slow storage, connection interruption, Retry, and re-opening another file.
6. Check image preview and small mobile viewport, with keyboard Escape to dismiss.
### Milestone 88 USB explorer space

- [ ] On a 960 × 540 logical TV display, confirm the USB list shows about seven rows, with one compact folder heading and the breadcrumb in a small fixed footer.
- [ ] Confirm no `../` row appears in normal, empty, or error views. Use Left to move to the parent folder and Back to return to the parent or drive list.
- [ ] In an empty folder and a folder containing only unsupported files, confirm focus remains usable and Left/Back still work. In an error view, confirm focus lands on the first available action.
- [x] Source checks and local debug build/lint are recorded in `docs/PROGRESS.md`; TV visual and remote behavior remain unverified.

### Milestone 97 USB playback return and subtitle focus

- [ ] Play a USB folder video with a matching subtitle, then use the player Home action; confirm the same folder returns, focus and scroll land on the played video, and the subtitle remains auto-selected when selected again.
- [ ] On the subtitle choice screen, confirm **Play video** initially has focus even when a subtitle is auto-selected; choose another subtitle and confirm playback uses that choice.
- [ ] Repeat with an indexed USB video; confirm the USB browser is the return destination.
- [ ] Play a handoff URL, browser-uploaded video, and recently played URL; confirm player Home returns to Send to TV.
- [ ] Unplug the USB drive during playback and return; confirm a reconnect message appears and the app stays in USB browsing.
- [x] Local debug build, lint, and diff checks are recorded in `docs/PROGRESS.md`; physical TV behavior remains unverified.
