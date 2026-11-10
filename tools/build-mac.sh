#!/usr/bin/env bash
# The Mac's dmg, built on a Mac (Apple Silicon), as GitHub's runner once built it (its Actions retired 2026-11-10):
# libmpv bundled beside the app, the dmg, then every piece of code in
# it signed as "ink music" (ink's own certificate, not Apple's: the first open is still Open Anyway, but each build
# is the same app to macOS, which keeps what it was allowed, Local Network above all).
# Needs Homebrew, a JDK 21 (JAVA_HOME, or brew's temurin@21), and beside the checkout: keystore.properties (Last.fm's
# key) and the certificate, ink-music-mac-sign.p12 and .pass (~/.config/ink-music/ on the PC; MAC_SIGN=path/to.p12
# to say where). Leaves build/release/ink-music-<version>-mac.dmg.
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ "$(uname -m)" = arm64 ] || { echo "an Apple Silicon Mac builds it (this is $(uname -m))"; exit 1; }
[ -f keystore.properties ] || { echo "no keystore.properties: without it, no Last.fm"; exit 1; }
p12=${MAC_SIGN:-$HOME/.config/ink-music/ink-music-mac-sign.p12}
[ -f "$p12" ] && [ -f "${p12%.p12}.pass" ] || { echo "no $p12 (and .pass): the dmg must be signed as ink music"; exit 1; }
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null) || { echo "no JDK 21: brew install --cask temurin@21"; exit 1; }
  export JAVA_HOME
fi

# libmpv and everything it needs, beside it in the app (found through @loader_path), each signed again (ad hoc) once
# its paths are rewritten, or macOS won't load it
brew list mpv >/dev/null 2>&1 || brew install mpv
brew list dylibbundler >/dev/null 2>&1 || brew install dylibbundler
out=desktop/native/macos-arm64
rm -rf "$out" && mkdir -p "$out"
cp "$(brew --prefix mpv)/lib/libmpv.2.dylib" "$out/"
chmod u+w "$out/libmpv.2.dylib"
install_name_tool -id @loader_path/libmpv.2.dylib "$out/libmpv.2.dylib"
dylibbundler -of -b -x "$out/libmpv.2.dylib" -d "$out" -p @loader_path/ -s "$(brew --prefix)/lib" < /dev/null
chmod u+w "$out"/*.dylib
codesign --force -s - "$out"/*.dylib

rm -rf desktop/build/compose/binaries/main/dmg
GITHUB_SHA=$(git rev-parse HEAD 2>/dev/null || echo here) ./gradlew -q :desktop:packageDmg -PdesktopOnly --no-daemon

# signed as ink music: its own keychain for the while, each piece of code from the inside out (Java's runtime,
# libmpv's), then the app; the dmg opened writable to do it, with room for the signatures
kc="$(mktemp -d)/sign.keychain-db"
security create-keychain -p "" "$kc"
security set-keychain-settings "$kc"
security unlock-keychain -p "" "$kc"
security import "$p12" -k "$kc" -P "$(cat "${p12%.p12}.pass")" -T /usr/bin/codesign >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "" "$kc" > /dev/null
before=$(security list-keychains -d user | tr -d '"')
security list-keychains -d user -s "$kc" $before
trap 'security list-keychains -d user -s $before; security delete-keychain "$kc" 2>/dev/null || true' EXIT
id=$(security find-certificate -c "ink music" -Z "$kc" | awk '/SHA-1/ { print $3 }')
dmg=$(ls desktop/build/compose/binaries/main/dmg/*.dmg)
work=$(mktemp -d)
hdiutil convert "$dmg" -format UDRW -o "$work/rw.dmg" >/dev/null
now=$(hdiutil resize -limits "$work/rw.dmg" | tail -1 | awk '{ print $2 }')
hdiutil resize -sectors $((now + 81920)) "$work/rw.dmg"
hdiutil attach "$work/rw.dmg" -nobrowse -mountpoint "$work/m" >/dev/null
app="$work/m/ink music.app"
find "$app/Contents" -type f \( -name '*.dylib' -o -name '*.jnilib' -o -perm -u+x \) -print0 |
  while IFS= read -r -d '' f; do file -b "$f" | grep -q Mach-O && codesign --force --sign "$id" --keychain "$kc" "$f"; done
[ -d "$app/Contents/runtime" ] && codesign --force --sign "$id" --keychain "$kc" "$app/Contents/runtime"
codesign --force --sign "$id" --keychain "$kc" "$app"
codesign --verify --deep --strict "$app"
codesign -dvv "$app" 2>&1 | grep -E "^(Identifier|Authority|Signature)="
hdiutil detach "$work/m" -quiet
mkdir -p build/release
rm -f "build/release/ink-music-$version-mac.dmg"
hdiutil convert "$work/rw.dmg" -format UDZO -o "build/release/ink-music-$version-mac.dmg" >/dev/null
rm -rf "$work"
ls -la "build/release/ink-music-$version-mac.dmg"
