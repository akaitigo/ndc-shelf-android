#!/usr/bin/env bash
# GitHub Actionsの参照が全て完全なcommit SHAへ固定されていることを検証する。
#
# ripgrepはGitHubのrunner imageに含まれないため、POSIXのgrepだけを使う。
# 参照が1件も取れない場合は「検査対象ゼロで成功」を防ぐため失敗させる。
set -euo pipefail

references="$(grep -rhE '^[[:space:]]*-?[[:space:]]*uses:[[:space:]]+' .github/workflows || true)"

if [[ -z "$references" ]]; then
  echo "No 'uses:' references were found under .github/workflows." >&2
  echo "The pinning check would silently pass, so this is treated as a failure." >&2
  exit 1
fi

status=0
while IFS= read -r reference; do
  [[ -z "$reference" ]] && continue
  target="${reference#*uses: }"
  target="${target%% *}"
  [[ "$target" == ./* ]] && continue

  revision="${target##*@}"
  if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
    echo "GitHub Action is not pinned to a full commit SHA: $target" >&2
    status=1
  fi
done <<<"$references"

(( status == 0 )) || exit "$status"
echo "GitHub Actions are pinned to immutable commit SHAs ($(grep -c . <<<"$references") references)."
