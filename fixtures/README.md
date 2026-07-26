# 匿名リリース検証fixture

`v0.2/anonymous-library-v1.json`は実在する個人蔵書を含まない、JSON importと実機往復確認用のfixtureである。

- 全16項目、nullable項目、Unicode、カンマ、改行を含む。
- `=匿名サンプル図書A`はCSV export時のformula injection対策も確認する。
- ISBNは形式検証を通すための公開識別子で、タイトル、著者、置き場所とは関係しない。
- fixtureは読取専用の入力として扱い、実機検証で生成したexportやバックアップをリポジトリへ追加しない。

JVM CIはfixtureのJSON import、共通validation、JSON／CSV再exportとCSV再importを検証する。実機では[リリースチェックリスト](../docs/releases/V0.2_RELEASE_CHECKLIST.md)に従い、Storage Access Framework経由で使用する。
