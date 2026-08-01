# Contributing

NDC Shelfへの貢献をありがとうございます。

## 開発フロー

1. 変更内容に対応するIssueを確認または作成します。
2. 小さく焦点を絞ったブランチを作ります。
3. 実装とテストを追加します。
4. `./gradlew testStandardDebugUnitTest lintStandardDebug` を実行します。
5. 目的、変更点、確認方法をPull Requestに記載します。

mainへの直接pushは禁止です。Draft PRで作業し、base branchの最新化、必須CI、未解決conversationがないことを確認してからReady for reviewへ変更します。mergeと緊急時の手順は[Repository governance](docs/REPOSITORY_GOVERNANCE.md)を参照してください。

UI変更では、可能ならBefore / Afterのスクリーンショットを添付してください。データモデルや外部APIを変更する場合は、互換性とプライバシーへの影響も説明してください。

## コーディング方針

- UIより先にドメイン用語を明確にします。
- ネットワーク障害があっても既存の蔵書を閲覧できるようにします。
- 外部サービスへの送信データを最小化します。
- 新しい依存関係には、目的とライセンスを明記します。
- Roomのスキーマ変更にはMigrationとテストを追加します。

## 依存関係の更新

Dependabot PRも1件ずつrelease note、breaking change、license、互換性を確認し、通常のtest・lint・buildを通します。Android plugin、runtime、UI、databaseへ影響する更新ではinstrumentation testも実行します。CI成功だけを根拠に自動mergeせず、問題時はmainのmerge commitをrevertします。検証手順、SBOM、脆弱性例外は[依存関係とサプライチェーン管理](docs/DEPENDENCY_SECURITY.md)に従います。

## Commit

厳密な形式は強制しません。先頭を動詞にし、何を変えたかが分かる短いメッセージを推奨します。
