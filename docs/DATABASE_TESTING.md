# Roomデータベーステスト方針

## 目的

既存の蔵書を失うschema変更、DAO制約の退行、複数テーブル処理の部分書き込みをPull Requestの必須CIで検出する。

## テスト層

| 層 | 実行環境 | 責務 |
| --- | --- | --- |
| ドメイン単体テスト | JVM | ISBN、parser、validation、import計画などAndroid非依存ロジック |
| Room／Repository統合テスト | Robolectric JVM、API 35 | v1 fixture、schema検証、DAO、外部キー・index、Repository transaction、失敗・キャンセル・未知enum |
| instrumentationテスト | Android端末／エミュレーター | 実Android SQLite、WAL、ファイルI/O、バックアップ復元、Compose UI |

Robolectric層はGitHub Actionsの `testStandardDebugUnitTest` で毎回実行する。高速で安定した必須チェックを優先する一方、ホスト上のSQLite挙動は実端末と完全には同一でないため、既存の `androidTest` を削除せずリリース前の実機行列で補完する。

## 依存の根拠とライセンス

- `androidx.room:room-testing`: exported schemaから旧DBを作成し、Migration後のschemaをRoom自身で検証するために使用する。AndroidXと同じApache License 2.0。
- `org.robolectric:robolectric:4.16.1`: Android frameworkとRoomをJVM CIで実行するためにtest scopeだけへ追加する。MIT Licenseで、一部のAndroid由来コードはApache License 2.0。
- `androidx.test:core`: Robolectric上でアプリContextを取得するためにtest scopeだけへ追加する。Apache License 2.0。

これらはrelease runtimeへ入らず、APKサイズ・権限・利用者データ処理に影響しない。ライセンスは公式リポジトリと自動生成レポートで更新時に再確認する。

## Schemaの正本

`app/schemas/dev.ndcshelf.app.data.local.AppDatabase/<version>.json` を履歴の正本としてGit管理する。現在はv13で、必須列、主キー、外部キー、unique index、旧置き場所文字列、棚内順序、シリーズ所属、監視設定、同期journal・cursor・tombstone・競合証跡、目的別同意記録を `AppDatabaseMigrationTest` が検証する。

CIはKSP実行後に `git diff --exit-code -- app/schemas` を実行する。DB版を上げず既存JSONが書き換わった場合や、新しいfixtureをコミットし忘れた場合は失敗する。

## Migration追加手順

1. `APP_DATABASE_VERSION`を1増やし、Entity／DAOを変更する。版を飛ばさない。
2. 完全なSQLを使う `Migration(n, n + 1)` を追加し、`AppDatabase.MIGRATIONS`へ登録する。`fallbackToDestructiveMigration`は使用しない。
3. KSPが生成した新しいschema JSONをレビューし、過去fixtureを変更せずコミットする。
4. 変更前版のfixtureへ代表値と境界値をSQLで入れ、`runMigrationsAndValidate`後に必須列、外部キー、index、値保持、enum変換を検証する。
5. `everyExportedSchemaHasARegisteredPathToCurrentVersion`が、v1から現行版まで連続したfixtureと各1段Migrationを認識することを確認する。
6. `testStandardDebugUnitTest`に加え、対象APIのエミュレーターまたは実機でmigration instrumentationテストを実行する。
7. schema、バックアップ形式、ロールバック方法、既存ユーザーへの影響をPRへ記載する。

## 現在の回帰ケース

- v1 fixtureの全列、CASCADE外部キー、ISBN unique index、値保持
- exported schemaの連番と `AppDatabase.MIGRATIONS` の1段ずつの経路
- schema欠落した既存DBを破壊的再作成せず拒否
- v7からv8で既存Workを未所属のまま保持し、シリーズ外部キーと一意indexを追加
- v8からv9で既存Membershipを手動・ユーザー確定として保持し、候補由来列を追加
- v11からv12で既存domain dataを変更せず、同期を既定OFF・端末未登録で初期化
- 全開始版からv12までのMigrationと、同期journal・domain writeの原子的rollback
- 追加、重複、3テーブル更新、所有コピー削除と孤立データ掃除
- 途中INSERT失敗と不正importの全transactionロールバック
- metadata取得キャンセルの再送出と書き込み0件
- 未知の `classificationSource`、`mediaType`、`readingStatus` の安全な既定値
- 空の部屋・本棚を含む階層、同一親内の名称制約、並べ替え、使用中の場所の再割り当て
- fractional order keyの中間挿入、同期衝突回避、10,000冊の棚での局所移動性能

## 実行方法

```bash
./gradlew testStandardDebugUnitTest
./gradlew compileDebugAndroidTestKotlin
```

実機が利用できる場合は次も実行する。

```bash
./gradlew connectedStandardDebugAndroidTest
```
