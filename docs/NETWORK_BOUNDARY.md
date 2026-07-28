# NDL外部通信・障害対応方針

## 目的

蔵書の閲覧・編集をオフラインで維持しながら、書誌と表紙に必要な最小限の通信だけを国立国会図書館サーチ（NDL Search）へ許可する。

## 通信境界

| 用途 | 発生タイミング | 送信先 | アプリが送る値 |
| --- | --- | --- | --- |
| 書誌・NDC取得 | 未登録ISBNをスキャンまたは手入力したとき | `GET https://ndlsearch.ndl.go.jp/api/sru` | 正規化したISBN、固定のSRU引数（`recordSchema=dcndl`、`recordPacking=xml`、最大1件）、User-Agent |
| 表紙取得 | 本棚に保存済みのNDL表紙を表示し、メモリ／ディスクcacheにないとき | `GET https://ndlsearch.ndl.go.jp/thumbnail/<ISBN>.jpg` | URL path内のISBN、通常のHTTPS request header |
| シリーズ候補 | シリーズ単位で明示的に有効化し、WorkManagerの制約を満たしたとき（シリーズごとに週1回。失敗時は指数バックオフで再試行） | `GET https://ndlsearch.ndl.go.jp/api/sru` | 対象シリーズ名、検索開始年、`dpid=open`、最大20件、User-Agent |

シリーズ候補では明示したシリーズ名以外のタイトル、著者、NDC、読書状態、置き場所、購入状態、他の蔵書は送らない。全経路ともHTTPSのみで、redirectを追従しない。importした表紙URLも、NDL host、既定HTTPS port、`/thumbnail/<同じISBN>.jpg`、query・fragmentなしの全条件を満たす場合だけ通信する。

## シリーズ候補の障害処理

周期処理はネットワーク接続とbattery-not-lowを必須とし、一時的なDNS・timeout・HTTP 429・5xx・I/O失敗ではWorkManagerへretryを返す。指数バックオフは1時間から開始し、同じ7日間に成功済みのシリーズはretry時に再照会しない。4xx（429以外）、不正XML、2MiB超のbody、タイトル不一致は再試行しない。外部障害時も保存済み候補と蔵書機能は利用できる。候補IDと通知済み時刻をRoomへ保存し、通知成功後だけ通知済みにする。

仕様の根拠は[NDL Search API仕様](https://ndlsearch.ndl.go.jp/help/api/specifications)と[API利用案内](https://ndlsearch.ndl.go.jp/help/api/)である。NDLは大量の同時・継続アクセスを制限し得るため、連続自動取得や無制限retryは行わない。

## 書誌取得の障害処理

| 結果 | 分類 | 自動retry | 画面からのretry |
| --- | --- | --- | --- |
| SRU 200、該当recordなし | not found | なし | なし |
| DNS・接続不可 | offline | 1回 | あり |
| connect/read/call timeout | timeout | 1回 | あり |
| HTTP 429 | rate limited | 1回 | あり |
| HTTP 5xx | service unavailable | 1回 | あり |
| その他のI/O失敗 | network | 1回 | あり |
| HTTP 4xx（429以外） | request rejected | なし | なし |
| XML不正・body欠落 | invalid response | なし | なし |

retryableな失敗は750ms、HTTP 429だけは3秒待って1回だけ再送する。OkHttpの暗黙retryは無効化し、合計2 requestを上限とする。最終失敗後は利用者が明示的に「再試行」を選べる。coroutine cancellationはHTTP callへ伝播し、失敗結果へ変換しない。

## 表紙cache

- 共有する単一のCoil `ImageLoader`を使用する。
- decoded imageのmemory cacheはアプリが使用可能なmemoryの10%を上限とする。
- network imageのdisk cacheは`cacheDir/ndl-cover-cache`に最大50MiBで保持し、CoilのLRUにより古い項目から削除する。
- cache keyは表紙URLである。保存URLが変われば別項目として取得し、端末の「cacheを削除」、アプリデータ削除、アンインストールでも無効化される。
- connect 5秒、read 10秒、call全体15秒で打ち切る。redirectとOkHttpの接続retryは無効にする。
- cache missや取得失敗では本のアイコンを表示し、Roomに保存済みの本棚操作を妨げない。

Coil 3はnetwork responseをdisk cacheへ保存するため、このアプリではNDLの表紙に限ってcacheを有効化する。表紙cacheは再生成可能で、バックアップ・export・Android端末間転送の対象にしない。

## 利用条件と運用

現在は広告・課金のない非営利用途を前提とする。NDLの案内では、営利利用やThumbnail APIの利用主体によって事前申請が必要になる場合がある。また継続アクセスでは、申請要否にかかわらず利用内容と連絡先の事前連絡が推奨される。シリーズ監視を含む版の一般公開前に最新条件を再確認してNDLへ利用内容を連絡し、収益化、法人配布、通信頻度・対象データの変更時は再評価する。

## テスト

- MockWebServerでSRU query/header、200、not found、4xx、429、5xx、malformed XML、retry上限、cancellationを検証する。
- URL policyでscheme、host偽装、userinfo、port、query、path、ISBN不一致を拒否する。
- 表紙requestでmemory/disk/network cache有効化と外部URLのrequest不生成を検証する。
- ViewModelでretryableな失敗だけ再試行導線を持ち、再試行後の成功を検証する。

MockWebServerはtest scopeだけで使用し、Apache License 2.0である。release APK、権限、利用者データ処理には影響しない。
