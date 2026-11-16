#!/bin/bash
# The app in the iOS Simulator, on a Mac with Xcode and XcodeGen (brew install xcodegen): a debug
# build of Sources/ (the app and ClaudiaShared; not the extensions) with the hub address given, so
# it can open a local test stack (node bin/stack-serve.mjs) or any hub. Installs into a booted
# simulator and starts it.
#
#   iphone/tools/simulator.sh <simulator udid> [hub url]     (default http://127.0.0.1:4599)
#
# The build is com.example.claudia.sim, unsigned, allowed plain HTTP; never for TestFlight.
set -euo pipefail
udid=${1:?simulator udid (xcrun simctl list devices available)}
hub=${2:-http://127.0.0.1:4599}
here=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d /tmp/claudia-sim.XXXXXX)
trap 'rm -rf "$work"' EXIT
cp -R "$here/Sources" "$work/"
python3 - "$here/Info.plist" "$work/Info.plist" "$hub" <<'PY'
import plistlib, sys
p = plistlib.load(open(sys.argv[1], "rb"))
p.update({"CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleExecutable": "$(EXECUTABLE_NAME)",
          "CFBundleName": "Claudia", "CFBundlePackageType": "APPL", "CFBundleVersion": "1",
          "ClaudiaHubURL": sys.argv[3], "ClaudiaLoginHosts": [],
          "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True}})
plistlib.dump(p, open(sys.argv[2], "wb"))
PY
cat >"$work/project.yml" <<'YML'
name: ClaudiaSim
options:
  deploymentTarget:
    iOS: "17.0"
targets:
  ClaudiaShared:
    type: framework
    platform: iOS
    sources: [Sources/ClaudiaShared]
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.example.claudia.sim.shared
        SWIFT_VERSION: "5"
        GENERATE_INFOPLIST_FILE: YES
        CODE_SIGNING_ALLOWED: NO
  Claudia:
    type: application
    platform: iOS
    sources: [Sources/Claudia]
    dependencies:
      - target: ClaudiaShared
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.example.claudia.sim
        PRODUCT_NAME: Claudia
        SWIFT_VERSION: "5"
        TARGETED_DEVICE_FAMILY: "1,2"
        INFOPLIST_FILE: Info.plist
        GENERATE_INFOPLIST_FILE: NO
        CODE_SIGNING_ALLOWED: NO
YML
(cd "$work" && xcodegen generate --quiet)
xcodebuild -project "$work/ClaudiaSim.xcodeproj" -scheme Claudia -sdk iphonesimulator \
  -destination "generic/platform=iOS Simulator" -derivedDataPath "$work/build" -quiet build
xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b >/dev/null
xcrun simctl install "$udid" "$work/build/Build/Products/Debug-iphonesimulator/Claudia.app"
xcrun simctl privacy "$udid" grant microphone com.example.claudia.sim
xcrun simctl launch --terminate-running-process "$udid" com.example.claudia.sim
