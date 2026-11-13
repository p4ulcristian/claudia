#!/usr/bin/env bash
# Render the page's app icon (web/app-icon/icon.svg, full bleed, already sized for iOS's mask)
# to iphone/Icon/AppIcon.png: 1024x1024, no alpha channel (App Store Connect refuses icons with one).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p Icon
rsvg-convert -w 1024 -h 1024 ../web/app-icon/icon.svg -o Icon/AppIcon.rgba.png
magick Icon/AppIcon.rgba.png -background '#2c3833' -alpha remove -alpha off -define png:color-type=2 Icon/AppIcon.png
rm Icon/AppIcon.rgba.png
magick identify Icon/AppIcon.png
