# リリースプロセス

署名付きAPKの生成・配布・ロールバックの正本。自動化はGitHub Actionsの
`release.yml`、手動ゲートはこの文書の手順に従う。

## 配布方針

NDC Shelfは**無料のオープンソースアプリとして、GitHub Releasesで署名付きAPKを
配布する**。アプリストアへは公開しない。

この方針の帰結:

- 利用者は提供元不明アプリのインストールを許可してAPKをサイドロードする。手順は
  READMEに記載する。
- 自動更新は行われない。更新の告知はGitHub Releases（およびWatch通知）に依存する。
- 段階ロールアウト・ストアの審査・Data safety申告は存在しない。代わりに
  **pre-release → stable** の2段階と、SHA-256による配布物の検証を用いる。
- 署名鍵はアプリ本体の署名鍵そのものであり、Play App Signingのような再発行手段が
  ない。鍵を失うと、既存利用者は上書き更新できなくなる（アンインストールと再
  インストールが必要になり、端末内データを失う）。**鍵の保全が最重要事項**である。

## バージョン更新の一貫性

リリース候補は次の4点を同一PRで更新してからタグを打つ。

1. `app/build.gradle.kts` の `versionCode`（単調増加、再利用禁止）と `versionName`（`X.Y.Z`）
2. `CHANGELOG.md` の `## [X.Y.Z] - YYYY-MM-DD` 節（`Unreleased` から移動）
3. リリースゲートチェックリスト（`docs/releases/`）の更新
4. `verifyV0*ReleaseConfiguration` タスクの期待値

タグは `vX.Y.Z` 形式で、`versionName` と完全一致させる。`release.yml` の
`verify-release-tag.sh` がタグ・`versionName`・CHANGELOG節の不一致でビルドを
停止する。

## 署名付きAPKの生成フロー

1. mainのCIがグリーンであることを確認し、`git tag vX.Y.Z && git push origin vX.Y.Z`
2. GitHub Releaseをタグから作成する。最初は **pre-release** として公開し、
   リリースノート（CHANGELOG該当節）と既知の制約を記載する
3. `Release APK` workflowが起動し、**release environmentの承認**（リポジトリ管理者の
   レビュー必須）を待つ
4. 承認後、tag整合検証 → フル検証（test/lint/backup/license/SBOM）→ R8有効の
   `assembleRelease` → `apksigner`による署名検証 → 成果物をReleaseへ添付する

添付される成果物:

v0.6.0以降、配布物はフレーバーごとに2つある（`docs/adr/0009-on-device-llm-librarian.md`）。
`-ai` が付く方が端末内LLMを含むAI版で、`applicationId` が異なる別アプリとして扱われる。

| ファイル | 用途 |
| --- | --- |
| `ndc-shelf-vX.Y.Z.apk` | **配布物（通常版）**。端末内LLMを含まない。Android 6.0以上 |
| `ndc-shelf-vX.Y.Z-ai.apk` | **配布物（AI版）**。端末内LLMを含む。Android 7.0以上・arm64-v8a専用 |
| `SHA256SUMS.txt` | 全配布物の完全性検証 |
| `apk-signature-vX.Y.Z.txt` | 通常版の署名者証明書のSHA-256。配布元の同一性検証 |
| `apk-signature-vX.Y.Z-ai.txt` | AI版の署名者証明書のSHA-256。通常版と一致することを`release.yml`が判定する |
| `mapping-vX.Y.Z.txt` | 通常版のR8マッピング。クラッシュ報告の解析用 |
| `mapping-vX.Y.Z-ai.txt` | AI版のR8マッピング |
| `ndc-shelf.cdx.json` | CycloneDX SBOM（両フレーバーの実行時依存を含む） |
| `THIRD-PARTY-NOTICES.json` | OSSライセンス表示 |
| `ndc-shelf-vX.Y.Z.aab` / `ndc-shelf-vX.Y.Z-ai.aab` | 予備。将来ストア配布へ切り替える場合に使う |

両APKは**同じ署名鍵**で署名する。`release.yml` は両方の証明書SHA-256が一致することを
検証し、片方だけ別鍵で署名された配布物を出さない。

未署名ビルドの検証はsecretsなしで `./gradlew :app:assembleRelease` により
いつでも実行できる（署名configは環境変数が無ければ生成されない）。

