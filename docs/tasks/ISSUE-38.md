# Implementation Contract: Issue #38

## Objective

ADR 0005とSync Protocol v1に従い、交換可能な同期backend interface、E2EE暗号envelope、鍵管理、端末管理を実装する。参照backendはaccount不要のSAFフォルダadapterとし、利用者が選んだフォルダへ暗号化済みobjectだけを保存する。backendを交換してもJSON exportとlocal利用を維持する。

## Current State

- branch: `agent/issue-38-sync-backend`
- base: `main`
- base HEAD: `7d77b36`（作業後半で`origin/main`（#118まで）をマージ。Room版はmainのタグ機能v15と衝突したため自分の版をv16へ改番）
- related Issue: #38
- dependencies: #36（protocol確定）、#37（RoomSyncEngine）、#39（ConsentPurpose.LIBRARY_SYNC・ConsentPayloadDialog）
- database: Room v15（mainのタグ機能まで）。暗号鍵state、device registry cache、招待、quarantineは未実装

## Decisions

- transport契約はSYNC_PROTOCOL.md 9節の`SyncBackend` interfaceとして`domain/sync`へ置き、TLS失敗・認証失敗・期限切れ・rate limit・サービス停止・IO失敗・権限喪失・容量不足を共通enum `SyncBackendErrorKind`で分類する。retry可否はenumの属性で決め、認証・権限・容量系は自動retryしない。
- 参照実装はSAFフォルダadapter（`FolderSyncBackend` + `SyncObjectStore`抽象）。objectはcontent-addressed・immutable、headはetag（内容hash）比較のcompare-and-set、deletionはフォルダ内全object削除と空確認のreceiptで実装する。ADRの「filesystem/WebDAV相当adapter」に該当し、account認証を要求しない。
- HPKE `DHKEM(P-256, HKDF-SHA256)/HKDF-SHA256/AES-256-GCM` はGoogle Tink（`tink-android`、Apache-2.0）を使用する。protocol 6.1節が要求するinfo/AAD束縛のためTinkのHPKE contextを用い、HPKE primitiveを独自実装しない。AES-256-GCM・SHA256withECDSA・HKDFはplatform（javax.crypto/AndroidKeyStore）とTink subtle（`Hkdf`、DER検証）を使う。
- RFC 8785 canonical JSONは保守済み実装`io.github.erdtman:java-json-canonicalization`（Apache-2.0）へ委譲し、独自canonicalizerを作らない。
- 鍵管理は`SyncKeyManager` interfaceで抽象化する。本番は`AndroidKeystoreSyncKeyManager`（Keystore内non-exportable P-256署名鍵 + AES-256-GCM wrapping key、`setRandomizedEncryptionRequired(true)`、AADでkey種別・libraryId・key versionを認証）、JVMテストは同じ契約の`FakeSyncKeyManager`（JCA in-memory鍵）で実crypto経路を検証する。Keystore実鍵はandroidTestで検証する。
- HPKE秘密鍵とepoch keyはwrapping keyで暗号化した`sync_wrapped_keys`（Room v16）だけへ永続化する。平文鍵・平文operationをRoom外・log・backupへ出さない。
- nonceは32-bit zero prefix + big-endian 64-bit encryption counterの96-bit。counterは`sync_identity`でobject作成前にtransactionalに増加・永続化し、再利用しない。counter異常はdevice ID廃棄と再登録を要求する。
- device registryは署名済みJSON文書としてbackendへ置き、headがregistry hashと世代を持つ。端末追加・失効はregistryGenerationを進め、失効は同一管理transactionで新epochへrotationし、失効端末へ新keyをwrapしない。失効generation以後に旧端末が署名したobjectは拒否する。
- 新端末追加はSAF adapterでは短時間・一回限りの招待コード（QR相当のout-of-band secret。既存端末に表示し新端末へ手入力）とHMACで新端末公開鍵を認証し、双方に表示する6桁verification codeの照合と既存端末での明示承認後にHPKEでcurrent epoch keyをwrapする。10分expiry・nonce一回性・trusted head固定はprotocol 8.2どおり。QR画像表示は同一payloadのUI表現として将来課題とし、protocol意味は変更しない。
- 新端末は承認時に作成するcurrent epochのbootstrap snapshot objectから開始し、過去epoch keyを受け取らない。
- 同期有効化はConsentPurpose.LIBRARY_SYNC同意（既定OFF、ConsentPayloadDialog経由）とinitial snapshot upload成功の両方で確定する。同意なし・撤回後・同期OFFではnetwork・ファイル・鍵生成を一切行わない（fail-closed）。
- WorkManager定期同期はopt-in時だけ一意periodic workで登録し、撤回・停止でcancelする。worker内でも同意を再検査する。
- security不正（署名・AEAD・hash・schema・サイズ・失効境界）objectはsize上限付きで`sync_quarantine`へ保存して同期を停止し、domainへ適用しない。domain conflictはRoomSyncEngineのledger処理を変更しない。
- backup restore時は既存`resetSyncStateAfterDomainRestore`を拡張して新tableも全消去し、既存device IDを再利用しない。
- remote全削除は明示確認後にフォルダ内の全object・head・registry・envelope・join requestを削除し、空確認のreceiptを表示する。local domain dataは変更しない。

