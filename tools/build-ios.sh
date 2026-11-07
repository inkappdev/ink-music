#!/usr/bin/env bash
# ink music on Bronson's iPhone (2026-11-07), built on the Mac: the Kotlin framework (ios/, Kotlin/Native), the Xcode
# project made from ios/app/project.yml (xcodegen), the app signed with his Apple ID (a free account: it lasts 7 days,
# then this again), installed and opened on the iPhone paired with the Mac.
#   tools/build-ios.sh            build, install, open
#   tools/build-ios.sh build      build only
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH=/opt/homebrew/bin:$PATH
[ -n "${JAVA_HOME:-}" ] || export JAVA_HOME="$HOME/ink-build/jdk/Contents/Home"
device=${DEVICE:-00008140-0011446C0A38801C}
./gradlew -q -Pios -PdesktopOnly -PphoneOnly :ios:linkReleaseFrameworkIosArm64
cd ios/app
xcodegen generate --quiet
xcodebuild -quiet -project InkMusic.xcodeproj -scheme InkMusic -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath build -allowProvisioningUpdates build
app="build/Build/Products/Release-iphoneos/ink music.app"
ls -d "$app"
[ "${1:-}" = build ] && exit 0
xcrun devicectl device install app --device "$device" "$app"
xcrun devicectl device process launch --device "$device" app.ink.music.iphone || echo "installed: open ink music on the iPhone"
