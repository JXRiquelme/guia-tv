#!/bin/bash
# Builds "AerioTV Lite.app" (Apple Silicon) with libmpv from Homebrew bundled inside.
set -euo pipefail
cd "$(dirname "$0")"
PREFIX="$(brew --prefix)"
rm -rf build && mkdir -p build

swiftc -O -parse-as-library -swift-version 5 \
  -target arm64-apple-macos14.0 \
  -import-objc-header Sources/bridge.h \
  -I "$PREFIX/include" -L "$PREFIX/lib" -lmpv \
  -Xcc -DGL_SILENCE_DEPRECATION \
  Sources/*.swift -o build/AerioLite

APP="build/AerioTV Lite.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Frameworks" "$APP/Contents/Resources"
cp build/AerioLite "$APP/Contents/MacOS/AerioLite"
cp Resources/Info.plist "$APP/Contents/Info.plist"

mkdir -p build/AppIcon.iconset
for s in 16 32 128 256 512; do
  sips -z $s $s Resources/icon.png --out "build/AppIcon.iconset/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) Resources/icon.png --out "build/AppIcon.iconset/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns build/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"

# Copy libmpv and everything it links into the app and point the binary at them.
dylibbundler -od -b -cd -x "$APP/Contents/MacOS/AerioLite" -d "$APP/Contents/Frameworks/" \
  -p @executable_path/../Frameworks/ -s "$PREFIX/lib"

# Ad-hoc signature (required on Apple Silicon); no Apple developer account involved.
find "$APP/Contents/Frameworks" -type f -exec codesign --force --sign - {} \;
codesign --force --sign - "$APP"
codesign --verify --verbose "$APP"

du -sh "$APP"
otool -L "$APP/Contents/MacOS/AerioLite" | head -20
cd build && ditto -c -k --sequesterRsrc --keepParent "AerioTV Lite.app" AerioTV-Lite-Mac.zip
ls -la AerioTV-Lite-Mac.zip