## 利用者向けの検証手順（リリースノートへ記載する）

```bash
# 完全性の検証
sha256sum -c SHA256SUMS.txt

# 配布元の同一性の検証（証明書のSHA-256が全リリースで一致することを確認する）
apksigner verify --print-certs ndc-shelf-vX.Y.Z.apk
```

初回リリースの証明書SHA-256をREADMEへ固定掲載し、以後のリリースで変化しないことを
利用者が確認できるようにする。

## 署名鍵の管理

| 項目 | 値 |
| --- | --- |
| 形式 | PKCS12 / RSA 4096 / 有効期限25年 |
| alias | `ndcshelf-upload` |
| 保管（CI） | GitHub `release` environment secrets（`NDC_SHELF_UPLOAD_KEYSTORE_BASE64` ほか3件） |
| 保管（ローカル正本） | 開発機 `~/.local/share/ndc-shelf-release/`（0700、パスワード同梱） |

- **必ずパスワードマネージャ等のオフライン控えへ複製する**こと。ストア配布と異なり
  再発行手段がないため、ローカル正本とGitHub secretsを同時に失うと、既存利用者への
  上書き更新を永久に提供できなくなる。
- PKCS12では鍵パスワード＝ストアパスワードとなる（別値を設定しても無視される）。
- fork PRおよび`pull_request`イベントへはenvironment secretsが渡らない。
  `release.yml`は`release: published`と`workflow_dispatch`だけで起動し、
  environment承認を必須とする。Actions全体は完全SHA固定
  （`verify-action-pins.sh`）を維持する。

### ローテーション・漏えい対応

鍵の変更は既存利用者の上書き更新を壊すため、**漏えい時以外は行わない**。

漏えいが判明した場合:

1. 影響範囲を`SECURITY.md`のPrivate Vulnerability Reportingで記録する
2. 新しいkeystoreを生成し、`release` environmentの4 secretsを差し替える
3. 新しい署名鍵で公開することと、**利用者は一度アンインストールしてから新版を
   インストールする必要があること**、その前に必ずアプリ内の完全バックアップを
   取得することを、リリースノートとREADMEの冒頭で告知する
4. 旧鍵で署名した過去リリースに、漏えいの事実と検証手順を追記する

## 配布と停止基準

段階ロールアウトの代わりに、GitHub Releasesの2段階を用いる。

1. **pre-release**: 開発者の実機で更新インストール・スモークを完了するまで。
   Latestとして表示されないため、一般利用者へ推奨されない
2. **stable**: 実機ゲートを通過し、リリースノートと既知の制約が揃った時点で
   pre-releaseを解除する

判定材料はGitHub Issuesの不具合報告と、利用者が任意で共有する診断ファイル
（`docs/`のDiagnostics参照。自動収集は行わない）。

**停止基準**（いずれかでstable化を中止し、pre-releaseへ戻すか公開を取り下げる）:

- データ損失・破損の報告が1件でも再現確認された場合
- Migration失敗・起動不能の報告
- 既存利用者が上書き更新できない（署名不一致・`versionCode`の誤り）

## ロールバック

Androidは`versionCode`の巻き戻し配布ができないため、ロールバックは
**roll-forward**（修正を含むより大きい`versionCode`の緊急リリース）で行う。

1. 問題のあるReleaseをpre-releaseへ戻し、リリースノートの冒頭へ警告を追記する
   （GitHub Releasesは既にダウンロードされたAPKを回収できないため、告知が唯一の手段）
2. 直前の安定版タグから緊急ブランチを作成し、修正またはrevertを適用する
3. `versionCode`を+1し、パッチ版として本プロセスを最初から実行する
4. データを破損した可能性がある場合は、`docs/releases/`のロールバック文書
   （バックアップからの復元手順）を利用者向け告知に含める

各リリースのロールバック演習記録は `docs/releases/V*_ROLLBACK.md` に残す。

## 残る手動前提

- 実機での更新インストール・スモーク（`docs/DEVICE_TEST_MATRIX.md`の手動実機層）
- 初回リリース時に、署名証明書のSHA-256をREADMEへ固定掲載する

Google Play Developerアカウントは不要である（ストア配布を行わないため）。
