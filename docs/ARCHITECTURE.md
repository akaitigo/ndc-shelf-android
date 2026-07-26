# Architecture

## 方針

NDC Shelfは、最初のリリースでは単一のAndroidアプリモジュールに保ちます。パッケージ境界で責務を分け、ビルド時間や変更頻度が分離のコストを上回った時点でマルチモジュール化します。

## パッケージ

| パッケージ | 責務 |
| --- | --- |
| `domain/model` | UIや保存方法に依存しない蔵書モデル |
| `domain/export` | バージョン付きJSONと安全なCSVの逐次出力 |
| `domain/importer` | 形式非依存の入力検証、競合解決、プレビュー、原子反映 |
| `domain/repository` | ユースケースから見たデータ操作の契約 |
| `data/local` | RoomのEntity、DAO、Database |
| `data/remote` | NDL Search SRUクライアントとXML解析 |
| `data/repository` | ローカル・リモートデータの統合 |
| `scanner` | ISBN検証とリアルタイムバーコード解析 |
| `ui` | Compose画面、テーマ、表示部品 |

## 登録フロー

```mermaid
sequenceDiagram
    participant C as Camera
    participant V as ViewModel
    participant R as Repository
    participant N as NDL Search
    participant D as Room
    C->>V: ISBN-13
    V->>R: addFromIsbn
    R->>D: 重複確認
    alt 未登録
        R->>N: SRU検索
        N-->>R: 書誌 + NDC
        R->>D: Work / Edition / Copyを保存
        R-->>V: Added
    else 登録済み
        R-->>V: Duplicate
    end
```

## インポートフロー

```mermaid
sequenceDiagram
    participant P as JSON / CSV Parser
    participant I as Import Planner
    participant R as Repository
    participant D as Room
    P->>I: 未検証レコード + 入力サイズ
    I->>R: 正規化済みプレビュー
    R-->>I: 追加 / 更新 / スキップ件数
    Note over I,D: ユーザー確定まではDBを変更しない
    I->>R: 確定済みプレビュー
    R->>D: 現在状態を再照合
    alt 状態が一致
        R->>D: 単一トランザクションでupsert
    else 状態が変化
        R-->>I: StalePreview（再確認要求）
    end
```

形式パーサーとUIは必ず共通インポート基盤を経由します。詳細な上限、競合方針、ロールバック条件は[IMPORT_SAFETY.md](IMPORT_SAFETY.md)を参照してください。

## データモデル

```mermaid
erDiagram
    BOOK_WORK ||--o{ BOOK_EDITION : has
    BOOK_EDITION ||--o{ OWNED_COPY : owned_as
    BOOK_WORK {
        string id PK
        string title
        string primaryAuthor
    }
    BOOK_EDITION {
        string id PK
        string workId FK
        string isbn13 UK
        string ndcCode
        string coverUrl
    }
    OWNED_COPY {
        string id PK
        string editionId FK
        string location
        string readingStatus
    }
```

`BookEdition.isbn13` は現在ユニークです。複数冊所蔵を正式に扱う段階では、既存Editionに新しいOwnedCopyを追加するユースケースを設けます。

### 手動補正の優先順位

詳細編集では、タイトル・著者を`BookWork`、出版社・出版年・NDCを`BookEdition`、置き場所・読書状態を`OwnedCopy`へ単一トランザクションで保存します。入力は保存前に前後空白、必須項目、文字数、出版年、NDCコードを検証します。

NDCコードまたはNDC版を変更した場合、`classificationSource`を`MANUAL`にします。同じISBNを再スキャンしても既存蔵書の重複判定をNDL取得より先に行うため、手動値を上書きしません。将来NDL再取得機能を追加する場合も、`MANUAL`の分類値は明示確認なしに更新してはいけません。

保存直後は変更前と変更後のスナップショットを保持し、Snackbarから取り消せます。取り消し時点のDBが保存直後の状態と一致する場合だけ、3テーブルを単一トランザクションで復元します。別操作による変更を検出した場合は復元を拒否します。

## プライバシー

- 蔵書DBは端末内に保存します。
- バーコード認識は端末内で処理します。
- 書誌情報の検索時にISBNをNDL Searchへ送信します。
- アナリティクスSDKや広告SDKは組み込みません。
- 将来の同期やAI機能は明示的なオプトインにします。
