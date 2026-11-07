#!/usr/bin/env bash
# ink music on Bronson's iPhone, from this PC: the tree copied to the Mac (~/ink-build/ios), tools/build-ios.sh run in
# the Mac's own Terminal (an SSH session can't reach the signing key in the login keychain: errSecInternalComponent;
# Terminal, in the logged-in session, can, codesign allowed once), its output read back. Also the weekly re-sign (a free
# Apple ID's builds last 7 days): run it again.
#   tools/ios-remote.sh          build, install on the iPhone, open
#   tools/ios-remote.sh build    build only
set -euo pipefail
cd "$(dirname "$0")/.."
MAC=${MAC:-bronsonsnelling@192.168.1.227}
rsync -a --delete --exclude build --exclude .gradle --exclude .git --exclude '*.apk' --exclude keystore.properties --exclude local.properties ./ "$MAC:ink-build/ios/"
ssh "$MAC" "rm -f ~/ink-build/ios-build.log; osascript -e 'tell application \"Terminal\" to do script \"cd ~/ink-build/ios && JAVA_HOME=~/ink-build/jdk/Contents/Home tools/build-ios.sh ${1:-} > ~/ink-build/ios-build.log 2>&1; echo EXIT \$? >> ~/ink-build/ios-build.log; exit\"' >/dev/null"
for _ in $(seq 1 240); do ssh "$MAC" 'grep -q "^EXIT" ~/ink-build/ios-build.log 2>/dev/null' && break; sleep 5; done
ssh "$MAC" 'grep -vE "^\s*$|IDERunDestination|DVTiPhonePlaceholder|warning:" ~/ink-build/ios-build.log | tail -15'
ssh "$MAC" 'grep -q "^EXIT 0" ~/ink-build/ios-build.log'
