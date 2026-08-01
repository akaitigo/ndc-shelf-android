# プライバシー・ライセンス更新手順

ネットワーク通信、権限、バックアップ、依存関係を変更するPull Requestでは、次の確認を必須とします。

## 対象文面

- アプリ内: `app/src/main/res/values/strings.xml` の `info_privacy_*`、`info_source_*`、`info_security_*`
- 公開方針: `PRIVACY.md`
- 概要と出典: `README.md`
- 脆弱性報告経路: `SECURITY.md`
- 配布ページ原稿: `docs/STORE_LISTING.md`
- Androidバックアップ設定: `AndroidManifest.xml`、`res/xml/backup_rules.xml`、`res/xml-v28/backup_rules.xml`、`res/xml/data_extraction_rules.xml`

## レビュー手順

1. リリース成果物のManifest、依存関係、通信先を実装から列挙し、文面だけを根拠にしない。
2. 収集しないデータ、端末内保存、外部へ送る値・契機・通信先、表紙通信、手動ファイル、OS管理バックアップを5つの文面で照合する。
3. NDL Search APIの利用表示と提供元別の利用条件リンクが有効か、公式ページで確認する。
4. `verifyBackupPolicy`を実行し、API別のクラウド除外と端末間転送条件を検査する。
5. `verifyLicenseReport`を実行し、直接・推移依存関係、空のライセンス割当、Apache-2.0・BSD-3-Clause・MIT本文の同梱を検査する。
6. 新規・更新依存のライセンスとNOTICE要件を個別に確認する。自動生成結果だけで法的判断を完結させない。
7. `:app:cyclonedxDirectBom`のSBOMを基準に、各依存の提供元が公開する情報と実際の通信挙動を確認する。
8. リリース公開前に、署名済み成果物・README・リリースノートの説明を再照合する。詳細は[依存関係とサプライチェーン管理](DEPENDENCY_SECURITY.md)を参照する。

## 受け入れ条件

- アプリ内の主要文面とOSSライセンス本文がネットワークなしで読める。
- アプリ内、`PRIVACY.md`、README、SECURITY、配布ページ原稿の通信先・報告先・バックアップ説明が一致する。
- `./gradlew verifyBackupPolicy verifyLicenseReport :app:cyclonedxDirectBom testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`が成功する。
- CIのDependency ReviewとOSV-Scannerが成功し、SBOMとNOTICE artifactを取得できる。
- UI変更を実機またはエミュレーターで確認し、情報画面とライセンス詳細のスクリーンショットをPRへ添付する。
