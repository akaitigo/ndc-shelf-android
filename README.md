# NDC Shelf

スマートフォンで本のISBNバーコードを連続スキャンし、自分の蔵書を日本十進分類法（NDC）で整理する、Android向けの個人図書館アプリです。

> 現在の安定版は `v0.1.2` です。Pixel 7（Android 16）で、起動および「ISBNスキャン → 書誌情報取得 → 本棚登録」の一連の動作を確認しています。

## できること

- ISBN（EAN-13）をカメラで連続スキャン
- トーチ、ピンチズーム、タップフォーカス、成功フィードバック付きカメラスキャン
- ISBN-10 / ISBN-13のチェックデジット検証
- 国立国会図書館サーチからタイトル、著者、出版社、出版年、NDCを取得
- Roomによる端末内の蔵書保存
- 登録済みISBNの重複警告
- タイトル、著者、ISBN、NDC、置き場所の横断検索
- 部屋・本棚・段の階層による置き場所管理と読書状態の編集
- 段内の物理的な並び順と左右の隣接本による戻す位置の表示
- NDCの類別ごとの蔵書分布表示
- JSON / CSVによる蔵書データのエクスポート
- JSON / CSVからの検証・プレビュー付きインポート
- 書誌情報、NDC分類、棚位置、読書状態の手動補正と直後の取り消し
- 所有コピー単位の確認付き削除と直後の取り消し
- 同じ版の複数冊登録と、コピーごとの表示名・置き場所・読書状態管理
- 書店モードで所有冊数・欲しい・予約済みを確認し、購入時に本棚へ変換
- 連続スキャンのセッション件数・試行履歴と、安全な個別／一括取り消し
- チェックサム・事前退避・原子的復元を備えたデータベース完全バックアップ
- NDLで見つからない本のオフライン手動登録と、差分確認付きの後日照合
- 用途と影響を確認して操作できる専用データ管理画面
- プライバシー、NDL出典、アプリ情報、OSSライセンスのオフライン表示
- ライトテーマ、ダークテーマ、Material You

## スクリーン

| 本棚 | スキャン | 分類 | データ | 情報 |
| --- | --- | --- | --- | --- |
| 蔵書の検索と編集 | バーコード連続登録 | NDC別の蔵書マップ | 移行・バックアップ・復元 | プライバシー・出典・ライセンス |

スクリーンショットは最初の実機ビルド後に追加予定です。

## 技術スタック

- Kotlin 2.4 / Java 17
- Jetpack Compose + Material 3
- Room
- CameraX
- ML Kit Barcode Scanning（端末内モデル）
- Coil
- Coroutines / Flow
- Gradle Version Catalog
- Room Testing + Robolectric（DB／Repositoryの必須JVM統合テスト）

AGP 9のBuilt-in Kotlinを使用しているため、`org.jetbrains.kotlin.android` プラグインは適用していません。

## アーキテクチャ

```mermaid
flowchart TD
    UI["Compose UI"] --> VM["MainViewModel"]
    VM --> REPO["LibraryRepository"]
    REPO --> DB["Room / 端末内DB"]
    REPO --> NDL["NDL Search API"]
    CAMERA["CameraX + ML Kit"] --> UI
```

データは次の3層に分けています。

- `BookWork`: 作品そのもの
- `BookEdition`: ISBN、出版社、表紙、NDCなど版固有の情報
- `OwnedCopy`: 表示名、所有状態、置き場所、読書状態、取得日時
- `WishlistItem`: 未所有候補の欲しい・予約済み状態
- `ScanSession` / `ScanAttempt`: 直近の連続登録セッションと最小限の試行履歴

この分離により、同じ作品の紙版・電子版・改訂版や複数冊所蔵へ拡張できます。詳しくは [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) を参照してください。

## セットアップ

必要な環境:

- JDK 17
- Android SDK 37
- Android Studio（AGP 9.3対応版）またはコマンドライン環境

