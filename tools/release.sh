#!/usr/bin/env bash
# A release: both phone builds, R8'd and signed with the release key (keystore.properties), named by version. The full
# build in build/release/: what the site and GitHub get. The play build (no YouTube Music) apart in build/play/, for the
# Play Console only: it shares the app's id and key, so on GitHub it installed over the full one and YouTube went silent
# (40 people took it for 1.4.5).
# The computer's are built by hand into the same build/release/, before or after this, from the same commit (since
# 2.1.2; the private mac repo whose Actions built them was archived 2026-11-10): tools/build-mac.sh on the Mac over SSH,
# tools/windows-vm.sh build on the Umbrel's Windows machine, tools/build-linux.sh and tools/build-appimage.sh here.
# This leaves them be, says which this version has and which it lacks, and refuses another version's (a computer offered
# this release must never be handed an older build: it would offer itself as the update for ever, 2026-11-01).
# Nothing is uploaded: tools/site_release.py and gh release create are separate, deliberate steps.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f keystore.properties ] || { echo "no keystore.properties: a release must be signed with the release key"; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk-21}"
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
[ -f "fastlane/metadata/android/en-US/changelogs/$code.txt" ] || { echo "no changelog for $code"; exit 1; }
./gradlew -q clean :app:assembleFullRelease :app:assemblePlayRelease
mkdir -p build/release build/play && rm -f build/release/ink-music-*.apk build/release/SHA256SUMS build/play/*
cp app/build/outputs/apk/full/release/app-full-release.apk "build/release/ink-music-$version.apk"
cp app/build/outputs/apk/play/release/app-play-release.apk "build/play/ink-music-$version-play.apk"
# the play build must carry no NewPipe at all
if unzip -p "build/play/ink-music-$version-play.apk" 'classes*.dex' | grep -q -a "org/schabi/newpipe"; then echo "NewPipe in the play build"; exit 1; fi
# the computer's, built by hand beside it: another version's refused, this one's said, any missing named
stale=$(cd build/release && ls ink-music-* 2>/dev/null | grep -v -- "^ink-music-$version[.-]" || true)
[ -z "$stale" ] || { echo "another version's builds in build/release (move them away): $stale"; exit 1; }
for want in mac.dmg windows.msi linux.deb linux.rpm linux.AppImage; do
  [ -f "build/release/ink-music-$version-$want" ] && echo "with it: $want" || echo "not built yet: $want (by hand, from this commit)"
done
(cd build/release && sha256sum ink-music-* > SHA256SUMS)
"$(ls -d $HOME/.local/share/android-sdk/build-tools/* | tail -1)/apksigner" verify --print-certs "build/release/ink-music-$version.apk" 2>/dev/null | grep -m1 SHA-256 || true
ls -la build/release build/play
