#!/usr/bin/env bash
# Build Claudia, sign it for the App Store and upload it to TestFlight, all from Linux
# (Roomprint's recipe: xtool builds, rcodesign signs, the App Store Connect API uploads).
#
#   iphone/tools/testflight.sh              build, sign, upload
#   iphone/tools/testflight.sh --no-upload  stop after making iphone/build/Claudia.ipa
#
# Needs (none of it in git): the local config folder, ~/.config/claudia-ios (or
# $CLAUDIA_IOS_CONFIG), with `env` (see iphone/README.md); xtool, rcodesign, uv; the compiled
# icon in iphone/Icon/compiled/ (tools/fetch-icon.sh).
#
# The app carries two extensions (xtool.yml `extensions`): PlugIns/Widget.appex (the summary
# Live Activity) and PlugIns/NotificationService.appex (decrypts the notification text). Each
# bundle is signed with its own bundle id's profile and entitlements.
set -euo pipefail
cd "$(dirname "$0")/.."
CONFIG=${CLAUDIA_IOS_CONFIG:-$HOME/.config/claudia-ios}
set -a; . "$CONFIG/env"; set +a
: "${HUB_URL:?}" "${LOGIN_HOSTS:?}" "${PASSKEY_HOST:?}" "${ASC_APP_ID:?}" "${DIST_KEY:?}" "${DIST_CER:?}" "${DIST_CERT_ID:?}"
XTOOL=${XTOOL:-xtool}
BUNDLE_ID=com.p4ulcristian.claudia   # the App Store Connect app's bundle id; also the APNs topic
BUILD_NUMBER=$(date -u +%Y%m%d%H%M)
PY="uv run -q --with pyjwt --with cryptography"

# SDK the app is compiled against, stamped into Info.plist like Xcode does (and into the
# binary by the linker flags in Package.swift); App Store Connect rejects builds without
# these. Xcode values: the Xcode that ships this SDK.
SDK_VERSION=26.5
SDK_BUILD=23F81a
XCODE=${XCODE:-2650}
XCODE_BUILD=${XCODE_BUILD:-17F42}

"$XTOOL" dev build --configuration release
rm -rf build && mkdir -p build/Payload
cp -R xtool/Claudia.app build/Payload/
APP=build/Payload/Claudia.app

# The icon: App Store Connect takes it only from an actool-compiled asset catalog (loose PNGs
# fail processing with 90022 + 91111). The app-icon workflow makes it on a macOS runner;
# tools/fetch-icon.sh puts it in Icon/compiled/.
if [ ! -f Icon/compiled/Assets.car ] && [ "${1:-}" != "--no-upload" ]; then
  echo "no Icon/compiled/Assets.car: run the app-icon workflow, then tools/fetch-icon.sh" >&2
  exit 1
fi
[ -f Icon/compiled/Assets.car ] && cp Icon/compiled/Assets.car Icon/compiled/*.png "$APP/"

# The app's Info.plist, then each extension's: the same version and build (App Store Connect
# requires it) and the same SDK stamp.
python3 - "$APP" Icon/compiled/partial.plist <<EOF
import os, plistlib, sys
app, icon_plist = sys.argv[1], sys.argv[2]
stamp = {
    "CFBundleVersion": "$BUILD_NUMBER",
    "CFBundleSupportedPlatforms": ["iPhoneOS"],
    "UIDeviceFamily": [1],                       # iPhone only
    "DTPlatformName": "iphoneos", "DTPlatformVersion": "$SDK_VERSION", "DTPlatformBuild": "$SDK_BUILD",
    "DTSDKName": "iphoneos$SDK_VERSION", "DTSDKBuild": "$SDK_BUILD",
    "DTXcode": "$XCODE", "DTXcodeBuild": "$XCODE_BUILD", "DTCompiler": "com.apple.compilers.llvm.clang.1_0",
}
info_path = f"{app}/Info.plist"
p = plistlib.load(open(info_path, "rb"))
if os.path.exists(icon_plist):
    p.pop("CFBundleIconFile", None)
    p.update(plistlib.load(open(icon_plist, "rb")))   # CFBundleIcons / CFBundleIconName from actool
p.update(stamp)
p.update({
    "CFBundleIdentifier": "$BUNDLE_ID",
    "ClaudiaHubURL": "$HUB_URL",                  # from the local config, never committed
    "ClaudiaLoginHosts": "$LOGIN_HOSTS".split(),
    "ITSAppUsesNonExemptEncryption": False,      # only the OS's standard encryption: HTTPS, CryptoKit AES-GCM
})
p.pop("UISupportedInterfaceOrientations~ipad", None)
plistlib.dump(p, open(info_path, "wb"))
for ext in sorted(os.listdir(f"{app}/PlugIns")):
    path = f"{app}/PlugIns/{ext}/Info.plist"
    e = plistlib.load(open(path, "rb"))
    e.update(stamp)
    e["CFBundleShortVersionString"] = p["CFBundleShortVersionString"]
    plistlib.dump(e, open(path, "wb"))
EOF

# Each bundle's profile and entitlements, both checked against its App ID through the App Store
# Connect API (an extension's bundle id is registered there the first time).
SCOPED=()
sign_with() {   # <path in the app> <bundle id> <name in App Store Connect> <signing.py role>
  local path=$1 id=$2 name=$3 role=$4 profile="$CONFIG/appstore-$4.mobileprovision"
  [ -d "$APP/$path" ] || { echo "the build has no $path" >&2; exit 1; }
  $PY tools/signing.py profile "$id" "$name" "$DIST_CERT_ID" "$profile"
  cp "$profile" "$APP/$path/embedded.mobileprovision"
  if [ "$role" = app ]; then
    $PY tools/signing.py entitlements app "$profile" "$PASSKEY_HOST" "build/$role.entitlements"
    SCOPED+=(--entitlements-xml-file "build/$role.entitlements")
  else
    $PY tools/signing.py entitlements "$role" "$profile" "build/$role.entitlements"
    SCOPED+=(--entitlements-xml-file "$path:build/$role.entitlements")
  fi
}
sign_with . "$BUNDLE_ID" "Claudia" app
sign_with PlugIns/Widget.appex "$BUNDLE_ID.Widget" "Claudia Widget" widget
sign_with PlugIns/NotificationService.appex "$BUNDLE_ID.NotificationService" "Claudia NotificationService" notifications

openssl x509 -inform der -in "$DIST_CER" -out build/dist.pem
# One pass: the extensions (inside out), then the app, each with its own entitlements.
rcodesign sign --pem-file "$DIST_KEY" --pem-file build/dist.pem "${SCOPED[@]}" "$APP"
rm build/dist.pem
(cd build && zip -qry Claudia.ipa Payload)
echo "build/Claudia.ipa: build $BUILD_NUMBER"

[ "${1:-}" = "--no-upload" ] && exit 0
# The App Store Connect build upload API (tools/upload.py); the Linux Transporter refuses
# to upload apps because it cannot analyse them without Xcode.
$PY tools/upload.py "$ASC_APP_ID" build/Claudia.ipa
