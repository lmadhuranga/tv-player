# Version selections (source snapshot, 2026-10-21)

The following are **pinned project versions** chosen using public Android developer release documentation. They are candidates, not proven locally by an Android build. The final compatibility gate is a Gradle sync/build with the target Android SDK and BRAVIA/emulator testing.

| Component | Chosen version |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.13 |
| Kotlin Android / Compose Compiler | 2.2.20 |
| Compile SDK | 36 |
| Target SDK | 35 |
| Minimum SDK | 26 |
| Compose BOM | 2026.09.00 |
| Compose TV Material | 1.1.0 |
| Media3 ExoPlayer/UI/HLS/DASH | 1.11.1 |
| Activity Compose | 1.13.0 |
| DataStore | 1.2.1 |
| ZXing core | 3.5.3 |

References:
- https://developer.android.com/build/releases/agp-8-13-0-release-notes
- https://developer.android.com/jetpack/androidx/releases/tv
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/jetpack/androidx/releases/datastore
- https://developer.android.com/training/tv/playback/compose

The project includes wrapper properties, not the official Gradle wrapper JAR; run `gradle wrapper --gradle-version 8.13` with an installed Gradle distribution when ready.
