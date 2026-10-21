# TV Player

TV Player is a native Android TV app for playing direct video URLs on Android TV. A small page served by the TV lets a paired phone or laptop on the same Wi-Fi submit a video URL or upload a video file, plus an optional subtitle URL or SRT/WebVTT file. URL media is fetched by the TV; uploaded files are streamed into private temporary TV cache and removed after playback or when the flow is cancelled/exits.

The first release is centered on reliable remote control use, direct HTTP(S) links, external subtitles, and clear network and playback errors. It will not depend on Seedr or any cloud service.

The player interface, settings, and errors are English-only. Subtitle tracks may contain any language and Unicode text supported by the media stack.

## Project status

Milestone 20 adds paired phone upload choices for both video and subtitle files while preserving URL submission. Video uploads are capped at 8 GiB and further limited by available TV cache space while reserving 512 MiB; subtitle uploads are capped at 8 MiB. Build/lint passed and the debug APK installed and launched on the Sony BRAVIA; phone-browser transfer, playback, and cleanup behavior remain unverified. See the [implementation plan](docs/PLAN.md), [progress log](docs/PROGRESS.md), and [local build and TV QA guide](docs/SETUP_AND_QA.md) for evidence and remaining checks.

## Development workflow

- The core milestones were implemented one at a time on numbered branches, each stacked from the previous milestone commit.
- Keep each branch focused; commit every completed feature before starting the next milestone.
- Keep implementation and verification status distinct. Record exactly which checks ran and which remain pending; a source review does not prove that a build or device behavior works.
- Do not push branches, create a GitHub repository, or merge to `main` without an explicit request.
- Local build/lint, TV installation, and app launch are authorized. D-pad interaction, playback, visual, and functional QA still require manual checks and are not claimed complete.

## Tentative product identity

The working name is **TV Player**. The Android application ID is `dev.lmadhuranga.tvplayer`, distinct from Seedr's identity.
