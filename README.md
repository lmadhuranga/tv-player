# TV Player

TV Player is a native Android TV app for entering a direct video URL and playing it on a Sony BRAVIA. A small page served by the TV will let a phone or laptop on the same Wi-Fi submit a video URL and, optionally, an external subtitle URL. The page is for link entry only: the TV retrieves the media and plays it locally through its player.

The first release is centered on reliable remote control use, direct HTTP(S) links, external subtitles, and clear network and playback errors. It will not depend on Seedr or any cloud service.

The player interface, settings, and errors are English-only. Subtitle tracks may contain any language and Unicode text supported by the media stack.

## Project status

Source implementation for core milestones 2–12 is complete and committed sequentially on local numbered branches. This pass did not compile, lint, test, install, or launch the app, so the source remains unverified. See the [implementation plan](docs/PLAN.md), [progress log](docs/PROGRESS.md), and [local build and TV QA guide](docs/SETUP_AND_QA.md) for scope, exact evidence, and deferred checks.

## Development workflow

- The core milestones were implemented one at a time on numbered branches, each stacked from the previous milestone commit.
- Keep each branch focused; commit every completed feature before starting the next milestone.
- Keep implementation and verification status distinct. Record exactly which checks ran and which remain pending; a source review does not prove that a build or device behavior works.
- Do not push branches, create a GitHub repository, or merge to `main` without an explicit request.
- Source implementation was authorized through milestone 12. Builds, tests, installation, and TV connection remain pending; TV access requires explicit approval before device interaction.

## Tentative product identity

The working name is **TV Player**. The Android application ID is `dev.lmadhuranga.tvplayer`, distinct from Seedr's identity.
