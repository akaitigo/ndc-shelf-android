# リリースプロセス

署名付きAABの生成・配布・ロールバックの正本。自動化はGitHub Actionsの
`release.yml`、手動ゲートはこの文書の手順に従う。

## バージョン更新の一貫性

リリース候補は次の4点を同一PRで更新してからタグを打つ。

1. `app/build.gradle.kts` の `versionCode`（単調増加、再利用禁止）と `versionName`（`X.Y.Z`）
2. `CHANGELOG.md` の `## [X.Y.Z] - YYYY-MM-DD` 節（`Unreleased` から移動）
3. リリースゲートチェックリスト（`docs/releases/`）の更新
4. `verifyV0*ReleaseConfiguration` タスクの期待値

タグは `vX.Y.Z` 形式で、`versionName` と完全一致させる。`release.yml` の
`verify-release-tag.sh` がタグ・`versionName`・CHANGELOG節の不一致でビルドを
停止する。

## 署名付きAABの生成フロー

1. mainのCIがグリーンであることを確認し、`git tag vX.Y.Z && git push origin vX.Y.Z`
2. GitHub Releaseをタグから作成し、リリースノート（CHANGELOG該当節）を記載して公開する
3. `Release AAB` workflowが起動し、**release environmentの承認**（リポジトリ管理者のレビュー必須）を待つ
4. 承認後、tag整合検証 → フル検証（test/lint/backup/license/SBOM）→ R8有効の `bundleRelease` → 署名確認 → 成果物（AAB・mapping.txt・SBOM・NOTICES・SHA256SUMS）をReleaseへ添付する

未署名ビルドの検証はsecretsなしで `./gradlew :app:bundleRelease` により
いつでも実行できる（署名configは環境変数が無ければ生成されない）。

## 署名鍵の管理

| 項目 | 値 |
| --- | --- |
| 形式 | PKCS12 / RSA 4096 / 有効期限25年 |
| alias | `ndcshelf-upload` |
| 保管（CI） | GitHub `release` environment secrets（`NDC_SHELF_UPLOAD_KEYSTORE_BASE64` ほか3件） |
| 保管（ローカル正本） | 開発機 `~/.local/share/ndc-shelf-release/`（0700、パスワード同梱） |

- **必ずパスワードマネージャ等のオフライン控えへ複製する**こと。ローカル正本と
  GitHub secretsを同時に失うと鍵を復元できない。
- PKCS12では鍵パスワード＝ストアパスワードとなる（別値を設定しても無視される）。
- fork PRおよび`pull_request`イベントへはenvironment secretsが渡らない。
  `release.yml`は`release: published`と`workflow_dispatch`だけで起動し、
  environment承認を必須とする。Actions全体は完全SHA固定
  （`verify-action-pins.sh`）を維持する。

### ローテーション・漏えい対応

1. 新しいkeystoreを生成し、`release` environmentの4 secretsを差し替える
2. Play Console（Play App Signing利用時）で「アップロード鍵のリセット」を申請する。
   アプリ署名鍵はGoogle管理のため、アップロード鍵の漏えいはリセットで回復できる
3. 旧keystoreは失効を記録した上で破棄する。漏えい時はこのリポジトリの
   Private Vulnerability Reportingで経緯を記録する

## 段階配布と停止基準（Play Console手動ゲート）

Play Developer Console利用時は次の順で配布し、各段階で承認を記録する。

1. Internal testing（開発者端末での更新インストール・スモーク）
2. 段階ロールアウト 10% → 50% → 100%（各段階で最低48時間監視）
3. 各段階の判定材料: Play Consoleのクラッシュ率・ANR率、Issue報告

**停止基準**（いずれかで即時ロールアウト停止）:

- クラッシュ率が前版比で有意に悪化（目安: セッションの1%超）
- データ損失・破損の報告が1件でも再現確認された場合
- Migration失敗・起動不能の報告

## ロールバック

Androidは`versionCode`の巻き戻し配布ができないため、ロールバックは
**roll-forward**（修正を含むより大きい`versionCode`の緊急リリース）で行う。

1. ロールアウトを一時停止する
2. 直前の安定版タグから緊急ブランチを作成し、修正またはrevertを適用する
3. `versionCode`を+1し、パッチ版として本プロセスを最初から実行する
4. データを破損した可能性がある場合は、`docs/releases/`のロールバック文書
   （バックアップからの復元手順）を利用者向け告知に含める

各リリースのロールバック演習記録は `docs/releases/V*_ROLLBACK.md` に残す。

## 未完了の手動前提（BLOCKED）

- Google Play Developerアカウントの開設（有料・所有者の判断が必要）
- Play App Signingへの登録とアップロード鍵の紐付け
- 実機でのInternal testing版インストール確認
