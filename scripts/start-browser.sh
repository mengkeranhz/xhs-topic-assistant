#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="$ROOT/.local/xhs-browser-profile"
mkdir -p "$PROFILE"
if [[ -d /Applications/Google\ Chrome.app ]]; then
  open -na "Google Chrome" --args \
    --remote-debugging-port=9222 \
    --user-data-dir="$PROFILE" \
    "https://www.xiaohongshu.com"
elif command -v chromium >/dev/null 2>&1; then
  chromium --remote-debugging-port=9222 --user-data-dir="$PROFILE" "https://www.xiaohongshu.com" &
elif command -v chromium-browser >/dev/null 2>&1; then
  chromium-browser --remote-debugging-port=9222 --user-data-dir="$PROFILE" "https://www.xiaohongshu.com" &
else
  echo "Chrome/Chromium not found. Install it or edit this script." >&2
  exit 1
fi
echo "CDP browser profile: $PROFILE"
echo "Manually log in to Xiaohongshu, then keep this browser open."