## Scope

- `SyncBackend` interface、エラー分類enum、capability・head・registry・envelopeのwire model
- RFC 8785 canonical JSON、AES-256-GCM content envelope（6節）、HPKE device key envelope（6.1節）、ECDSA署名・厳密DER検証
- `SyncKeyManager`（Keystore実装 + JVM fake）と鍵のwrap保存
- Room v15→v16（sync_identity、sync_wrapped_keys、sync_peer_devices、sync_invites、sync_processed_envelopes、sync_quarantine）
- SAFフォルダadapter（DocumentsContract実装 + java.io File実装）と冪等upload・CAS head・削除receipt
- 同期lifecycle coordinator（有効化、genesis/bootstrap snapshot、手動同期、端末追加・承認・失効、sign-out、remote purge）
- WorkManager定期同期（opt-in時のみ）
- データ管理画面の同期セクション（有効化、フォルダ選択、手動同期、端末一覧・失効、全削除、停止）とConsent画面へのLIBRARY_SYNC追加
- CHANGELOG、PRIVACY.md、実装契約の更新

## Non-goals

- OAuth/HTTPS hosted backend adapter（interfaceだけ定義）
- QR画像の表示・camera scan UI（招待コードで同等の信頼特性を提供）
- compaction manifest、operation logの遠隔圧縮
- multi-user共有、conflict解決UIの拡張

## Invariants

- 同期OFF・同意なしでnetwork request、SAF読み書き、Keystore鍵生成を行わない。
- 平文のdomain payload・鍵材料をbackend・log・quarantine・backupへ出さない。backendにはE2EE ciphertextとheadだけを置く。
- 同じdevice subkeyでnonceを再利用しない。counter永続化前にobjectを作らない。
- 検証（size→suite→epoch→device authorization→signature→hash→AEAD→schema）に失敗したobjectをdomainへ適用しない。
- 失効generation以後の旧端末operationを受理しない。失効端末へ新epoch keyをwrapしない。
- backend・adapterを変更してもRoomのlocal domain dataとJSON exportを変更しない。
- backup restore後に既存device ID・counterを再利用しない。

## Required Verification

- crypto: HKDF/nonce/padding/objectId/署名/改ざん検出のknown-answerとnegative test
- HPKE: wrap→unwrap roundtrip、宛先不一致・AAD改ざん・期限切れ・nonce再利用の拒否
- 2端末（fake keystore×2、実Tink、temp folder backend）でのjoin→鍵引き継ぎ→暗号化op交換→収束のE2E
- 失効後の旧端末operation拒否とepoch rotation後の復号不可
- SAF adapterのIO失敗・権限喪失・容量不足の分類、冪等upload、CAS競合
- 同期OFF/同意なしでbackend・鍵・ファイルアクセスがゼロ
- backup restore後のsync state resetとdomain data保持、同期無効化後のlocal data不変・JSON export継続
- Migration v15→v16と全migration path、schema JSON差分ゼロ
- `verifyRoborazziDebug lintDebug assembleDebug assembleDebugAndroidTest verifyV04ReleaseConfiguration verifyBackupPolicy verifyLicenseReport :app:cyclonedxDirectBom`と`git diff --exit-code -- app/schemas`

## Acceptance Criteria

- [x] Transport interfaceと参照SAFフォルダadapterを分離して提供する。
- [x] 鍵材料をAndroid Keystore（署名鍵・wrapping key）とwrap済みblobだけで保存する。
- [x] 新端末追加は明示確認・verification code・HPKE key wrappingを経る。
- [x] 端末一覧・最終同期表示・個別失効（generation進行 + epoch rotation）を提供する。
- [x] リモート全削除と完了確認receiptを提供する。
- [x] transport共通のエラー分類とretry方針を実装する。
- [x] backendを変えてもJSON exportとlocal利用を維持する。

## Stop Conditions

- SYNC_PROTOCOL.mdの意味を変更しないと実装できない。
- 平文operationをKeystore外へ永続化する必要が生じる。
- TinkがHPKE `DHKEM(P-256)/HKDF-SHA256/AES-256-GCM` suiteを提供しない。
- 同期OFFでも通信・認証・鍵生成が必要になる。
