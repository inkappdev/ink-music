#!/usr/bin/env bash
# Windows' msi, built on the Windows machine kept on the Umbrel (umbrelOS's own Windows 11, "ink-windows-build"),
# for as long as GitHub's Actions can't (ink's account suspended 2026-11-01), and for whenever a build's wanted
# without them. Reached by SSH through the Umbrel (the machine is on its own network there): this PC's key only.
#
#     tools/windows-vm.sh build    the code at HEAD built there: build/release/ink-music-<version>-windows.msi
#     tools/windows-vm.sh shell    a PowerShell on it
#
# The machine is started and stopped from umbrelOS (its Machines page). Set up once (2026-11-04): OpenSSH with
# this PC's key, portable Git and Temurin 21 in C:\b (git, jdk), Gradle's cache in C:\b\gradle. Last.fm's key
# (keystore.properties) goes over for each build and is deleted after it. Slimmed for building (no automatic updates,
# indexing, telemetry or Store apps; Defender skips C:\b): a build takes about 8 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
UMBREL=${UMBREL:-umbrel@umbrel.local}
VM=${VM:-$(cat ~/.config/ink-music/windows-vm.ip 2>/dev/null || true)}
[ -n "$VM" ] || { echo "no address for the Windows machine: ~/.config/ink-music/windows-vm.ip"; exit 1; }
opts=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$HOME/.config/ink-music/windows-vm.known_hosts"
      -o LogLevel=ERROR -o ConnectTimeout=10 -J "$UMBREL")
win() { ssh "${opts[@]}" "ink@$VM" "$@"; }
put() { scp -q "${opts[@]}" "$1" "ink@$VM:$2"; }
get() { scp -q "${opts[@]}" "ink@$VM:$1" "$2"; }

case "${1:-}" in
  shell) exec ssh -t "${opts[@]}" "ink@$VM" ;;
  build) ;;
  *) echo "tools/windows-vm.sh build | shell"; exit 1 ;;
esac

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
sha=$(git rev-parse --short HEAD)
[ -f keystore.properties ] || { echo "no keystore.properties: without it, no Last.fm"; exit 1; }
win 'echo ok' >/dev/null || { echo "the Windows machine doesn't answer: is it started (umbrelOS, Machines)?"; exit 1; }

zip=$(mktemp --suffix=.zip)
git archive --format=zip -o "$zip" HEAD
put "$zip" C:/b/src.zip
put keystore.properties C:/b/keystore.properties
rm -f "$zip"
trap 'win "Remove-Item -Force C:\b\keystore.properties, C:\b\src\keystore.properties -ErrorAction SilentlyContinue" || true' EXIT

echo "building $sha on Windows"
win '$ProgressPreference="SilentlyContinue"; $ErrorActionPreference="Stop"
if (Test-Path C:\b\src) { Remove-Item -Recurse -Force C:\b\src }
Expand-Archive C:\b\src.zip -DestinationPath C:\b\src; Remove-Item C:\b\src.zip
Copy-Item C:\b\keystore.properties C:\b\src\
$env:JAVA_HOME="C:\b\jdk"; $env:GRADLE_USER_HOME="C:\b\gradle"
& C:\b\git\bin\bash.exe -lc "cd /c/b/src && GITHUB_SHA='"$sha"' tools/build-windows.sh 2>&1 | grep -v ^w: | tail -15"'

mkdir -p build/release
get "C:/b/src/build/release/ink-music-$version-windows.msi" "build/release/ink-music-$version-windows.msi"
ls -la "build/release/ink-music-$version-windows.msi"
