# Nova Player AI — Stage 1
An original Android video player inspired by familiar folder-based players.

## Current features
- Android native Kotlin / Jetpack Compose interface
- Open a local video with Android system file picker
- Play/pause, seeking, and full-screen controls provided by Media3 PlayerView
- Build a debug APK using GitHub Actions

## Not implemented yet
AI transcription, Arabic translation, folder browsing, gestures, background playback, additional codecs and feature parity with MX Player. No paid API is used.

## GitHub build
Upload these files to the root of a GitHub repository (keep directory structure). Go to Actions > Build Android APK > Run workflow. Download the artifact from the successful workflow run. Unzip it to obtain app-debug.apk.

Target SDK 35; intended to install on Android 16, but real device compatibility is not yet verified.
