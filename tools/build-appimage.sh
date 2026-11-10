#!/usr/bin/env bash
# Linux's AppImage (2026-11-09, asked for on Reddit): one file that runs on any recent distribution, nothing installed.
# Built as build-linux.sh builds the deb and rpm, in an Ubuntu 24.04 container (podman) with the PC's JDK copied in;
# the same finished app (createDistributable), but carrying its own libmpv (the deb and rpm ask the system for it):
# libmpv and the libraries it needs, out of Ubuntu's, beside it in the app's resources (Mpv.kt loads that one first),
# each told to find the others beside itself ($ORIGIN). Left out, the system's own: glibc, the GPU's, X and Wayland,
# fonts, ALSA, D-Bus and udev (each tied to its system, and on every desktop); so glibc 2.39 (2024) at the least. JACK
# and systemd's are carried: a system's libjack can be PipeWire's, built for a newer PipeWire than the one carried
# (Fedora 45's: libmpv wouldn't load), and a system without systemd has no libsystemd.
# appimagetool and its runtime fetched once, at pinned releases, into ~/.cache/ink-appimage.
# Leaves build/release/ink-music-<version>-linux.AppImage. Runs as one (APPIMAGE set), it updates itself (Updates.kt).
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -f keystore.properties ] || { echo "no keystore.properties: without it, no Last.fm"; exit 1; }
jdk=${JDK:-$HOME/.local/share/jdk-21}
work=${TMPDIR:-/tmp}/ink-linux-build
mkdir -p "$work/gradle"
[ -x "$work/jdk/bin/java" ] || { rm -rf "$work/jdk"; cp -r "$jdk" "$work/jdk"; }

tools=$HOME/.cache/ink-appimage
mkdir -p "$tools"
get() { [ -s "$tools/$2" ] || curl -fsSL -o "$tools/$2" "$1"; chmod +x "$tools/$2"; }
get https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage appimagetool
get https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64 runtime
(cd "$tools" && sha256sum appimagetool runtime)
rm -rf "$work/tools" && cp -r "$tools" "$work/tools"

podman run --rm -v "$PWD":/src:Z -v "$work/gradle":/gradle:Z -v "$work/jdk":/jdk:Z -v "$work/tools":/tools:Z \
  -e JAVA_HOME=/jdk -e GRADLE_USER_HOME=/gradle -e GITHUB_SHA="$(git rev-parse HEAD)" -e VERSION="$version" \
  -w /src docker.io/library/ubuntu:24.04 bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive PATH=/jdk/bin:$PATH
apt-get update -qq >/dev/null
apt-get install -y -qq libmpv2 patchelf file binutils >/dev/null 2>&1
rm -rf "desktop/build/compose/binaries/main/app"
./gradlew -q :desktop:createDistributable -PdesktopOnly --no-daemon
app="desktop/build/compose/binaries/main/app/ink music"
dir=build/appimage/AppDir
rm -rf build/appimage && mkdir -p "$dir"
cp -a "$app" "$dir/ink-music"
res="$dir/ink-music/lib/app/resources"
mkdir -p "$res"
# libmpv, and what it needs but the systems own (by soname)
mpv=$(readlink -f /usr/lib/x86_64-linux-gnu/libmpv.so.2)
cp "$mpv" "$res/libmpv.so.2"
keep_out="^(ld-linux|libc|libm|libdl|libpthread|librt|libresolv|libutil|libanl|libnsl|libmvec|libstdc\+\+|libgcc_s|libGL|libGLX|libGLdispatch|libEGL|libOpenGL|libGLESv2|libgbm|libdrm|libX11|libX11-xcb|libxcb|libxcb-.*|libXext|libXrender|libXi|libXtst|libXau|libXdmcp|libwayland-.*|libfontconfig|libfreetype|libharfbuzz|libexpat|libz|libasound|libdbus-1|libudev|libglib-2\.0|libgobject-2\.0|libgio-2\.0|libgmodule-2\.0)\.so"
ldd "$res/libmpv.so.2" | awk "/=> \// { print \$1, \$3 }" | while read -r name path; do
  echo "$name" | grep -Eq "$keep_out" && continue
  cp -L "$path" "$res/$name"
done
chmod u+w "$res"/*.so*
for f in "$res"/*.so*; do patchelf --set-rpath "\$ORIGIN" "$f"; done
# what ldd still cant find, from inside the app as it will run (nothing, or the build stops)
missing=$(LD_LIBRARY_PATH= ldd "$res/libmpv.so.2" | grep "not found" || true)
[ -z "$missing" ] || { echo "libmpv misses: $missing"; exit 1; }
echo "carried with libmpv: $(ls "$res" | wc -l) libraries, $(du -sh "$res" | cut -f1)"
cp desktop/src/main/resources/ink-music.png "$dir/ink-music.png"
cp "$dir/ink-music.png" "$dir/.DirIcon"
cat > "$dir/ink-music.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=ink music
Comment=A music player in ink'"'"'s look
Exec=ink-music %U
Icon=ink-music
Categories=AudioVideo;Audio;Player;
StartupWMClass=ink-music-ink_music
EOF
cat > "$dir/AppRun" <<"EOF"
#!/bin/sh
here=$(dirname "$(readlink -f "$0")")
exec "$here/ink-music/bin/ink music" "$@"
EOF
chmod +x "$dir/AppRun"
mkdir -p build/release
ARCH=x86_64 /tools/appimagetool --appimage-extract-and-run --no-appstream --runtime-file /tools/runtime "$dir" "build/release/ink-music-$VERSION-linux.AppImage" >/dev/null
chown --reference=/src build/release/ink-music-$VERSION-linux.AppImage
'
ls -la build/release/ink-music-"$version"-linux.AppImage
