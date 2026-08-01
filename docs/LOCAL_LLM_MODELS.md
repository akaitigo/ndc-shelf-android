# 端末内LLMのモデル台帳

端末内LLM AI司書が読み込めるモデルの唯一の定義元は、コード上の
`dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog`（allowlist）である。
本書は台帳の運用手順・記載項目・停止手順を定める。設計判断は
`docs/adr/0009-on-device-llm-librarian.md`、プライバシー境界は
`PRIVACY.md` と `docs/NETWORK_BOUNDARY.md` を参照する。

## 現在の登録状況

| モデルID | version | runtime | ライセンス | 状態 | 廃止日 |
| --- | --- | --- | --- | --- | --- |
| （なし） | — | — | — | — | — |

**台帳は空である。** ADR 0009のとおり推論runtimeが未採用のため、
`LlmModelCatalog.models` は空リストで、AI司書は規則ベースの
`OnDeviceHeuristicLibrarian` の回答だけを返す（fail-closed）。

## 台帳へ登録できる条件

すべて満たさない限り登録しない。1つでも欠ければ登録を見送る。

1. **ライセンス**: OSI承認ライセンス（Apache-2.0 / MIT / BSD）で、利用者への再配布条件、
   利用制限の下流継承義務、遠隔での利用停止条項が無いこと。一次情報のURLと確認日を記録する。
2. **取得可否**: 認証なしで取得できること（gatedモデルはアプリからトークンを配らないため不可）。
   取得URLのhostが `LlmModelUrlPolicy.ALLOWED_HOSTS` に含まれ、**redirectを返さない**こと。
   現在の実装はredirectを一切追わないため、Hugging Faceの `/resolve/` のようにCDNへ302する
   URLは登録できない。詳細と対応案はADR 0009「未解決の技術課題」を参照する。
3. **完全性**: ファイルサイズとSHA-256を実測し、台帳へ記録すること。
4. **端末条件**: 必要なAPI level・ABI・物理RAM・空き容量を実機で確定し、台帳へ記録すること。
5. **日本語品質**: 「次に読む本」「テーマ別整理」「蔵書概観」の3ユースケースを実機で評価し、
   結果をリリースチェックリストへ記録すること（ベンダー公表のベンチマークだけでは足りない）。
6. **配布サイズ**: runtime追加後のrelease AABが `docs/PERFORMANCE_BUDGETS.md` の予算内であること。

## 記載項目（`LlmModelDefinition`）

| フィールド | 内容 | 検証 |
| --- | --- | --- |
| `id` | 台帳内で一意なID | 診断ログへ記録する固定値 |
| `version` | モデルversion | 互換性判定と診断ログ |
| `displayName` | 画面表示名 | — |
| `runtime` | 対応する推論runtime | `LlmRuntimeId` |
| `downloadUrl` | 取得URL | 構築時に `LlmModelUrlPolicy` で検査（HTTPS・許可host・port 443・userInfo無し・fragment無し・`..`無し） |
| `fileName` | 端末内のファイル名 | — |
| `sizeBytes` | 期待ファイルサイズ | 構築時に1〜3 GiBを強制。ダウンロードの上限にもなる |
| `sha256` | 期待SHA-256（小文字hex 64桁） | 構築時に形式検査、導入時に全バイトを照合 |
| `licenseSpdxId` / `licenseUrl` | ライセンス | OSSライセンス表示と突き合わせる |
| `sourceUrl` / `verifiedOn` | 一次情報URLと確認日 | 監査証跡 |
| `minSdkInt` | 必要なAPI level | `LlmCapabilityChecker` |
| `requiredAbis` | 必要なABI | 同上 |
| `minTotalRamBytes` | 必要な物理RAM | 同上 |
| `requiredFreeBytes` | 必要な空き容量 | 構築時に `sizeBytes` 以上を強制 |
| `contextTokens` | context長 | prompt上限の根拠 |
| `addedOn` | 台帳へ追加した日 | — |
| `retiredOn` | 廃止日。設定すると既定モデルから外れる | `LlmModelCatalog.defaultModel` |
| `knownLimitations` | 既知の制約 | ADRとUIで同じ文言を使う |

## 追加手順

1. 上記「登録できる条件」を全項目確認し、一次情報URLと確認日を控える。
2. モデルを取得し、`sha256sum` と `stat -c%s` で checksum とサイズを実測する。
3. `LlmModelCatalog.models` へ `LlmModelDefinition` を追加する。
4. 本書の「現在の登録状況」表へ1行追加する。
5. `docs/PERFORMANCE_BUDGETS.md` の実行時予算枠を実機測定値で埋める。
6. `PRIVACY.md` のモデル取得通信の記述（送信先host・送信する値）を更新する。
7. リリースチェックリストのLLM節を更新する。
8. `./gradlew verifyLicenseReport :app:cyclonedxDirectBom :app:bundleRelease :app:verifyReleaseBundleSize` を通す。

## 更新（version上げ）手順

- 新versionは既存versionと**別の**`LlmModelDefinition`として追加する。
- `FileLlmModelStore` は新versionの検証成功後にrenameで有効化し、その時点で旧versionを削除する。
  検証に失敗した場合は一時ファイルだけを消し、旧versionをそのまま残す（中途半端なモデルは有効化されない）。
- 旧versionを新規取得させたくない場合は `retiredOn` を設定する。すでに導入済みの端末では
  該当定義を台帳から削除するまでロードできる。強制的に無効化したい場合は台帳から定義ごと削除する。

## 停止・rollback手順

| 事象 | 対応 |
| --- | --- |
| 配布元が停止した / URLが404になった | 台帳から該当versionを削除（または `retiredOn` を設定）してアプリを更新する。導入済み端末はそのまま動作する |
| ライセンスが変更された | 台帳から定義を削除してアプリを更新する。UIに削除導線を案内する |
| モデル由来の脆弱性・重大な不具合 | 台帳から定義を削除。`LlmModelCatalog.models` が空になればLLM経路全体が起動しない |
| runtimeの不具合 | `AppContainer` の `LlmInferenceRuntime` を `UnavailableLlmRuntime` へ戻す。以後は必ずヒューリスティックへ縮退する |
| 端末側の不具合（OOM・発熱） | `LlmModelDefinition` の `minTotalRamBytes` / `minSdkInt` / `requiredAbis` を引き上げて対象端末を狭める |

いずれの場合も、AI司書そのものは `OnDeviceHeuristicLibrarian` で動作を続け、
本棚・検索・スキャン・分析には影響しない。

## 利用者による削除

- アプリ内の「モデルを削除」で `LlmModelStore.delete()` を実行する。
- 全削除は `LlmModelStore.deleteAll()`。`noBackupFilesDir/llm-models` 配下を再帰削除するため、
  一時ファイル（`.staging/*.part`）とメタデータ（`model.meta`）も残らない。
- 配置先は `noBackupFilesDir` 配下で、OSクラウドbackup・端末間移行（D2D）・
  エクスポート・同期の対象外（`docs/BACKUP_THREAT_MODEL.md` と同じ扱い）。
