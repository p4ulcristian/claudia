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

python3 - "$APP/Info.plist" Icon/compiled/partial.plist <<EOF
import os, plistlib, sys
info_path, icon_plist = sys.argv[1], sys.argv[2]
p = plistlib.load(open(info_path, "rb"))
if os.path.exists(icon_plist):
    p.pop("CFBundleIconFile", None)
    p.update(plistlib.load(open(icon_plist, "rb")))   # CFBundleIcons / CFBundleIconName from actool
p.update({
    "CFBundleIdentifier": "$BUNDLE_ID",
    "CFBundleVersion": "$BUILD_NUMBER",
    "ClaudiaHubURL": "$HUB_URL",                  # from the local config, never committed
    "ClaudiaLoginHosts": "$LOGIN_HOSTS".split(),
    "UIDeviceFamily": [1],                       # iPhone only
    "ITSAppUsesNonExemptEncryption": False,      # HTTPS only
    "DTPlatformName": "iphoneos", "DTPlatformVersion": "$SDK_VERSION", "DTPlatformBuild": "$SDK_BUILD",
    "DTSDKName": "iphoneos$SDK_VERSION", "DTSDKBuild": "$SDK_BUILD",
    "DTXcode": "$XCODE", "DTXcodeBuild": "$XCODE_BUILD", "DTCompiler": "com.apple.compilers.llvm.clang.1_0",
})
p.pop("UISupportedInterfaceOrientations~ipad", None)
plistlib.dump(p, open(info_path, "wb"))
EOF

# Profile and entitlements, both checked against the App ID through the App Store Connect API.
$PY tools/signing.py profile "$BUNDLE_ID" "$DIST_CERT_ID" "$CONFIG/appstore.mobileprovision"
cp "$CONFIG/appstore.mobileprovision" "$APP/embedded.mobileprovision"
$PY tools/signing.py entitlements "$CONFIG/appstore.mobileprovision" "$PASSKEY_HOST" build/entitlements.plist

openssl x509 -inform der -in "$DIST_CER" -out build/dist.pem
rcodesign sign --pem-file "$DIST_KEY" --pem-file build/dist.pem \
  --entitlements-xml-file build/entitlements.plist "$APP"
rm build/dist.pem
(cd build && zip -qry Claudia.ipa Payload)
echo "build/Claudia.ipa: build $BUILD_NUMBER"

[ "${1:-}" = "--no-upload" ] && exit 0
# The App Store Connect build upload API (tools/upload.py); the Linux Transporter refuses
# to upload apps because it cannot analyse them without Xcode.
$PY tools/upload.py "$ASC_APP_ID" build/Claudia.ipa
