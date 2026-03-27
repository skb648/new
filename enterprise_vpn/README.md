# Enterprise VPN

Enterprise VPN Application with Samsung One UI 6 Design

## One-Try Build

### Prerequisites
- **Java 17** (required for Gradle 7.6.3)
- **Android SDK** (API 34)
- **Flutter SDK** (3.24.0 or later)

### Build APK in One Command

```bash
chmod +x build.sh
./build.sh
```

The script will:
1. Auto-detect Java 17, Android SDK, and Flutter SDK
2. Create `local.properties` automatically
3. Get dependencies
4. Build the release APK

### Manual Build

If you prefer manual build:

```bash
# Set environment variables
export JAVA_HOME=/path/to/java17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

# Create local.properties
echo "flutter.sdk=/path/to/flutter" > android/local.properties
echo "sdk.dir=$ANDROID_HOME" >> android/local.properties

# Build
flutter pub get
flutter build apk --release
```

### APK Location
```
build/app/outputs/flutter-apk/app-release.apk
```

## Features

- Samsung One UI 6 Design Language
- Custom Server Configuration (IP, Port, Protocol)
- HTTP Header Injection
- SNI (Server Name Indication) Configuration
- Server Authentication (Username/Password)
- Real-time Traffic Statistics
- Dark/Light Theme Support
- Haptic Feedback

## Tech Stack

- Flutter 3.24.0
- Dart 3.5.0
- Kotlin 1.9.22
- Android Gradle Plugin 7.4.2
- Gradle 7.6.3

## Requirements

| Requirement | Version |
|-------------|---------|
| Android Min SDK | 21 (Android 5.0) |
| Android Target SDK | 34 (Android 14) |
| Java | 17 |
| Flutter | 3.24.0+ |

## Install APK

```bash
# Using adb
adb install build/app/outputs/flutter-apk/app-release.apk

# Or transfer the APK to your device and install manually
```

## License

MIT License
