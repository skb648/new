#!/bin/bash

# Enterprise VPN - One-Try Build Script
# This script sets up the environment and builds the APK in one command

set -e

echo "=========================================="
echo "  Enterprise VPN - One-Try APK Build"
echo "=========================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to find Java 17
find_java_17() {
    # Check common locations for Java 17
    local java_paths=(
        "$HOME/jdk17/jdk-17.0.2"
        "$HOME/jdk-17"
        "$HOME/.jdks/corretto-17.*"
        "/usr/lib/jvm/java-17-openjdk-amd64"
        "/usr/lib/jvm/java-17-oracle"
        "/usr/lib/jvm/temurin-17-jdk"
        "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
    )
    
    for path in "${java_paths[@]}"; do
        expanded=$(eval echo "$path")
        if [ -d "$expanded" ] && [ -x "$expanded/bin/java" ]; then
            version=$("$expanded/bin/java" -version 2>&1 | head -n1 | grep "17\.")
            if [ -n "$version" ]; then
                echo "$expanded"
                return 0
            fi
        fi
    done
    
    # Check system java
    if command_exists java; then
        version=$(java -version 2>&1 | head -n1 | grep "17\.")
        if [ -n "$version" ]; then
            echo "system"
            return 0
        fi
    fi
    
    return 1
}

# Function to find Android SDK
find_android_sdk() {
    local sdk_paths=(
        "$HOME/android-sdk"
        "$HOME/Android/Sdk"
        "$HOME/Library/Android/sdk"
        "$HOME/AppData/Local/Android/Sdk"
        "/opt/android-sdk"
    )
    
    # First check environment variable
    if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then
        echo "$ANDROID_HOME"
        return 0
    fi
    
    if [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
        echo "$ANDROID_SDK_ROOT"
        return 0
    fi
    
    # Check common paths
    for path in "${sdk_paths[@]}"; do
        if [ -d "$path" ]; then
            echo "$path"
            return 0
        fi
    done
    
    return 1
}

# Function to find Flutter SDK
find_flutter_sdk() {
    # First check if flutter command exists
    if command_exists flutter; then
        flutter_path=$(which flutter)
        flutter_dir=$(dirname "$(dirname "$flutter_path")")
        echo "$flutter_dir"
        return 0
    fi
    
    # Check common locations
    local flutter_paths=(
        "$HOME/flutter"
        "$HOME/.flutter"
        "/opt/flutter"
        "/usr/local/flutter"
        "$HOME/development/flutter"
    )
    
    for path in "${flutter_paths[@]}"; do
        if [ -d "$path" ] && [ -x "$path/bin/flutter" ]; then
            echo "$path"
            return 0
        fi
    done
    
    return 1
}

echo ""
echo -e "${YELLOW}Step 1: Checking prerequisites...${NC}"

# Find and set Java 17
echo "Checking for Java 17..."
JAVA_17_PATH=$(find_java_17)
if [ -z "$JAVA_17_PATH" ]; then
    echo -e "${RED}ERROR: Java 17 not found!${NC}"
    echo "Please install Java 17 or set JAVA_HOME manually"
    echo "Download from: https://adoptium.net/temurin/releases/?version=17"
    exit 1
fi

if [ "$JAVA_17_PATH" = "system" ]; then
    echo -e "${GREEN}✓ Java 17 found (system)${NC}"
else
    export JAVA_HOME="$JAVA_17_PATH"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo -e "${GREEN}✓ Java 17 found at: $JAVA_HOME${NC}"
fi

# Find and set Android SDK
echo "Checking for Android SDK..."
ANDROID_SDK_PATH=$(find_android_sdk)
if [ -z "$ANDROID_SDK_PATH" ]; then
    echo -e "${RED}ERROR: Android SDK not found!${NC}"
    echo "Please install Android SDK and set ANDROID_HOME"
    echo "Download from: https://developer.android.com/studio#command-tools"
    exit 1
fi

export ANDROID_HOME="$ANDROID_SDK_PATH"
export ANDROID_SDK_ROOT="$ANDROID_SDK_PATH"
echo -e "${GREEN}✓ Android SDK found at: $ANDROID_HOME${NC}"

# Find Flutter SDK
echo "Checking for Flutter SDK..."
FLUTTER_SDK_PATH=$(find_flutter_sdk)
if [ -z "$FLUTTER_SDK_PATH" ]; then
    echo -e "${RED}ERROR: Flutter SDK not found!${NC}"
    echo "Please install Flutter SDK"
    echo "Download from: https://docs.flutter.dev/get-started/install"
    exit 1
fi
echo -e "${GREEN}✓ Flutter SDK found at: $FLUTTER_SDK_PATH${NC}"

FLUTTER_CMD="$FLUTTER_SDK_PATH/bin/flutter"

echo ""
echo -e "${YELLOW}Step 2: Creating local.properties...${NC}"

# Create local.properties
cat > android/local.properties << EOF
flutter.sdk=$FLUTTER_SDK_PATH
sdk.dir=$ANDROID_SDK_PATH
EOF
echo -e "${GREEN}✓ local.properties created${NC}"

echo ""
echo -e "${YELLOW}Step 3: Getting dependencies...${NC}"
$FLUTTER_CMD pub get

echo ""
echo -e "${YELLOW}Step 4: Building APK...${NC}"
$FLUTTER_CMD build apk --release

echo ""
echo "=========================================="
echo -e "${GREEN}✓ BUILD SUCCESSFUL!${NC}"
echo "=========================================="
echo ""
echo "APK Location: $(pwd)/build/app/outputs/flutter-apk/app-release.apk"
echo "APK Size: $(du -h build/app/outputs/flutter-apk/app-release.apk | cut -f1)"
echo ""
echo "Install with: adb install build/app/outputs/flutter-apk/app-release.apk"
