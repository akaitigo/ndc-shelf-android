#!/usr/bin/env bash
set -euo pipefail

status=0
while IFS= read -r reference; do
  target="${reference#*uses: }"
  target="${target%% *}"
  [[ "$target" == ./* ]] && continue

  revision="${target##*@}"
  if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
    echo "GitHub Action is not pinned to a full commit SHA: $target" >&2
    status=1
  fi
done < <(rg --no-filename '^[[:space:]]*-?[[:space:]]*uses:[[:space:]]+' .github/workflows)

(( status == 0 )) || exit "$status"
echo "GitHub Actions are pinned to immutable commit SHAs."
