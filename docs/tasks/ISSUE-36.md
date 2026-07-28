# Implementation Contract: Issue #36

## Objective

任意同期を実装する前に、同期対象、暗号化、鍵管理、端末管理、競合解決、削除、復旧、公開形式を固定し、backendや実装担当者が変わっても個人データ保護とローカルファーストを維持する。

## Current State

- branch: `agent/issue-36-sync-adr`
- base: `main`
- base HEAD: `f92688e`
- related Issue: #36
- dependent Issues: #37、#38、#39
- current application: Room v11、端末内DBが正本、同期用table・権限・依存なし

## Decisions

- 同期は全体で明示的なopt-inとし、既定OFFでは認証、鍵生成、background work、同期先通信を行わない。
- backendは暗号化済みimmutable object、head、端末公開鍵、最小限のaccount metadataだけを扱い、蔵書payloadを復号しない。
- payloadはversion付きcanonical JSONを一定幅へpaddingしてからAEADで暗号化し、端末署名を付ける。
- 端末内の鍵はAndroid Keystoreで保護する。認証credentialとE2EE鍵を分離し、backendによる鍵復旧は提供しない。
- mutationは端末IDごとの単調counterとversion vectorで因果関係を表し、wall clockを競合判定に使わない。
- 同時更新はfield単位、membershipは独立entity、削除はremove-wins、順序は既存fractional keyとentity IDで決定する。
- scan履歴、watch実行状態、release candidate、cache、通知状態、credential、鍵は同期しない。
- 同期前snapshot、transactional apply、署名・schema・制約検証、quarantine、再取得で障害から復旧する。

## Invariants

- 未同意、同意撤回後、sign-out後に同期先へ新しいrequestを送らない。
- backend、network観測者、別accountはpayloadの平文を取得できない。
- 不正署名、未知の必須version、AEAD失敗、因果履歴欠落、DB制約違反を部分適用しない。
- offline編集を許可し、同期失敗でlocal確定データを失わない。
- 削除済みentityを遅延updateで復活させない。復元は新しいIDを持つ明示操作とする。
- 端末時計の変更で勝敗、削除、順序が変わらない。
- 同期backendを変更しても公開snapshotとoperation logから移行できる。
- 失効端末から既に取得済みの平文を遠隔消去できるとは表示しない。

## Scope

- 同期ADRと選択肢比較
- data flow、同期対象・除外項目・識別子
- protocol v1 envelope、operation、snapshot、capability、version negotiation
- E2EE、端末署名、鍵追加・失効・紛失
- offline、同時更新、削除、順序、時刻ずれの規則
- backend停止・侵害・誤同期時の復旧とrollback
- STRIDE脅威モデル、Non-goals、残存リスク

## Non-goals

- 同期engine、backend adapter、画面、認証providerの実装
- 特定cloud vendor、課金、SLAの決定
- 複数人によるlibrary共有、real-time共同編集
- backendからの全文検索、AI処理、平文analytics
- 失効前に端末へ保存されたdataの遠隔消去保証
- root化端末、悪意あるOS、ロック解除済み端末の完全防御

## Required Verification

- ADR、protocol、threat model間で用語、対象範囲、algorithm suite、versionが一致する。
- 現在のRoom entityと同期対象表を照合する。
- Issue #36の各受け入れ条件から正本sectionへ追跡できる。
- `git diff --check`
- `./gradlew testDebugUnitTest lintDebug assembleDebug`
- Draft PRのGitHub Actions成功

## Acceptance Criteria

- [x] 同期対象、除外項目、metadata、識別子をdata flow図と表で定義する。
- [x] 端末内、転送中、保存先の暗号化と鍵管理・紛失時挙動を定義する。
- [x] 認証、端末追加・失効、account削除、remote全削除を定義する。
- [x] offline編集、同時更新、削除、順序、時刻ずれの競合規則を定義する。
- [x] protocolをversion化し、export可能な公開形式を維持する。
- [x] backend停止・侵害・誤同期時の復旧とrollbackを定義する。
- [x] STRIDEで脅威、対策、検証、残存リスク、Non-goalsを記録する。
- [x] 後続Issue #37・#38が参照するnormative requirementを明記する。
- [x] Draft PR #100を作成する。
- [x] GitHub Actions run 30399983566でverify、dependency review、OSV、API 29／35のinstrumentation成功を確認する。

## Stop Conditions

- E2EEを外し、backendが蔵書payloadを復号する設計が必要になる。
- 同期OFFでも通信またはaccount作成が必要になる。
- wall clockだけで競合を解決する必要が生じる。
- destructive migration、全量last-writer-wins、silent conflict dropが必要になる。
- 独自暗号primitiveまたは未監査の暗号実装が必要になる。
- 公開exportなしで独自backendへdataを閉じ込める必要が生じる。
