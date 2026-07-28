# ADR 0005: 任意同期をE2EE operation logとして提供する

- Status: Proposed
- Date: 2026-07-29
- Issue: #36
- Decision owners: repository maintainer and security reviewer

## Context

蔵書、読書状態、購入予定、部屋・棚・段の名称は、関心、健康、思想、生活環境を推測できる個人dataである。同期を追加すると、端末内だけだった信頼境界へnetwork、認証provider、同期backend、別端末が加わる。backend固有schemaへRoomの全量snapshotを保存する方式は、漏えい範囲、競合時のdata喪失、vendor lock-inを大きくする。

アプリはofflineで全機能を使えること、同期は明示的opt-inであること、公開exportを維持することを既存方針としている。また、series membership、work group membership、fractional shelf order keyは将来の同期を見越して独立IDと決定的順序を持つ。

## Decision Drivers

- backend運営者を含む保存先から蔵書payloadを秘匿する
- offline編集と複数端末の同時更新で確定dataを黙って失わない
- backendを交換しても同じ公開protocolを実装できる
- Android API 23以降で、独自暗号を実装せず利用できるprimitiveを選ぶ
- 端末失効、account削除、鍵紛失の限界を利用者へ正確に示す
- 現在のRoom modelをnetwork schemaへ直接固定しない

## Considered Options

### Option A: backend管理鍵でRoom snapshotを暗号化する

**メリット**: server-side検索、復旧、実装が比較的容易。

**デメリット**: backend侵害または運営者権限で全payloadを復号できる。全量競合、vendor lock-in、過剰送信を避けにくい。

### Option B: 利用者のE2EE鍵でimmutable operation logを暗号化する

**メリット**: backendは平文を持たず、差分同期、offline編集、backend交換、履歴検証を両立できる。

**デメリット**: 鍵紛失をbackendで救済できない。tombstone、compaction、端末失効、因果履歴の実装が必要になる。

### Option C: 既存の汎用同期databaseへRoom modelを委譲する

**メリット**: transport、retry、競合処理の一部を再利用できる。

**デメリット**: provider固有schemaと競合規則へ依存し、E2EE、削除、公開exportの保証をproviderごとに再検証する必要がある。

## Decision

**Option B**を採用する。端末内Roomを常に正本とし、local mutationと同期outboxを同一transactionで記録する。backendはversion付きの暗号化済みimmutable objectと条件付き更新可能なheadを保存するだけで、domain payloadを解釈しない。

protocol v1は次を必須とする。

- payloadはRFC 8785準拠のJSON Canonicalization Schemeで、未知のoptional fieldは保持できる構造にする。snapshotは同じ公開schemaでexport可能にする。
- contentはlibrary epochごとの256-bit keyからdevice別subkeyをHKDF-SHA-256で導出し、AES-256-GCMで暗号化する。deviceごとの永続64-bit encryption counterを96-bit nonceへ符号化し、同じsubkeyで再利用しない。
- 端末追加時のkey wrappingはRFC 9180 HPKEの`DHKEM(P-256, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM` suiteを使う。
- 各端末はAndroid Keystore内のP-256署名鍵でenvelopeへ`SHA256withECDSA`署名を行う。さらにKeystore内のnon-exportable AES-256-GCM wrapping keyでHPKE recipient private keyとepoch keyを暗号化保存する。API 23〜30ではECDH用Keystore purposeがないためHPKE private keyは使用時にprocess memoryへ復号する。StrongBoxは利用可能なら署名・wrapping keyへ推奨するが必須にしない。
- transportは証明書検証を無効化しないHTTPSとし、TLS 1.2以上を必須、TLS 1.3を推奨する。E2EEとtransport暗号のどちらか一方で代替しない。
- account認証はbackend adapterの責務とする。OAuthを使うnative adapterはauthorization code + PKCEを必須とし、passwordを独自収集しない。account不要adapterは認証を要求せず、認証credentialは常にE2EE鍵と分離する。
- operationは`deviceId + counter`のdotとversion vectorを持つ。wall clockは表示・診断だけに使い、競合の勝敗へ使わない。
- 因果的に新しいfield updateを採用し、同時field updateはdotのbytewise順で大きい方を決定値とし、失われた候補をconflict logへ残す。entity deleteは同時updateに対してremove-winsとする。
- tombstoneは全active deviceのackまたはdevice失効と、最低90日の双方を満たすまで削除しない。復元は旧IDのresurrectionではなく新IDで作成する。
- device失効時はregistry generationを進めて新epochへkey rotationし、失効端末へ新keyをwrapしない。失効generation以後の旧端末operationは受理しない。既に取得済みの平文・旧epoch keyは遠隔消去できない。

