#!/usr/bin/env bash
# Linux's deb and rpm, built here in an Ubuntu 24.04 container (podman) as desktop.yml builds them on GitHub's runner,
# for when Actions can't (GitHub suspended ink's account 2026-11-01). With the JDK the PC builds with (Temurin 21, its
# runtime bundled in the app as the runner's was), copied in: SELinux keeps a container out of the home folder's.
# jpackage lists a library as needed only when it's installed where it builds, so the runner's are installed (sound,
# OpenGL, X) and libjpeg taken out: Java's jpeg and splash screen link it, ink music uses neither (Skia draws), and
# Ubuntu's name for it (libjpeg-turbo8) would keep the deb off Debian. Checked 2026-11-03: the same dependencies as
# 2.1.1's from Actions. Leaves build/release/ink-music-<version>-linux.deb and .rpm.
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -f keystore.properties ] || { echo "no keystore.properties: without it, no Last.fm"; exit 1; }
jdk=${JDK:-$HOME/.local/share/jdk-21}
work=${TMPDIR:-/tmp}/ink-linux-build
mkdir -p "$work/gradle"
[ -x "$work/jdk/bin/java" ] || { rm -rf "$work/jdk"; cp -r "$jdk" "$work/jdk"; }
podman run --rm -v "$PWD":/src:Z -v "$work/gradle":/gradle:Z -v "$work/jdk":/jdk:Z \
  -e JAVA_HOME=/jdk -e GRADLE_USER_HOME=/gradle -e GITHUB_SHA="$(git rev-parse HEAD)" -w /src docker.io/library/ubuntu:24.04 bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive PATH=/jdk/bin:$PATH
apt-get update -qq >/dev/null
apt-get install -y -qq rpm fakeroot binutils dpkg-dev libasound2t64 libgl1 libglvnd0 libglx0 libxext6 libxi6 libxrender1 libxtst6 >/dev/null 2>&1
dpkg -r --force-depends libjpeg-turbo8 >/dev/null 2>&1 || true
rm -rf desktop/build/compose/binaries/main/deb desktop/build/compose/binaries/main/rpm
./gradlew -q :desktop:packageDeb :desktop:packageRpm -PdesktopOnly --no-daemon'
mkdir -p build/release
cp desktop/build/compose/binaries/main/deb/*.deb "build/release/ink-music-$version-linux.deb"
cp desktop/build/compose/binaries/main/rpm/*.rpm "build/release/ink-music-$version-linux.rpm"
ls -la build/release/ink-music-"$version"-linux.*
