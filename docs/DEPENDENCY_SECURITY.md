# 依存関係とサプライチェーン管理

## 保護レイヤー

| 対象 | 自動検証 | 証跡 |
| --- | --- | --- |
| Gradle配布物 | `distributionSha256Sum` と setup-gradle のwrapper検証 | Actionsログ |
| Gradle依存物 | dependency verificationのSHA-256照合 | `gradle/verification-metadata.xml` |
| PRの依存差分 | GitHub Dependency Review（moderate以上を拒否） | PR check |
| 既知脆弱性 | OSV-ScannerによるCycloneDX SBOM走査 | SARIFとActions artifact |
| 構成要素 | CycloneDX 1.6 JSON | `release-compliance` artifact |
| OSSライセンス | AboutLibrariesの直接・推移依存レポート検証 | `THIRD-PARTY-NOTICES.json` artifact |

GitHub Actionsはタグではなく完全なcommit SHAへ固定する。Dependabotが更新を提案した場合も、release note、権限、runtime、ライセンスを確認してからSHAを更新する。

## 脆弱性例外

原則として脆弱な依存を更新または削除する。直ちに修正できず、到達不能性を確認できた場合だけ`osv-scanner.toml`へ最長90日の例外を追加する。

```toml
[[IgnoredVulns]]
id = "GHSA-xxxx-xxxx-xxxx"
ignoreUntil = 2026-08-31
reason = "owner=@maintainer; rationale=affected API is not packaged or reachable"
```

`.github/scripts/verify-osv-exceptions.sh`はID、期限、所有者、根拠、重複を検査する。更新日を延長する場合は、到達可能性を再評価してPRへ証跡を残す。重大または悪用可能な未公開情報は公開Issueへ書かず、GitHubのPrivate Vulnerability Reportingで扱う。

## リリースレビュー

1. `./gradlew verifyLicenseReport :app:cyclonedxDirectBom`を実行し、NOTICEとSBOMを生成する。
2. CIのDependency Review、OSV、ライセンス検証が成功していることを確認する。
3. SBOMの直接依存をGoogle Play SDK Indexで照合し、ポリシー・既知問題・提供元のData safety guidanceを確認する。
4. Manifest、SBOM、実装から権限、通信先、送信値、SDKのデータ収集を再調査する。
5. `PRIVACY.md`、アプリ内説明、`docs/STORE_LISTING.md`の回答が実装と一致することを確認する。
6. GitHub Releaseへ自動添付されたSBOMとNOTICEを、署名済み成果物の証跡として保管する。通常のCIでも`release-compliance` artifactを30日保管する。

SDK Indexや自動スキャナーは補助資料であり、ライセンス判断やData safety申告を代替しない。特にML Kit、CameraX、Coil/OkHttpを更新した場合は、提供元の一次資料と実際のネットワーク挙動を再確認する。

## 基準レビュー（2026-07-28）

| 対象 | 確認結果 | Data safety・通信への判断 |
| --- | --- | --- |
| `com.google.mlkit:barcode-scanning:17.3.0` | [公式導入手順](https://developers.google.com/ml-kit/vision/barcode-scanning/android)のbundled最新版と一致。auto-zoomは未使用 | 画像と認識結果は端末内処理。[公式開示](https://developers.google.com/ml-kit/android-data-disclosure)にある端末・アプリ情報、識別子、性能、利用イベント等の診断・分析収集を申告対象として維持 |
| CameraX 1.6.1 | AndroidXのカメラ抽象化。Manifestのカメラ権限以外に独自通信処理なし | カメラ画像は保存・送信しないという既存説明を維持 |
| Coil 3.5.0 / OkHttp 4.12.0 | アプリ実装の通信先をNDL SearchのHTTPS allowlistへ制限 | ISBN検索と表紙取得の既存説明を維持 |
| Room / Compose / AndroidX | 端末内UI・保存用途で、アプリ実装から外部通信を開始しない | 追加申告なし |

[Google Play SDK Index](https://play.google.com/sdks)でMaven IDを検索し、公開されている安全性・ポリシー情報を確認した。Play Consoleだけに表示される提供元メッセージは公開前ゲートで再確認する。今回の依存構成と実装は`PRIVACY.md`、アプリ内説明、`docs/STORE_LISTING.md`と一致しており、利用者向け説明の意味変更は不要だった。

## ローカル検証

```bash
./gradlew verifyLicenseReport :app:cyclonedxDirectBom
.github/scripts/verify-osv-exceptions.sh
```

dependency verificationで新しいartifactが拒否された場合は、出所とチェックサムを確認してから次を実行し、生成差分をレビューする。

```bash
./gradlew --write-verification-metadata sha256 <必要なタスク>
```
