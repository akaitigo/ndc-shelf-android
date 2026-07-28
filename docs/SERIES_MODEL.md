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

## 所有状態と欠巻候補

シリーズ一覧と詳細は、MembershipをWork、Edition、OwnedCopy、WishlistへJOINして次を巻単位で集約します。

- 所有状態はOwnedCopyが1冊以上なら`OWNED`、それ以外は`RESERVED`、`WANTED`、`UNOWNED`の優先順で決める。
- 読了数は、少なくとも1冊のOwnedCopyが`READ`である巻の数とし、同じ巻の複数冊を重複計上しない。
- 最新所有巻は、確認済みの`sortOrderKey`順で最後にある所有巻とする。シリーズの`updatedAt`と「ユーザー確認データ」を併記し、外部の最新刊を表すとは断定しない。
- 欠巻候補は未所有の`MAIN_STORY`で、整数・小数（「第1巻」を含む）・上下中巻・前後編の明示的な巻ラベルを持つMembershipだけとする。
- 保存されていない番号の穴から巻を生成しない。`SIDE_STORY`、`OMNIBUS`、`OTHER`、巻番号なしは未所有表示できるが欠巻候補へ含めない。

この規則は過剰な購入誘導を避けるため意図的に保守的である。将来、外部書誌から刊行巻を提案する場合も、ユーザー確定前はMembershipへ追加せず、出典と取得時刻を分離して表示する。

## Migrationと互換性

DB v7からv8は2テーブルとindexだけを追加し、既存Work・Edition・Copyを更新しません。完全バックアップ形式は既存の形式8を再利用せず形式9へ上げ、SeriesとMembershipを外部キー順で検証・復元します。形式1から8のバックアップはSeriesなしとして引き続き読めます。

## セキュリティ・運用

完全バックアップのレコード数、文字列長、外部キー、一意性、enum、順序キーを復元前に検証します。未知の種別や不正な順序キーを黙って補正せず、整合性エラーとして復元を中止します。シリーズ画面は端末内データだけで表示し、書店モードを利用者が選択したときだけ既存のNDL Search通信境界へ遷移します。DB downgradeは提供しないため、ロールバックにはアップデート前バックアップが必要です。
