# FlareMusic

A modern Android music player built with **Kotlin**, **Jetpack Compose**, and **Media3**.

> FlareMusic is under active development. Features and interfaces may change between builds.

[![Android CI](https://github.com/NotKrishEnough/FlareMusic/actions/workflows/android.yml/badge.svg)](https://github.com/NotKrishEnough/FlareMusic/actions/workflows/android.yml)

## Overview

FlareMusic is an Android music player project focused on a clean, customizable experience.

### Tech stack

- Kotlin
- Jetpack Compose and Material 3
- AndroidX
- AndroidX Media3
- NewPipe Extractor

## Build the APK on Linux

You can build FlareMusic locally on Ubuntu, Debian, Fedora, Arch, or another Linux distribution. The commands below use Ubuntu/Debian package names; install the equivalent packages on other distributions.

### 1. Install the required tools

Install Git, **JDK 17**, and the Android SDK. Android Studio is the simplest way to install and manage the SDK.

On Ubuntu/Debian:

```bash
sudo apt update
sudo apt install git openjdk-17-jdk
```

Check that Java 17 is active:

```bash
java -version
```

Install Android Studio from the [official Android developer site](https://developer.android.com/studio), then use **Tools → SDK Manager** to install:

- Android SDK Platform 35
- Android SDK Build-Tools (the version recommended by Android Studio)
- Android SDK Command-line Tools (latest)

Accept the Android SDK licences in Android Studio, or run the following after installing the command-line tools (adjust the SDK path if yours differs):

```bash
\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

If your SDK is not at the default location, set the environment variable. For example:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"
```

Add those exports to your shell profile (such as `~/.bashrc`) if you want them to persist. You can also create a local `local.properties` file in the project root containing `sdk.dir=/absolute/path/to/Android/Sdk`. Do not commit that machine-specific file.

### 2. Clone the repository

```bash
git clone https://github.com/NotKrishEnough/FlareMusic.git
cd FlareMusic
```

### 3. Build a debug APK

The repository includes the Gradle wrapper, so you do not need to install Gradle globally.

```bash
chmod +x gradlew
./gradlew assembleDebug
```

The first build may take a while because Gradle downloads the required dependencies.

When the build succeeds, the APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install on a connected Android device (optional)

Enable USB debugging on your device, connect it to the computer, then run:

```bash
./gradlew installDebug
```

Or transfer `app-debug.apk` to your phone and install it manually. You may need to allow installation from that file manager.

### Useful Gradle commands

| Command | Purpose |
| --- | --- |
| `./gradlew assembleDebug` | Build the debug APK |
| `./gradlew installDebug` | Build and install on a connected device |
| `./gradlew clean` | Remove generated build files |
| `./gradlew test` | Run available unit tests |

## Build with GitHub Actions

You can also build without setting up a Linux environment locally:

1. Open the [Actions](https://github.com/NotKrishEnough/FlareMusic/actions) tab.
2. Select the Android build workflow.
3. Open a completed run.
4. Download the APK from the run's **Artifacts** section, if the workflow uploaded one.

## Automated releases

A GitHub Actions workflow builds the debug APK and publishes a GitHub Release with automatically generated release notes (changelog).

**To publish a release manually:**

1. Open **Actions → Build and Release**.
2. Select **Run workflow**.
3. Enter a version tag such as `v1.0.0`.
4. Run the workflow. After the build succeeds, open the repository's **Releases** page.

You can also create and push a version tag from your local checkout:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Pushing a `v*` tag triggers the same workflow. Each release includes the APK and a SHA-256 checksum file. GitHub generates the changelog from the repository's merged pull requests and commits since the previous release. Review the generated notes before sharing a release.

## Project details

| Setting | Value |
| --- | --- |
| Application ID | `com.xin.flaremusic` |
| Minimum Android version | Android 8.0 (API 26) |
| Compile / target SDK | 35 |
| Java compatibility | 17 |

## Windows desktop version

A native Windows desktop companion is available in `windows/FlareMusic.Windows`. It currently supports adding local audio files, track selection, play/pause, previous/next, and seeking. Online search and account features from Android are not included in this initial desktop build.

To build it, install the .NET 8 SDK on Windows and run:

```powershell
dotnet publish windows/FlareMusic.Windows/FlareMusic.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o artifacts/FlareMusic
```

Or open **Actions → Windows Desktop** and download the `FlareMusic-Windows-x64` artifact after the workflow completes. Extract the ZIP and run `FlareMusic.exe`.

## Contributing

Issues and pull requests are welcome. When reporting a bug, include your device model, Android version, steps to reproduce, and relevant build or crash logs. Please avoid posting private account data, session cookies, or other secrets.

## License

FlareMusic is licensed under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only). You may use, modify, and redistribute the project under the terms of that license. See `LICENSE` for the full text. Third-party dependencies and assets may be subject to their own licenses.