```bash
cd ndc-shelf-android
./gradlew testDebugUnitTest lintDebug
./gradlew installDebug
```

カメラやスキャン処理を変更した場合は、エミュレーターだけでなく実機でも確認してください。Codexなどの開発エージェント向けの作業ルールは [AGENTS.md](AGENTS.md) にまとめています。

暗所・小型バーコード・100冊連続・端末差の確認条件は[スキャン実機検証手順](docs/SCAN_DEVICE_TESTING.md)を使用してください。

Room schemaとRepositoryのテスト構成、Migration追加手順は[docs/DATABASE_TESTING.md](docs/DATABASE_TESTING.md)を参照してください。

NDL Searchへの書誌・表紙通信、障害分類、再試行、表紙キャッシュの境界は[docs/NETWORK_BOUNDARY.md](docs/NETWORK_BOUNDARY.md)を参照してください。

利用者向け変更は[CHANGELOG.md](CHANGELOG.md)、v0.2の公開判定と復旧手順は[リリースチェックリスト](docs/releases/V0.2_RELEASE_CHECKLIST.md)および[ロールバック手順](docs/releases/V0.2_ROLLBACK.md)を参照してください。

NDL Search APIの利用にAPIキーは不要です。アプリはISBNの登録時だけ、対象ISBNをNDL Searchへ送信します。蔵書データは端末内に保存され、Androidのクラウドバックアップからも除外されます。Android 9以降の端末間転送ではRoom DBだけを移行対象にします。表紙表示時はNDL SearchのHTTPS画像URLへ接続し、最大50MiBの端末キャッシュを利用します。バーコード画像と認識結果はML Kitにより端末内処理されますが、同SDKは診断・利用分析メトリクスをGoogleへ送信します。詳しい取扱いは[PRIVACY.md](PRIVACY.md)、バックアップ判断は[docs/BACKUP_THREAT_MODEL.md](docs/BACKUP_THREAT_MODEL.md)を参照してください。

SRU APIには `recordPacking=xml` を明示し、DC-NDLの書誌要素をXMLとして取得・解析します。

## データ出典

本アプリは[国立国会図書館サーチのAPI](https://ndlsearch.ndl.go.jp/help/api)を利用しています。書誌情報およびNDC分類の一部は同サービスと[メタデータ提供機関](https://ndlsearch.ndl.go.jp/help/api/provider)に由来します。データの内容や利用条件は各提供元の案内をご確認ください。

## ロードマップ

- [x] 蔵書DB、検索、NDC分類、カメラスキャンの縦切り
- [x] 棚位置・読書状態の編集
- [x] ISBNとNDL XMLパーサーの単体テスト
- [x] JSON / CSVエクスポート
- [x] JSONインポート
- [x] CSVインポート
- [x] 書誌情報とNDCの手動補正
- [x] 蔵書コピーの削除と取り消し
- [x] 同じ版の複数冊所蔵
- [x] Roomデータベースの完全バックアップと復元
- [x] 本棚・部屋・段を管理する棚エディタ
- [x] 書店モード（所有済み警告、欲しい本、予約済み）
- [x] 連続スキャンのセッション履歴と安全な一括取り消し
- [ ] 漫画・シリーズの巻数管理
- [ ] バックアップ同期（任意・オプトイン）
- [ ] AI司書（任意・オプトイン）

詳細は [docs/ROADMAP.md](docs/ROADMAP.md) を参照してください。

## コントリビューション

IssueやPull Requestを歓迎します。開発を始める前に [CONTRIBUTING.md](CONTRIBUTING.md)、[Repository governance](docs/REPOSITORY_GOVERNANCE.md)、[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) をご確認ください。脆弱性は公開Issueにせず、[SECURITY.md](SECURITY.md) の手順で報告してください。

## ライセンス

[Apache License 2.0](LICENSE)
