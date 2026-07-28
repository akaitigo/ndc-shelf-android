#!/usr/bin/env bash
set -euo pipefail

config="${1:-osv-scanner.toml}"
today="$(date -u +%F)"
max_days=90

[[ -f "$config" ]] || { echo "Missing OSV configuration: $config" >&2; exit 1; }

awk '
  function flush() {
    if (active) print id "\t" until "\t" reason
    id = until = reason = ""
  }
  /^\[\[IgnoredVulns\]\]$/ { flush(); active = 1; next }
  active && /^id[[:space:]]*=/ {
    value = $0; sub(/^[^=]*=[[:space:]]*/, "", value); gsub(/^"|"$/, "", value); id = value
  }
  active && /^ignoreUntil[[:space:]]*=/ {
    value = $0; sub(/^[^=]*=[[:space:]]*/, "", value); gsub(/^"|"$/, "", value); until = value
  }
  active && /^reason[[:space:]]*=/ {
    value = $0; sub(/^[^=]*=[[:space:]]*/, "", value); gsub(/^"|"$/, "", value); reason = value
  }
  END { flush() }
' "$config" | while IFS=$'\t' read -r id until reason; do
  [[ "$id" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Invalid or missing vulnerability ID" >&2; exit 1; }
  [[ "$until" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "$id: ignoreUntil is required (YYYY-MM-DD)" >&2; exit 1; }
  [[ "$reason" =~ ^owner=@[A-Za-z0-9_-]+\;[[:space:]]rationale=.+ ]] || {
    echo "$id: reason must contain owner=@github-handle; rationale=..." >&2
    exit 1
  }

  expiry_epoch="$(date -u -d "$until" +%s)" || { echo "$id: invalid ignoreUntil" >&2; exit 1; }
  today_epoch="$(date -u -d "$today" +%s)"
  max_epoch="$(date -u -d "$today + $max_days days" +%s)"
  (( expiry_epoch >= today_epoch )) || { echo "$id: exception expired on $until" >&2; exit 1; }
  (( expiry_epoch <= max_epoch )) || { echo "$id: exception exceeds $max_days days" >&2; exit 1; }

  if [[ " ${seen_ids:-} " == *" $id "* ]]; then
    echo "$id: duplicate exception" >&2
    exit 1
  fi
  seen_ids="${seen_ids:-} $id"
done

echo "OSV vulnerability exceptions are valid."
