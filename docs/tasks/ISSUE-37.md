# Implementation Contract: Issue #37

## Objective

ADR 0005とSync Protocol v1に従い、backendや暗号transportから独立したlocal-first同期engineを実装する。offline mutation、重複・順不同受信、同時更新、remove-wins削除、domain conflictを永続化し、同期障害でlocal domain dataを失わない。

## Current State

- branch: `agent/issue-37-sync-engine`
- base: `main`
- base HEAD: `3465884`
- related Issue: #37
- dependencies: #36、#17（完了済み）
- dependent Issues: #38（暗号envelope・backend adapter）、#39（同意・認証UI）
- database: Room v11。同期table、同期権限、同期通信は未実装

## Decisions

- Room v12でdevice state、local operation journal、field winner、tombstone、received / processed cursor、domain conflict、未解決依存を追加する。
- 同期対象のdomain mutationとjournal書込みは同じRoom transactionでcommitする。
- journalは暗号化前のlocal domain stateでありapp-private Room内だけに置く。Issue #38がjournalをE2EE ciphertext outboxへ変換し、平文をnetwork queueへ保存しない。
- operationの因果順序はdevice counterとversion vectorで決め、wall clockはretentionと表示だけに使う。
- concurrent field updateはdotの辞書順winnerを表示値にし、loserをlocal conflict ledgerへ残す。deleteは同時または古いupdateにremove-winsとする。
- security-invalidなobjectの検証はIssue #38の責務とする。本Issueは署名・AEAD検証済みoperationだけを入力として扱い、domain conflictと混同しない。
- 完全バックアップへ同期鍵・credential・device ID・counter・journalを含めない。restore時は同期内部stateを全消去し、新device IDとinitial snapshotで再登録させる。
- status表示は個人データを含めず、既定OFF、pending件数、未解決conflict件数、最終成功時刻だけを示す。同期の有効化UIと実通信は追加しない。

## Scope

- backend非依存のoperation、dot、version vector、transaction model
- Room v11→v12 Migrationと全migration path検証
- local mutation journalと同期対象repositoryへのatomic接続
- idempotent receive、causal pending、field merge、remove-wins tombstone、conflict ledger
- ack、90日retention、active device確認後のcompaction
- fake transportによる2 replica convergence test
- data管理画面のread-only同期状態表示
- backup restore時の同期内部state reset

## Non-goals

- E2EE、HPKE、署名、canonical wire envelope
- OAuth、実backend、WorkManager upload/download
- 同期のON/OFF、端末追加・失効、remote purge UI
- multi-user共有、real-time共同編集
- scan履歴、series watch、release candidateの同期

## Invariants

- 同期OFF・未認証ではnetwork、鍵生成、background workを行わない。
- local domain mutationが成功してjournalだけ失敗する状態を作らない。
- operation IDを二度適用せず、順不同で因果依存が欠けたoperationをdomainへ適用しない。
- domain conflictをsilent overwriteせず、個人データを通常logへ出さない。
- tombstone対象IDを遅延updateで復活させない。
- 全active deviceのackと90日経過前にtombstoneを削除しない。
- restore、counter消失、device state不整合時に既存device IDを再利用しない。

## Required Verification

- Migration v11→v12と全start version→v12
- journalとdomain writeのrollback atomicity
- duplicate、out-of-order、retry、partial failure、clock ±10年
- 2 replica concurrent field update、delete/update、membership、order key convergence
- conflict ledger、manual resolution、tombstone retention / compaction
- backup restore後のsync state resetとdomain data保持
- sync OFFの既存repository非回帰とnetwork call 0
- JVM test、lint、debug APK、androidTest APK、API 29／35 instrumentation

## Acceptance Criteria

- [ ] ADR準拠のjournal、tombstone、cursor、device IDを永続化する。
- [ ] 同期OFF時の既存local動作と外部通信を変えない。
- [ ] field競合とdomain conflictを保存し、read-only状態表示へ件数を出す。
- [ ] 重複・順不同・再送・途中失敗で冪等に収束する。
- [ ] tombstoneを全active device ackかつ90日後だけ圧縮する。
- [ ] fake transportで2端末、offline、時刻ずれ、delete競合を検証する。
- [ ] backup restoreでdomain dataを保持し、sync stateを安全にresetする。
- [ ] 全ローカル検証とGitHub Actionsが成功する。

## Stop Conditions

- protocol v1の意味変更が必要になる。
- 同期OFFでも通信、認証、鍵生成が必要になる。
- plaintext operationをnetwork outboxへ永続化する必要がある。
- domain writeとjournalを別transactionにせざるを得ない。
- destructive migration、wall-clock LWW、silent conflict dropが必要になる。
