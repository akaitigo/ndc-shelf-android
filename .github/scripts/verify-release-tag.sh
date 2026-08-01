#!/usr/bin/env bash
# リリースタグ（vX.Y.Z）とGradleのversionName・CHANGELOGの整合を検証する。
# タグとアプリ識別が食い違ったまま署名付きAPKを配布しないための入口ゲート。
#
# ripgrepはGitHubのrunner imageに含まれないため、sed/awkだけを使う。
set -euo pipefail

tag="${1:?usage: verify-release-tag.sh <tag>}"

if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release tag must match vX.Y.Z, got: $tag" >&2
  exit 1
fi

version="${tag#v}"

version_name=$(sed -n 's/.*versionName = "\([0-9.]\{1,\}\)".*/\1/p' app/build.gradle.kts | head -1)
if [[ -z "$version_name" ]]; then
  echo "Could not read versionName from app/build.gradle.kts" >&2
  exit 1
fi
if [[ "$version_name" != "$version" ]]; then
  echo "Tag $tag does not match versionName $version_name in app/build.gradle.kts" >&2
  exit 1
fi

# 行頭が "## [X.Y.Z]" で始まるリリース節を探す（リンク定義行と取り違えない）。
if ! awk -v heading="## [$version]" 'index($0, heading) == 1 { found = 1 } END { exit found ? 0 : 1 }' CHANGELOG.md; then
  echo "CHANGELOG.md has no released section '## [$version]' for tag $tag" >&2
  exit 1
fi

echo "Release tag $tag matches versionName and CHANGELOG."
