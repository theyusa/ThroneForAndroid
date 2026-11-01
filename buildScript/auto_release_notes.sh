#!/usr/bin/env bash
set -euo pipefail

# auto_release_notes.sh <tag> <version-name> [notes-source-file]
#
# Generates release notes for an automatic release: the list of commits synced
# from upstream (read from the annotated tag message written by the sync
# workflow) plus an Android APK download table (per-ABI shields.io badges).
#
# <notes-source-file> holds one "sha subject" per line. If empty or missing,
# the last 10 commits are used as a fallback.

TAG="${1:?usage: auto_release_notes.sh <tag> <version> [notes-file]}"
VERSION_NAME="${2:-${TAG#v}}"
NOTES_FILE="${3:-}"

REPO="theyusa/ThroneForAndroid"
BASE_URL="https://github.com/${REPO}/releases/download/${TAG}"

echo "# 🚀 ${TAG}"
echo
echo "*Synced with upstream and rebuilt the Hotspot Gateway patch — $(date -u +%Y-%m-%d).*"
echo
echo "## 📦 What's Changed"
echo

emit_commits() {
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        sha="${line%% *}"
        subject="${line#* }"
        echo "* ${subject} (\`${sha}\`)"
    done
}

if [[ -n "$NOTES_FILE" && -s "$NOTES_FILE" ]]; then
    emit_commits < "$NOTES_FILE"
else
    git log --pretty=format:'%h %s' -10 2>/dev/null | emit_commits
fi

echo
echo "---"
echo
echo '<div align="center">'
echo
echo "[![Release Downloads](https://img.shields.io/github/downloads/${REPO}/${TAG}/total?style=flat-square&logo=github)](${BASE_URL})"
echo
echo "</div>"
echo
echo "### ⬇️ Download"
echo
echo "Choose the APK for your device's CPU. **Most phones (2015+) are \`arm64-v8a\`.**"
echo
echo "| Architecture | Download |"
echo "|---|---|"
echo "| ARM64 — modern phones | [![arm64-v8a](https://img.shields.io/badge/APK-arm64--v8a-3DDC84?style=flat-square&logo=android&logoColor=white)](${BASE_URL}/Throne-${VERSION_NAME}-arm64-v8a.apk) |"
echo "| ARMv7 — 32-bit devices | [![armeabi-v7a](https://img.shields.io/badge/APK-armeabi--v7a-3DDC84?style=flat-square&logo=android&logoColor=white)](${BASE_URL}/Throne-${VERSION_NAME}-armeabi-v7a.apk) |"
echo "| x86_64 — emulators | [![x86_64](https://img.shields.io/badge/APK-x86__64-3DDC84?style=flat-square&logo=android&logoColor=white)](${BASE_URL}/Throne-${VERSION_NAME}-x86_64.apk) |"
echo
echo "> 🔐 Verify integrity with [SHA256SUMS](${BASE_URL}/SHA256SUMS)."
