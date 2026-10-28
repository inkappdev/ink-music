#!/usr/bin/env bash
# A release: both builds, R8'd and signed with the release key (keystore.properties), named by
# version. The full build, with its SHA-256 sum, in build/release/: what GitHub gets. The play build
# (no YouTube Music) apart in build/play/, for the Play Console only: it shares the app's id and key,
# so on GitHub it installed over the full one and YouTube went silent (40 people took it for 1.4.5).
# Nothing is uploaded: attaching them to a GitHub release (gh release create v<version>
# build/release/*) is a separate, deliberate step.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f keystore.properties ] || { echo "no keystore.properties: a release must be signed with the release key"; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk-21}"
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
[ -f "fastlane/metadata/android/en-US/changelogs/$code.txt" ] || { echo "no changelog for $code"; exit 1; }
./gradlew -q clean :app:assembleFullRelease :app:assemblePlayRelease
mkdir -p build/release build/play && rm -f build/release/* build/play/*
cp app/build/outputs/apk/full/release/app-full-release.apk "build/release/ink-music-$version.apk"
cp app/build/outputs/apk/play/release/app-play-release.apk "build/play/ink-music-$version-play.apk"
# the play build must carry no NewPipe at all
if unzip -p "build/play/ink-music-$version-play.apk" 'classes*.dex' | grep -q -a "org/schabi/newpipe"; then echo "NewPipe in the play build"; exit 1; fi
(cd build/release && sha256sum *.apk > SHA256SUMS)
"$(ls -d $HOME/.local/share/android-sdk/build-tools/* | tail -1)/apksigner" verify --print-certs "build/release/ink-music-$version.apk" 2>/dev/null | grep -m1 SHA-256 || true
ls -la build/release build/play
