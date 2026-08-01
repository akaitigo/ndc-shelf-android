#!/usr/bin/env bash
# ダウンロードしたコンプライアンス成果物（SBOM・ライセンス通知）が
# 実在し、空でなく、JSONとして読めることを検証する。
#
# upload-artifact は複数pathを与えると共通の親ディレクトリを剥がすため、
# 参照側のパスがずれても「ファイルが無いまま検査が素通り」しやすい。
# 実際にOSVスキャンが対象ゼロで成功し続けていたため、明示的に落とす。
set -euo pipefail

directory="${1:?usage: verify-compliance-artifacts.sh <directory>}"

status=0
for name in ndc-shelf.cdx.json THIRD-PARTY-NOTICES.json; do
  path="$directory/$name"
  if [[ ! -f "$path" ]]; then
    echo "Compliance artifact is missing: $path" >&2
    echo "Downloaded layout:" >&2
    find "$directory" -type f -print >&2 || true
    status=1
    continue
  fi
  if [[ ! -s "$path" ]]; then
    echo "Compliance artifact is empty: $path" >&2
    status=1
    continue
  fi
  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$path" >/dev/null 2>&1; then
    echo "Compliance artifact is not valid JSON: $path" >&2
    status=1
  fi
done

(( status == 0 )) || exit "$status"

# 依存が1件も載っていないSBOMは、OSVスキャンを実質的に無効化する。
components=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1])).get("components", [])))' \
  "$directory/ndc-shelf.cdx.json")
if (( components == 0 )); then
  echo "SBOM lists no components; the vulnerability scan would cover nothing." >&2
  exit 1
fi

echo "Compliance artifacts are present under $directory ($components SBOM components)."
