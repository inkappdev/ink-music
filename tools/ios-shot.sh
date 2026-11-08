#!/usr/bin/env bash
# Pictures of the iPhone app's screens, taken on the iPhone and copied here (it must be unlocked): the app relaunched with
# -inkShot (Shots.kt), each step shown and saved, then copied off into build/ios-shots.
#   tools/ios-shot.sh listen,records,ink,play,player
set -euo pipefail
cd "$(dirname "$0")/.."
MAC=bronsonsnelling@192.168.1.227
DEV=00008140-0011446C0A38801C
steps=${1:-listen,records,find,ink,podcasts,radio,settings,play,player}
n=$(( $(tr ',' '\n' <<<"$steps" | wc -l) * 6 + 30 ))
ssh "$MAC" "rm -rf ~/ink-build/shots; mkdir -p ~/ink-build/shots; timeout $n xcrun devicectl device process launch --device $DEV --terminate-existing --console app.ink.music.iphone -- -inkShot $steps 2>&1 | grep -E '^SHOT' ; \
  for s in \$(tr ',' ' ' <<<'$steps'); do xcrun devicectl device copy from --device $DEV --domain-type appDataContainer --domain-identifier app.ink.music.iphone \
  --source \"Library/Application Support/ink/shot-\$s.png\" --destination ~/ink-build/shots/\$s.png >/dev/null 2>&1 || true; done"
mkdir -p build/ios-shots
rsync -a "$MAC:ink-build/shots/" build/ios-shots/
ls build/ios-shots
