# Changelog

このプロジェクトの利用者向け変更を記録する。形式は[Keep a Changelog](https://keepachangelog.com/ja/1.1.0/)を参考にし、リリース前の変更は`Unreleased`へ追加する。

## [Unreleased]

### Added

- JSON／CSVによる蔵書のexport、検証付きimport、競合preview。
- Room全テーブルのchecksum付き完全backupと、事前検証・自動退避を伴う原子的restore。
- 書誌情報、NDC分類、読書状態、置き場所の編集と、所有copy単位の削除・取り消し。
- データ管理画面、アプリ内プライバシー説明、OSSライセンス一覧、データ出典。
- Room migration／Repository統合テストと匿名リリースfixture。
- 部屋・本棚・段の追加、改名、並べ替え、削除と、所有copyの階層的な置き場所割り当て。

### Changed

- Androidクラウドbackupから全アプリデータを除外し、Android 9以降の端末間転送だけRoom DBを対象化。
- NDL Search通信の失敗分類、上限付きretry、明示的な再試行UIを追加。
- 表紙取得をNDLのHTTPS thumbnailへ限定し、最大50MiBの端末cacheを設定。

### Security

- importの入力上限、schema検証、CSV formula injection対策、URL allowlist、transaction rollbackを追加。
- backup復元でZIP構造、SHA-256、DB版、件数、参照整合性、空き容量を事前検証。

## [0.1.2] - 2026-07-25

### Added

- ISBN scan、NDL Search書誌取得、端末内Room本棚、検索、NDC分類表示の初回公開版。

[Unreleased]: https://github.com/akaitigo/ndc-shelf-android/compare/d852975025bf5d224e29f5b9fc475cf8c0bff957...HEAD
[0.1.2]: https://github.com/akaitigo/ndc-shelf-android/commit/d852975025bf5d224e29f5b9fc475cf8c0bff957
