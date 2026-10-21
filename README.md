# TV Player

TV Player is a native Android TV app for entering a direct video URL and playing it on a Sony BRAVIA. A small page served by the TV will let a phone or laptop on the same Wi-Fi submit a video URL and, optionally, an external subtitle URL. The page is for link entry only: the TV retrieves the media and plays it locally through its player.

The first release is centered on reliable remote control use, direct HTTP(S) links, external subtitles, and clear network and playback errors. It will not depend on Seedr or any cloud service.

The player interface, settings, and errors are English-only. Subtitle tracks may contain any language and Unicode text supported by the media stack.

## Project status

The source now covers direct link entry and playback, same-Wi-Fi link handoff, external subtitles, display controls, and opt-in local resume history. The remaining core milestones are being implemented sequentially on their numbered branches. This work has not yet been built or device-tested; see [the implementation plan](docs/PLAN.md) for scope and [the progress log](docs/PROGRESS.md) for evidence and verification status.

## Development workflow

- Implement one planned milestone at a time, on its numbered branch, stacked from the latest accepted milestone.
- Keep each branch focused; commit every completed feature before starting the next milestone.
- Keep implementation and verification status distinct. Record exactly which checks ran and which remain pending; a source review does not prove that a build or device behavior works.
- Do not push branches, create a GitHub repository, or merge to `main` without an explicit request.
- TV connection, installation, or live testing requires explicit user approval for that work. The current authorization covers source implementation only; builds and tests are deferred until requested.

## Tentative product identity

The working name is **TV Player**. The Android application ID is `dev.lmadhuranga.tvplayer`, distinct from Seedr's identity.
