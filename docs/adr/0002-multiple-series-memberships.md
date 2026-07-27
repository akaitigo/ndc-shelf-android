# ADR 0002: Workは複数シリーズへ所属できる

- Status: Accepted
- Date: 2026-07-27
- Related: #30

## Context

漫画やライトノベルには、本編シリーズと共有世界、アンソロジー、外伝系統が重なる作品があります。Workへ単一の`seriesId`を持たせると、この重なりを失うかシリーズを複製する必要があります。またタイトルや巻ラベルから所属と順番を自動推測すると、誤統合をユーザーの確定情報と区別できません。

順番には整数巻、小数巻、前後編、上下巻、外伝、合本が混在します。ラベルの自然言語解析は出版社ごとの差異が大きく、同期端末間で同じ結果になる保証もありません。

## Decision Drivers

- 保存済みの書誌情報と未確定の推測を混在させない
- 複数系列にまたがる作品を値損失なく表現する
- 削除、シリーズ統合、将来の同期で関連行を一意に識別する
- ラベルの表記に依存せず決定的に並べる
- 既存Workを変更せず移行する

## Considered Options

### Option A: WorkへnullableなseriesIdと巻番号を追加する

**メリット**: JOINが少なく実装が単純。

**デメリット**: Workは1シリーズにしか所属できず、外伝・共有世界・アンソロジーを損失なく表現できない。Work更新とシリーズ編集が強く結合する。

### Option B: SeriesMembershipを独立させ、1 Work対0件以上とする

**メリット**: 複数シリーズ所属を表現でき、所属ごとに巻ラベル、種別、順序を保持できる。Membership IDを同期・統合の操作単位にできる。

**デメリット**: 中間テーブルとJOINが必要。同一シリーズ内の重複を制約・競合処理で管理する必要がある。

## Decision

**Option B**を採用します。`Series`と`SeriesMembership`はランダム生成した不変IDを主キーとし、MembershipからSeriesとWorkへ`ON DELETE CASCADE`外部キーを張ります。1つのWorkは0件以上のSeriesへ所属できますが、同一Series内では`(seriesId, workId)`を一意にします。

巻ラベルはユーザーが確認した原文を`volumeLabel`へ保持し、`MAIN_STORY`、`SIDE_STORY`、`OMNIBUS`、`OTHER`の種別を別に保存します。表示順はラベルから推測せず、小文字16進数のfractional `sortOrderKey`を明示的に割り当てます。同順位はMembership IDを最終tie-breakerにするため、整数・小数・前後編・上下巻・外伝を任意の順番で決定的に表示できます。

シリーズ統合ではMembership IDを変更せず所属先だけを移します。移動先に同じWorkがある場合は自動上書きせず、どちらを残すか確認してから一方を削除します。同期用tombstoneと競合解決規則は同期ADRで決め、現段階では物理削除を採用します。

## Consequences

- 既存Workはv7からv8へのMigrationで一切更新されず、未所属のまま保持される。
- SeriesまたはWorkの削除時に孤児Membershipが残らない。
- タイトル解析による誤った自動所属は永続モデルへ入らない。
- 同一シリーズ内のWork重複と順序キー衝突はDB制約で失敗するため、呼び出し側は競合を明示的に解決する必要がある。
- 完全バックアップ形式v9はSeries、Membership、確定由来を含む。v1からv7は空のシリーズ、v8は手動・ユーザー確定として復元できるが、v9バックアップを旧アプリへ復元できない。

## Implementation Notes

- `series`と`series_memberships`をRoom v8で追加する。
- 一覧順は`sortOrderKey, membershipId`、シリーズ一覧は`name, seriesId`で安定化する。
- ロールバックはコードのrevertだけではv8 DBをv7へ戻せない。変更前の完全バックアップへ復元するか、v8テーブルを保持したまま修正版を配布する。
- 移行、外部キー、一意制約、順序、ドメイン変換、バックアップ往復を自動テストする。
