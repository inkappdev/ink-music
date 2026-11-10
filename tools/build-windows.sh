#!/usr/bin/env bash
# Windows' msi, built on Windows (in Git Bash), as GitHub's runner once built it (its Actions retired 2026-11-10):
# libmpv from mpv's own Windows build (shinchiro's, x86-64), beside the
# app, then the msi. Unsigned, as ever: the first open is SmartScreen's More info, Run anyway.
# Needs a JDK 21 (JAVA_HOME), and keystore.properties beside the checkout (Last.fm's key). The msi carries the Java
# that builds it, or INK_RUNTIME's where it's set: JetBrains' (JBR 21, github.com/JetBrains/JetBrainsRuntime, a
# "jbrsdk" build) for ink's own title bar; on another Java, Windows' bar is back.
# Leaves build/release/ink-music-<version>-windows.msi.
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) ;; *) echo "Windows builds it (in Git Bash)"; exit 1 ;; esac
[ -f keystore.properties ] || { echo "no keystore.properties: without it, no Last.fm"; exit 1; }
[ -n "${JAVA_HOME:-}" ] || { echo "no JAVA_HOME: a JDK 21 (winget install EclipseAdoptium.Temurin.21.JDK)"; exit 1; }

# the newest mpv-dev build for x86-64 (any CPU from the last decade), its libmpv-2.dll beside the app
out=desktop/native/windows-x64
if [ ! -f "$out/libmpv-2.dll" ]; then
  url=$(curl -fsSL https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest |
    grep -o '"browser_download_url": *"[^"]*mpv-dev-x86_64-[0-9]\{8\}[^"]*\.7z"' | head -1 | sed 's/.*"\(http[^"]*\)"/\1/')
  [ -n "$url" ] || { echo "couldn't find mpv's Windows build"; exit 1; }
  echo "$url"
  work=$(mktemp -d)
  curl -fsSL -o "$work/mpv.7z" "$url"
  mkdir -p "$work/x" "$out"
  # Windows' own tar (libarchive) opens a 7z, called by its path: Git Bash's tar is GNU's, which can't; 7-Zip if it's there
  if command -v 7z >/dev/null; then 7z x "$work/mpv.7z" -o"$work/x" >/dev/null
  else "$(cygpath "${SYSTEMROOT:-C:\\Windows}")/System32/tar.exe" -xf "$(cygpath -w "$work/mpv.7z")" -C "$(cygpath -w "$work/x")"; fi
  cp "$work/x/libmpv-2.dll" "$out/"
  rm -rf "$work"
fi

rm -rf desktop/build/compose/binaries/main/msi
GITHUB_SHA=$(git rev-parse HEAD 2>/dev/null || echo here) ./gradlew -q :desktop:packageInkMsi -PdesktopOnly --no-daemon
mkdir -p build/release
cp desktop/build/compose/binaries/main/msi/*.msi "build/release/ink-music-$version-windows.msi"
ls -la "build/release/ink-music-$version-windows.msi"
