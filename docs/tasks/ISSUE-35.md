# Implementation Contract: Issue #35

## Objective

既存蔵書を失わず、候補と確定事実を混同しないv0.4シリーズ管理を、自動検証と再現可能な実環境手順を備えたrelease candidateとして確定する。

## Current State

- branch: `agent/issue-35-v04-release`
- base: `agent/issue-34-series-release-watch`
- base HEAD: `8365947`
- upstream: 未設定
- related Issue: #35
- dependency: v0.4 milestoneのP0 Issue #30、#32、#33、#34の実装branch
- dirty files: CI、version設定、Migration回帰テスト、v0.4匿名release fixture test
- ownership: 上記の未コミット変更はこのIssueの既存作業として保持する

## Decision

- v0.4 release candidateは`versionCode = 6`、`versionName = "0.4.0"`とする。
- v0.3相当のRoom v7から現在のRoom v11まで、既存Work、Edition、Copy、場所を保持するMigrationを検証する。
- Migration中にタイトルなどからシリーズ、作品グループ、新刊候補を推測生成しない。
- 整数巻、上下巻、外伝、合本、文庫、新装版は匿名fixtureで横断検証する。
- シリーズ所属と作品グループは解除後もWork、Edition、Copyを変更せず、再設定可能とする。
- 新刊確認は明示的opt-in時だけ通信し、初回はbaseline、同一候補は再通知せず、offline時は保存済み状態を維持する。
- Android OS上の自動化可能なテストはemulatorで実行する。通知権限、OS再起動、時計変更、実ネットワークの体感確認は実機手順へ分離する。
- 配布後の問題はdowngradeせず、より大きい`versionCode`のhotfixでroll forwardする。

## Invariants

- Migration、関連解除、失敗した新刊確認によって既存の蔵書事実を削除または上書きしない。
- 推測候補を確定済みシリーズ所属として保存しない。
- 通知OFFではシリーズ名を外部送信せず、background workも登録しない。
- 同一source recordを重複保存・重複通知しない。
- NDL障害やoffline時も保存済みシリーズを閲覧可能に保つ。
- release version、Room schema、backup formatの互換性を文書化し、rollbackでデータを巻き戻さない。

## Scope

- v0.4 version identityとCI verification
- Room v7→v11 Migration回帰テスト
- v0.4シリーズ機能の匿名横断fixture test
- v0.4 release checklist、既知制約、rollback手順
- README、ROADMAP、CHANGELOGのrelease参照整合
- emulatorで実行可能なAndroid instrumentation testの検証

## Non-goals

- Play Consoleへの公開、署名、本番tag作成
- 実機hardware固有の最終承認
- シリーズ統合、cloud同期、AI司書
- v0.3以前の未完了release Issueの完了扱い
- v0.4機能のscope拡張や新規依存追加

## Required Tests

- `V04SeriesReleaseTest`: 匿名fixture、欠巻の保守性、所属解除・再設定、版関連の可逆性、watchのopt-in・baseline・重複抑止・offline縮退
- `AppDatabaseMigrationTest`: Room v7→v11の蔵書・場所保持、シリーズ関連tableの空初期化、foreign key整合
- 全debug unit test、lint、debug assemble、androidTest Kotlin compile
- 利用可能なAVDで`connectedDebugAndroidTest`
- 実機のみの通知権限、再起動、時計変更、offline復帰はrelease checklistに手順と証跡欄を残す

## Commands

```bash
./gradlew \
  verifyV04ReleaseConfiguration \
  verifyBackupPolicy \
  verifyLicenseReport \
  testDebugUnitTest \
  lintDebug \
  assembleDebug \
  compileDebugAndroidTestKotlin
./gradlew connectedDebugAndroidTest
git diff --check
git diff --exit-code -- app/schemas
```

## Acceptance Criteria

- [ ] Room v7の匿名蔵書fixtureがv11へ欠落なくMigrationされる。
- [ ] Migrationはseries、work group、watch、release candidateを推測生成しない。
- [ ] 整数巻、上下巻、外伝、合本、文庫、新装版を匿名fixtureで検証する。
- [ ] 候補確定・解除・版関連付けが可逆である。
- [ ] 欠巻候補は確認済み本編だけから算出し、不明データを断定しない。
- [ ] 通知OFFで通信せず、ON時の初回baseline、頻度、重複、offline縮退を検証する。
- [ ] 正規Gradle検証とAndroid OS上のinstrumentation testが成功する。
- [ ] v0.4 release checklist、既知制約、rollbackを更新する。
- [ ] 実機未実施項目は理由、手順、必要証跡、残存リスクを明記する。
- [ ] Draft PRを作成し、GitHub Actions成功を確認する。

## Stop Conditions

- v0.4 milestoneの依存実装またはschemaが変更される。
- 既存の未コミット変更と所有権が競合する。
- 新規依存、権限、外部送信先、DB schema、backup formatの追加変更が必要になる。
- テストの削除、skip、弱体化なしでは検証を通せない。
- v0.3 release artifactが存在する前提でなければ成立しない検証が見つかる。
- 受け入れ条件同士または既存privacy方針と矛盾する。
