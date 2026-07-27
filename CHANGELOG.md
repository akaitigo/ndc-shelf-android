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
- 段内の本を局所更新で並べ替え、詳細画面で左右の本と戻す位置を確認する機能。
- 同じ版を複数冊登録し、表示名・置き場所・読書状態・取得日時をコピー単位で管理する機能。
- 書店モードで所有冊数、欲しい、予約済みを端末内保存し、購入済みへの変更時に本棚へ追加する機能。
- 連続スキャンの試行履歴、再起動後のセッション回復、編集済みコピーを保護する個別・一括取り消し。
- 暗所・小型バーコード向けのトーチ、ピンチズーム、タップフォーカス、成功表示と権限案内。
- 複数シリーズ所属、巻ラベル、外伝・合本、安定表示順を保持するRoom v8基盤。
- シリーズごとの所有巻・既知巻・読了巻・最新所有巻と、確認済み本編だけを対象にした欠巻候補の一覧・詳細表示。
- 日本語巻表記・数字・ローマ数字・上下巻・外伝のシリーズ候補を、確認後に新規または既存シリーズへ一括登録する画面。
- 文庫・新装版・電子版を自動マージせず、書誌差分を確認して可逆に関連付ける作品グループとシリーズ代替設定。
- シリーズ名の送信を明示許可した場合だけ、NDL Searchで週1回まで新刊書誌候補を確認する機能。

### Changed

- Androidクラウドbackupから全アプリデータを除外し、Android 9以降の端末間転送だけRoom DBを対象化。
- NDL Search通信の失敗分類、上限付きretry、明示的な再試行UIを追加。
- 表紙取得をNDLのHTTPS thumbnailへ限定し、最大50MiBの端末cacheを設定。
- v0.3.0 release candidateとしてversionCodeを5へ更新し、匿名E2E release gateを追加。

### Fixed

- 階層化した棚へ配置済みのcopyで、編集直後の取り消しが構造化path差分により失敗する問題を修正。
- CSV再import時に、数式記号の直前にある元データのアポストロフィを欠落させず復元。
- 完全restore失敗時も検証済み自動退避backupを最大3世代に制限。
- 本編集シートの文字入力中に全蔵書の段内filter・sortを繰り返さないよう最適化。
- CSV importは列数不一致でも表示上限100件に達した時点で解析を終了。
- 完全backupのmanifest形式版とpayload schema版が一致しないファイルを復元前に拒否。
- 完全backup形式v11とRoom v10で作品グループ・所属・シリーズ代替設定を退避・復元。
- 完全backup形式v12とRoom v11でシリーズ監視設定・書誌候補・通知状態を退避・復元。

### Security

- importの入力上限、schema検証、CSV formula injection対策、URL allowlist、transaction rollbackを追加。
- backup復元でZIP構造、SHA-256、DB版、件数、参照整合性、空き容量を事前検証。

### Known limitations

- シリーズ候補とNDL新刊書誌は確定事実ではなく、ユーザー確認前にシリーズ所属へ保存しない。
- 欠巻は確認済みの明示的な本編だけを対象とし、未登録番号や外部の最新刊を推測しない。
- 新刊候補確認は明示的opt-inかつ最大週1回で、発売日・網羅性・在庫を保証しない。
- シリーズ統合と複数端末同期はv0.4の対象外。

## [0.1.2] - 2026-07-25

### Added

- ISBN scan、NDL Search書誌取得、端末内Room本棚、検索、NDC分類表示の初回公開版。

[Unreleased]: https://github.com/akaitigo/ndc-shelf-android/compare/d852975025bf5d224e29f5b9fc475cf8c0bff957...HEAD
[0.1.2]: https://github.com/akaitigo/ndc-shelf-android/commit/d852975025bf5d224e29f5b9fc475cf8c0bff957
