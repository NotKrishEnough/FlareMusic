# FlareMusic

A Flutter-based Android music player focused on a clean, customizable listening experience.

> FlareMusic is under active development. Features and interfaces may change between builds.

[![Flutter Android CI](https://github.com/NotKrishEnough/FlareMusic/actions/workflows/flutter-android.yml/badge.svg)](https://github.com/NotKrishEnough/FlareMusic/actions/workflows/flutter-android.yml)

## Overview

FlareMusic is being developed with Flutter and Dart. The Android app uses Flutter plugins for audio playback, background audio, storage, and online music resolution.

### Tech stack

- Flutter and Dart
- `just_audio` and `audio_service` for playback and background audio
- `youtube_explode_dart` for YouTube stream resolution
- `shared_preferences` and `flutter_secure_storage` for local preferences and secure storage
- `webview_flutter` and `google_sign_in` for web and account-related integration

## Build the Android APK

### Requirements

- Flutter SDK (stable channel; compatible with the Dart SDK constraint in `pubspec.yaml`)
- Android SDK and Android build tools
- Git

Install Flutter using the [official Flutter installation guide](https://docs.flutter.dev/get-started/install), and configure Android tooling using the [Flutter Android setup guide](https://docs.flutter.dev/platform-integration/android/setup).

### Build

Clone the repository and enter the project:

```bash
git clone --branch flutter-rewrite https://github.com/NotKrishEnough/FlareMusic.git
cd FlareMusic
```

Fetch dependencies and check the Flutter setup:

```bash
flutter doctor
flutter pub get
```

Build a debug APK:

```bash
flutter build apk --debug
```

Build a release APK:

```bash
flutter build apk --release
```

The APK is generated under:

```text
build/app/outputs/flutter-apk/
```

The exact filename depends on the build mode and options. For a release intended for distribution, configure signing as described in the [Flutter Android deployment guide](https://docs.flutter.dev/deployment/android).

## Build with GitHub Actions

The `Flutter Android` workflow is located at [`.github/workflows/flutter-android.yml`](.github/workflows/flutter-android.yml). Open the repository's [Actions tab](https://github.com/NotKrishEnough/FlareMusic/actions), select that workflow, and open a completed run to see its status and any uploaded artifacts.

## Automated releases

The release workflow is defined in [`.github/workflows/release.yml`](.github/workflows/release.yml). Check that workflow's triggers and inputs before starting a release; the available options may change as development continues. Published builds and release notes are available from the repository's [Releases page](https://github.com/NotKrishEnough/FlareMusic/releases).

## Contributing

Issues and pull requests are welcome. When reporting a bug, include your device model, Android version, steps to reproduce, and relevant build or crash logs. Please do not post private account data, session cookies, or other secrets.

## License

FlareMusic is licensed under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only). You may use, modify, and redistribute the project under the terms of that license. See `LICENSE` for the full text. Third-party dependencies and assets may be subject to their own licenses.
