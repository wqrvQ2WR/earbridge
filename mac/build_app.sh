#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

APP="EarBridge.app"
ARCHS=(--arch arm64 --arch x86_64)
swift build -c release "${ARCHS[@]}"
BIN="$(swift build -c release "${ARCHS[@]}" --show-bin-path)"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/EarBridge" "$APP/Contents/MacOS/EarBridge"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/*.otf Resources/AppIcon.icns "$APP/Contents/Resources/"

codesign --force --deep --sign - "$APP"
echo "Done: $(pwd)/$APP"

# ./build_app.sh --install 이면 응용 프로그램 폴더에도 넣고 다시 켠다
if [ "${1:-}" = "--install" ]; then
  pkill -x EarBridge || true
  rm -rf /Applications/EarBridge.app
  ditto "$APP" /Applications/EarBridge.app
  open /Applications/EarBridge.app
  echo "Installed: /Applications/EarBridge.app"
fi
