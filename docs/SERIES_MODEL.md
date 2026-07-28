# シリーズモデル

## 用語

- `Series`: 作品をまとめるユーザー確定済みの系列。タイトル文字列から自動生成しない。
- `SeriesMembership`: WorkとSeriesの所属関係。独立した不変IDを持つ。
- `volumeLabel`: 「1巻」「1.5巻」「前編」「上巻」「外伝」など、画面に表示する確認済み原文。
- `sortOrderKey`: 表示順専用の小文字16進fractional key。ラベルの数値・語彙とは独立する。
- `type`: `MAIN_STORY`（本編）、`SIDE_STORY`（外伝）、`OMNIBUS`（合本）、`OTHER`（その他）。

## 不変条件

- Workは0件以上のSeriesへ所属できる。
- 同一Workは同一Seriesへ重複所属できない。
- 同一Series内で`sortOrderKey`は一意であり、同点時の読出しはMembership IDで安定化する。
- Series名と巻ラベルは空文字を許可しない。時刻は`createdAt >= 0`かつ`updatedAt >= createdAt`とする。
- SeriesまたはWorkを削除すると関連MembershipだけをCASCADE削除する。反対側のSeriesまたはWorkは削除しない。
- タイトル解析や外部メタデータによる候補は、ユーザー確定前にMembershipとして保存しない。

## 並び順

整数、小数、前後編、上下巻、外伝という表記は表示専用です。登録・並べ替え時に左右のMembershipからfractional keyを生成し、SQLiteでは`sortOrderKey ASC, id ASC`で読みます。これにより語彙やロケールを変えても保存順が変わりません。

## Migrationと互換性

DB v7からv8は2テーブルとindexだけを追加し、既存Work・Edition・Copyを更新しません。完全バックアップ形式は既存の形式8を再利用せず形式9へ上げ、SeriesとMembershipを外部キー順で検証・復元します。形式1から8のバックアップはSeriesなしとして引き続き読めます。

## セキュリティ・運用

完全バックアップのレコード数、文字列長、外部キー、一意性、enum、順序キーを復元前に検証します。未知の種別や不正な順序キーを黙って補正せず、整合性エラーとして復元を中止します。DB downgradeは提供しないため、ロールバックにはアップデート前バックアップが必要です。
