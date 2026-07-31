#!/usr/bin/env bash
# 旧安定版APKへ現行APKを上書きインストールし、アプリdataが消えないことを検証する。
# リリースゲート（#19/#29/#35/#55）の「旧版から更新して既存蔵書が保持される」の
# うち、端末側の更新インストール互換（署名・versionCode・data保持）を自動化する。
set -euo pipefail

package="dev.ndcshelf.app"
baseline_apk="${1:?usage: verify-update-install.sh <baseline.apk> <current.apk>}"
current_apk="${2:?usage: verify-update-install.sh <baseline.apk> <current.apk>}"
marker="update-install-marker.txt"

echo "::group::Install baseline APK"
adb install -r "$baseline_apk"
adb shell pm list packages | grep -q "package:$package"
echo "::endgroup::"

echo "::group::Create data in the baseline install"
# Roomは初回アクセスまでDBを作らないため、アプリdata配下へ直接マーカーを置き、
# 更新インストールがアプリdataを消さないことを検証する。
adb shell run-as "$package" sh -c "printf 'baseline-data' > files/$marker"
adb shell run-as "$package" cat "files/$marker" | grep -q 'baseline-data'
echo "::endgroup::"

echo "::group::Update install with the current APK"
# -r はdataを保持する更新インストール。署名不一致やversionCode低下はここで失敗する。
adb install -r "$current_apk"
echo "::endgroup::"

echo "::group::Verify data survived the update"
if ! adb shell run-as "$package" cat "files/$marker" | grep -q 'baseline-data'; then
  echo "::error::更新インストールでアプリdataが失われた" >&2
  exit 1
fi
echo "::endgroup::"

echo "::group::Verify the updated app launches"
adb logcat -c || true
adb shell am start -W -n "$package/.MainActivity"
sleep 5
if adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION"; then
  echo "::error::更新後の起動でクラッシュした" >&2
  adb logcat -d -s AndroidRuntime:E
  exit 1
fi
echo "::endgroup::"

installed_version=$(adb shell dumpsys package "$package" | grep -m1 versionCode | tr -s ' ' | cut -d' ' -f2)
echo "Update install verified. Installed $installed_version with preserved app data."
