#!/usr/bin/env bash
# 既定ロケール（values/ = 英語）と日本語（values-ja/）の翻訳キー集合が一致することを検証する。
#
# - 片方にしかないキー（未翻訳・不要翻訳）を検出して失敗する
# - <string> と <plurals> の両方を対象にする
# - translatable="false" のキーは values-ja/ に存在してはならない（lint の ExtraTranslation と同じ規約）
# - 英語の <plurals> には one / other、日本語には other が必須
# - フォーマット引数（%1$s など）の集合が両言語で一致することも検証する
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
default_file="${repo_root}/app/src/main/res/values/strings.xml"
japanese_file="${repo_root}/app/src/main/res/values-ja/strings.xml"

for file in "${default_file}" "${japanese_file}"; do
  if [[ ! -f "${file}" ]]; then
    echo "verify-translations: missing resource file: ${file}" >&2
    exit 1
  fi
done

python3 - "${default_file}" "${japanese_file}" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

default_path, japanese_path = sys.argv[1], sys.argv[2]
FORMAT_ARG = re.compile(r"%(\d+\$)?[sdfx]")


def load(path):
    root = ET.parse(path).getroot()
    translatable = {}
    untranslatable = set()
    formats = {}
    for element in root:
        if element.tag not in ("string", "plurals"):
            continue
        name = element.get("name")
        if name is None:
            continue
        key = f"{element.tag}/{name}"
        if element.get("translatable") == "false":
            untranslatable.add(key)
            continue
        translatable[key] = element
        if element.tag == "string":
            text = "".join(element.itertext())
            formats[key] = frozenset(FORMAT_ARG.findall(text))
        else:
            args = set()
            for item in element:
                args |= set(FORMAT_ARG.findall("".join(item.itertext())))
            formats[key] = frozenset(args)
    return translatable, untranslatable, formats


default_keys, default_untranslatable, default_formats = load(default_path)
japanese_keys, japanese_untranslatable, japanese_formats = load(japanese_path)

problems = []

missing = sorted(set(default_keys) - set(japanese_keys))
if missing:
    problems.append(
        "values-ja/strings.xml is missing translations for:\n  "
        + "\n  ".join(missing)
    )

extra = sorted(set(japanese_keys) - set(default_keys))
if extra:
    problems.append(
        "values-ja/strings.xml has keys that do not exist in values/strings.xml:\n  "
        + "\n  ".join(extra)
    )

marked_but_translated = sorted(default_untranslatable & (set(japanese_keys) | japanese_untranslatable))
if marked_but_translated:
    problems.append(
        'keys marked translatable="false" must not appear in values-ja/strings.xml:\n  '
        + "\n  ".join(marked_but_translated)
    )

untranslatable_only_in_ja = sorted(japanese_untranslatable - default_untranslatable)
if untranslatable_only_in_ja:
    problems.append(
        'values-ja/strings.xml declares translatable="false" for keys that are translatable in '
        "values/strings.xml:\n  " + "\n  ".join(untranslatable_only_in_ja)
    )

for key in sorted(set(default_keys) & set(japanese_keys)):
    if default_formats[key] != japanese_formats[key]:
        problems.append(
            f"{key}: format arguments differ "
            f"(values={sorted(default_formats[key])}, values-ja={sorted(japanese_formats[key])})"
        )

for key, element in sorted(default_keys.items()):
    if element.tag != "plurals":
        continue
    quantities = {item.get("quantity") for item in element}
    for required in ("one", "other"):
        if required not in quantities:
            problems.append(f"{key}: values/strings.xml is missing quantity=\"{required}\"")

for key, element in sorted(japanese_keys.items()):
    if element.tag != "plurals":
        continue
    quantities = {item.get("quantity") for item in element}
    if "other" not in quantities:
        problems.append(f"{key}: values-ja/strings.xml is missing quantity=\"other\"")
    unexpected = quantities - {"other"}
    if unexpected:
        problems.append(
            f"{key}: values-ja/strings.xml declares unused quantities {sorted(unexpected)}; "
            "Japanese only uses \"other\""
        )

if problems:
    print("verify-translations: found translation problems\n", file=sys.stderr)
    for problem in problems:
        print(f"- {problem}\n", file=sys.stderr)
    sys.exit(1)

print(
    "verify-translations: OK "
    f"({len(default_keys)} translatable keys, {len(default_untranslatable)} untranslatable)"
)
PY