algorithm suite、wire field、競合規則のnormativeな詳細は[同期protocol](../SYNC_PROTOCOL.md)、信頼境界と対策は[同期脅威モデル](../SYNC_THREAT_MODEL.md)を正本とする。

## Consequences

**Positive**:

- backend侵害時もpayload機密性と端末署名による改ざん検出を維持できる。
- offline mutationを先にlocalへ確定し、backend停止から独立できる。
- 同じprotocolをfilesystem、WebDAV相当、hosted backend等のadapterで利用できる。
- 公開snapshotとoperation logにより、アプリとbackendの双方から移行できる。

**Negative / trade-offs**:

- 最後のauthorized deviceと回復用完全backupを同時に失うと同期payloadを復号できない。
- backendは全文検索、重複排除、内容ベースのmoderationを行えない。
- metadataとしてaccount、接続IP、時刻、object数、概算sizeはbackendから見える。
- operation log、tombstone、conflict UI、compactionの実装・試験costが増える。

**Residual risks**:

- 悪意あるbackendはobjectの削除、遅延、古いheadの提示、端末ごとのforkを行える。署名chainと既知headでrollbackは検出するが、端末間gossipなしでは選択的forkを完全検出できない。
- malware、root、ロック解除済み端末は、アプリが復号した平文を取得し得る。
- API 23互換のためHPKE recipient private keyとepoch keyは使用中にapp process memoryへ現れ、process compromise時の抽出をKeystoreだけでは防げない。
- 端末失効は将来epochへのaccessを止めるだけで、過去に取得されたdataを消去しない。
- payload paddingを行ってもtraffic timingと総量から利用頻度を推測され得る。

## Implementation Notes

- Issue #37はlocal journal、merge、tombstone、conflict log、snapshot、compactionを実装する。
- Issue #38はこのprotocolだけへ依存するbackend interface、capability negotiation、device管理を実装する。
- Issue #39はopt-in、送信preview、撤回、sign-out、remote purgeの共通UIを実装する。
- cryptoはplatform APIまたは保守されている監査済みlibraryを使い、primitiveやcanonicalizationを独自実装しない。
- protocol conformance fixture、known-answer test、nonce uniqueness、clock skew、partition、tamper、rollback、device revokeをCIで検証する。
- schemaまたはalgorithm変更は新しいprotocol versionまたはsuite IDを追加し、同じIDの意味を変更しない。

## Acceptance Evidence

| Issue #36 acceptance criterion | Normative source |
| --- | --- |
| 同期対象・除外項目・metadata・識別子・data flow | `SYNC_PROTOCOL.md` 2〜4 |
| 端末内・転送中・保存先の暗号化、鍵管理、鍵紛失 | `SYNC_PROTOCOL.md` 2.1、6、8、`SYNC_THREAT_MODEL.md` 6 |
| 認証、端末追加・失効、account・remote削除 | `SYNC_PROTOCOL.md` 8 |
| offline、同時更新、削除、順序、時刻ずれ | `SYNC_PROTOCOL.md` 7、10 |
| protocol versionと公開export | `SYNC_PROTOCOL.md` 5、11 |
| backend停止・侵害・誤同期の復旧 | `SYNC_PROTOCOL.md` 10、`SYNC_THREAT_MODEL.md` 4 |
| STRIDE、residual risk、Non-goals | `SYNC_THREAT_MODEL.md` 3、7 |

ADRはDraft PRでsecurity・architecture reviewを受け、merge時に`Accepted`へ変更する。未公開の脆弱性はPRへ記載せずprivate reportingを使用する。

## Rollback

同期実装を無効化してもRoomのlocal dataは維持する。未送信outboxを削除せずexport可能にし、remote objectの自動削除は行わない。protocol v1に重大な欠陥が見つかった場合は同期をfail-closedで停止し、より大きいapp versionで新suiteまたはprotocolへ移行する。旧key・objectの削除は全active deviceの移行確認と利用者への明示後に行う。
