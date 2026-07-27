# ADR 0003: 版違いはWorkを残した可逆な作品グループで表現する

- Status: Accepted
- Date: 2026-07-27
- Related: #33

## Context

現在の`BookWork`は書名と主著者、`BookEdition`はISBN、出版社、出版年、表紙、NDC、書誌情報源を保持する。登録時はISBNごとにWorkも新規作成するため、単行本、文庫、新装版、電子版が別Workとして残る。Editionの`workId`を共有Workへ付け替えるだけでは、統合前の書名・著者、SeriesMembership、手動補正の境界を失い、解除時に元へ戻せない。

タイトルと著者の正規化は候補抽出には使えるが、同名異作品や翻訳・改題を安全に判別できない。シリーズ充足についても、版違いを常に代替扱いすると、合本や内容差のある版で誤った充足表示になる。

## Decision Drivers

- Work、Edition、OwnedCopy、SeriesMembershipの既存IDと値を変更しない
- 候補と利用者が確定した関連を分離する
- 関連付け前に版固有差分とシリーズへの影響を確認できる
- 誤関連をデータ損失なく解除できる
- シリーズ代替は利用者がグループ単位で明示的に選ぶ
- 将来の同期でグループと所属を独立した競合単位にできる

## Considered Options

### Option A: EditionのworkIdを共通Workへ変更する

**メリット**: 既存のWork対Edition関係だけで版違いを表現できる。

**デメリット**: 元Workを削除すると書名、著者、シリーズ所属を失う。元Workを残しても解除時の帰属を別途記録する必要があり、実質的に履歴モデルが必要になる。

### Option B: Work間の二項リンクを保存する

**メリット**: 2作品だけなら単純で、既存エンティティを変更しない。

**デメリット**: 3版以上で推移閉包、重複辺、解除後の連結成分を管理する必要があり、同期時の決定性が低い。

### Option C: WorkGroupとWorkGroupMembershipを追加する

**メリット**: 2版以上を1グループへ明示的に束ね、Workを不変のまま保持できる。グループ設定と所属を独立して同期・解除できる。

**デメリット**: 2テーブルとJOINが増え、1 Work 1グループの一意制約と最小2件のアプリ側不変条件が必要になる。

## Decision

**Option C**を採用する。`WorkGroup`は表示名、主著者、`seriesSubstitutionEnabled`、作成・更新時刻を持つ。`WorkGroupMembership`はランダム生成した不変IDでGroupとWorkを結び、`workId`を一意にして1 Workが複数グループへ重複所属することを防ぐ。

候補は正規化したタイトルと主著者から都度生成し、DBへ保存しない。候補画面は双方のISBN、出版社、出版年、NDC、表紙有無、媒体、所有冊数を表示する。確定時だけ同一トランザクションでGroupと2件のMembershipを追加する。既存グループへ追加する場合も、画面取得後に所属が変わっていないか再検証し、競合時は何も変更しない。

解除はMembershipだけを削除し、Work、Edition、OwnedCopy、WishlistItem、SeriesMembershipを変更しない。残りが1件以下になったGroupは残存Membershipとともに削除する。シリーズ代替を有効にしたGroupだけ、SeriesMembershipのWorkと同じGroupに属する別Workの所有Copy・Wishlistを集計対象へ含める。

## Consequences

- 誤関連を解除しても書誌・所有・読書状態・置き場所・シリーズ所属を完全に保持できる。
- 自動マージは存在せず、候補の信頼度にかかわらず利用者確認が必要になる。
- 同一作品の複数版を所有しても、シリーズ画面の冊数はCopy単位で集計される。
- 代替設定を無効にすれば、従来どおりSeriesMembershipのWorkだけを充足判定に使う。
- DB v9からv10へのMigrationは空テーブル追加だけで、既存Workを推測・更新しない。
- 完全バックアップ形式v11はGroupとMembershipを含み、v10以前は作品グループ0件として復元する。

## Implementation Notes

- DB外部キーはGroup削除とWork削除の双方でMembershipを`ON DELETE CASCADE`する。
- `(groupId, workId)`と`workId`を一意にし、孤児・重複をcodecでも復元前に拒否する。
- Group表示は`title, id`、所属表示は`createdAt, id`で決定的に並べる。
- downgradeは行わない。旧アプリへ戻す場合はアップグレード前のv9バックアップを使用する。
