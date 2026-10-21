# Progress checkpoint

## Current state

- Date: 2026-10-21
- Milestone: `1-plan-docs`
- Branch: `1-plan-docs`
- Repository began with no commits and no tracked application files. The `origin` remote is configured, but its `main` tracking ref is gone.
- Planning files are the only intended content in this milestone. No Android project, build configuration, app code, device access, or TV validation has started.

## Decisions recorded

- Native Kotlin Android TV app; Compose for TV, Media3 ExoPlayer, and DataStore are the initial technology direction.
- Tentative minimum API level 26; verify against selected versions and the target device during scaffolding.
- A TV-hosted page accepts links from a same-Wi-Fi browser. It submits only the URLs; video and subtitle data are fetched by the TV.
- Direct HTTP(S) playback is the baseline. Seedr integration is deferred behind a verified authentication/support gate.
- Remote navigation and reliable focus behavior are core requirements.
- Recent history is local and privacy-conscious, with clear-history and deliberate handling of signed URL replacement/resume.
- Feature work proceeds in numbered branches stacked from the last accepted commit; commit each feature before proceeding. No pushes, repository creation, or merge without an explicit request.
- The app UI, settings, errors, documentation, and examples are English-only; subtitle content may use any language/Unicode supported by playback.

## Next step

After this planning commit is accepted, begin only milestone `2-android-tv-skeleton` on a new branch from this commit. Do not access or install to the BRAVIA unless the user explicitly approves that future testing.
