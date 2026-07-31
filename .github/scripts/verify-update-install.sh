#!/usr/bin/env bash
# 旧安定版APKへ現行APKを上書きインストールし、アプリdataが消えないことを検証する。
# リリースゲート（#19/#29/#35/#55）の「旧版から更新して既存蔵書が保持される」の
# うち、端末側の更新インストール互換（署名・versionCode・data保持）と、
# 旧版が実際に作成したRoom DBを現行版が開けることを自動化する。
set -euo pipefail

package="dev.ndcshelf.app"
baseline_apk="${1:?usage: verify-update-install.sh <baseline.apk> <current.apk>}"
current_apk="${2:?usage: verify-update-install.sh <baseline.apk> <current.apk>}"
marker="files/update-install-marker.txt"
database="databases/ndc-shelf.db"

# リダイレクトを含むコマンドは、adb shell側の外殻シェルではなく run-as 配下の
# シェルで解釈させる必要がある。コマンド全体を単一の文字列としてデバイスへ渡す。
run_as() {
  adb shell "run-as $package sh -c '$1'"
}

echo "::group::Install baseline APK"
adb install -r "$baseline_apk"
adb shell pm list packages | grep -q "package:$package"
echo "::endgroup::"

echo "::group::Let the baseline app create its database"
adb shell am start -W -n "$package/.MainActivity"
# Roomは初回アクセスで作られるため、生成を待ってから存在を確認する。
for _ in $(seq 1 20); do
  if run_as "ls $database" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if ! run_as "ls $database" >/dev/null 2>&1; then
  echo "::error::baselineアプリがRoom DBを作成しなかった" >&2
  run_as "ls -R ." || true
  exit 1
fi
echo "baseline database created"
echo "::endgroup::"

echo "::group::Create marker data in the baseline install"
run_as "mkdir -p files && printf baseline-data > $marker"
run_as "cat $marker" | grep -q 'baseline-data'
echo "::endgroup::"

echo "::group::Update install with the current APK"
adb shell am force-stop "$package"
# -r はdataを保持する更新インストール。署名不一致やversionCode低下はここで失敗する。
adb install -r "$current_apk"
echo "::endgroup::"

echo "::group::Verify data survived the update"
if ! run_as "cat $marker" | grep -q 'baseline-data'; then
  echo "::error::更新インストールでアプリdataが失われた" >&2
  exit 1
fi
if ! run_as "ls $database" >/dev/null 2>&1; then
  echo "::error::更新インストールで蔵書DBが失われた" >&2
  exit 1
fi
echo "::endgroup::"

echo "::group::Verify the updated app opens the legacy database"
adb logcat -c || true
adb shell am start -W -n "$package/.MainActivity"
sleep 8
if adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION"; then
  echo "::error::更新後の起動でクラッシュした（旧DBの移行失敗の可能性）" >&2
  adb logcat -d -s AndroidRuntime:E
  exit 1
fi
if ! adb shell pidof "$package" >/dev/null; then
  echo "::error::更新後のアプリプロセスが起動していない" >&2
  adb logcat -d -s AndroidRuntime:E
  exit 1
fi
echo "::endgroup::"

echo "Update install verified: app data and the legacy database survived, and the app runs."
