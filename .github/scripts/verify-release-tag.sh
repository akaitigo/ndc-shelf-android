#!/usr/bin/env bash
# リリースタグ（vX.Y.Z）とGradleのversionName・CHANGELOGの整合を検証する。
# タグとアプリ識別が食い違ったまま署名付きAABを配布しないための入口ゲート。
set -euo pipefail

tag="${1:?usage: verify-release-tag.sh <tag>}"

if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release tag must match vX.Y.Z, got: $tag" >&2
  exit 1
fi

version="${tag#v}"

version_name=$(rg -o 'versionName = "([0-9.]+)"' -r '$1' app/build.gradle.kts | head -1)
if [[ "$version_name" != "$version" ]]; then
  echo "Tag $tag does not match versionName $version_name in app/build.gradle.kts" >&2
  exit 1
fi

if ! rg -q "^## \\[$version\\]" CHANGELOG.md; then
  echo "CHANGELOG.md has no released section '## [$version]' for tag $tag" >&2
  exit 1
fi

echo "Release tag $tag matches versionName and CHANGELOG."
