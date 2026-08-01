# ADR 0009: AI司書の端末内LLMは「安全基盤を先に確定し、runtime採用は配布サイズ予算の承認後」とする

- Status: Proposed（runtime/model採用とAABサイズ予算の引き上げにmaintainer承認が必要）
- Date: 2026-08-01
- Issue: #125
- Decision owners: repository maintainer
- 関連ADR: 0007（オプトインAI司書）、0008（GitHub ReleasesでのAPK配布）

## Context

ADR 0007では、送信範囲・プレビュー・注入対策・上限制御・失敗分類・履歴削除を完全実装したうえで、プロバイダは端末内の決定的実装（`OnDeviceHeuristicLibrarian`）だけを提供した。回答は規則ベースで、自然文の相談体験には及ばない。

Issue #125は、蔵書データを端末外へ出さないまま自然文の提案を返すために、端末内LLMを`AiLibrarianProvider`の実装として追加することを求めている。同時にIssueは「Definition of Ready / 実装停止条件」として、runtime/model比較と数値上限の確定、新規依存・モデルライセンス・配布方法のmaintainer承認、既存の21,000,000 B AAB予算との両立を、実装着手の前提条件に置いている。

本ADRは、実測に基づいてその前提条件が**現時点では満たされていない**ことを記録し、承認が必要な事項を具体的な数値で提示する。あわせて、runtime選定に依存しない安全基盤（能力判定・モデル台帳・取得検証・prompt組み立て・出力検証・縮退・診断）を先に確定する。

## Decision Drivers

- 蔵書・質問文・prompt・回答・推論テレメトリを端末外へ出さないこと
- 書誌文字列を命令として扱わず、出力を厳格なschemaへ制約すること
- minSdk 23を維持したまま、非対応端末では取得も起動もさせないこと（fail-closed）
- 無料OSS（ADR 0008）として、runtimeとモデルのライセンスが再配布・NOTICE・SBOMと素直に両立すること
- GitHub ReleasesでAPKを直接配布するため、配布サイズが利用者のダウンロード負担に直結すること
- OOMを例外処理で回復する設計にせず、予算外端末ではLLMを起動しないこと

## 実測（2026-08-01、この作業ブランチで測定）

### AABサイズ

`./gradlew :app:bundleRelease`（R8有効・未署名）の`app-release.aab`実測。予算判定タスクは`:app:verifyReleaseBundleSize`で、AABファイル長を`21_000_000`バイトと比較する。

| 構成 | アプリのABI | LLM native libのABI | AAB (bytes) | 予算比 | baseline差 |
| --- | --- | --- | ---: | ---: | ---: |
| 現行main（依存追加なし） | 4 ABI | — | **18,409,960** | 87.7% | — |
| 現行main | arm64-v8aのみ | — | 11,610,678 | 55.3% | −6,799,282 |
| + `com.google.mediapipe:tasks-genai:0.10.35` | 4 ABI | 4 ABI | **60,660,835** | 288.9% | +42,250,875 |
| + `com.google.mediapipe:tasks-genai:0.10.35` | 4 ABI | arm64-v8aのみ | **28,691,368** | 136.6% | +10,281,408 |
| + `com.google.mediapipe:tasks-genai:0.10.35` | arm64-v8aのみ | arm64-v8aのみ | 21,885,472 | 104.2% | +3,475,512 |
| + `com.google.ai.edge.litertlm:litertlm-android:0.15.0` | 4 ABI | arm64-v8a + x86_64 | **39,126,540** | 186.3% | +20,716,580 |
| + `com.google.ai.edge.litertlm:litertlm-android:0.15.0` | 4 ABI | arm64-v8aのみ | **28,953,242** | 137.9% | +10,543,282 |

LLM native libraryをarm64-v8aだけに絞っても（`packaging { jniLibs.excludes }` で他ABIのライブラリを除外）、**+10.3〜10.5 MB**増える。現行mainの残余枠は2,590,040 Bしかなく、**どの構成でも既存予算に収まらない**。アプリ全体をarm64-v8aだけに絞ればMediaPipe構成が21,885,472 Bまで下がるが、これでも予算超過であり、armeabi-v7a・x86・x86_64の端末を丸ごと切り捨てることになる。

`litertlm-android` 0.15.0は`kotlin-reflect`（3.3 MB）と`gson`を推移的に持ち込むため、native library以外にもdex側の増分がある。

> 注: ADR 0007当時の基準値17,590,996 B（2026-07-29）はその後のmain更新で18,409,960 Bへ増えている。残余枠は当初想定より狭い。

### runtime候補の実測値

AARを取得して`jni/`配下のELFサイズと`AndroidManifest.xml`の`minSdkVersion`を実測した（すべて2026-08-01時点）。

| Runtime | 座標 / version | repository | ライセンス | minSdk | 同梱ABI | arm64-v8a .so (bytes) | 全ABI合計 (bytes) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **MediaPipe LLM Inference API** | `com.google.mediapipe:tasks-genai:0.10.35` | Google Maven | Apache-2.0 | 21 | v7a/arm64/x86/x86_64 | 26,625,664 | 108,775,920 |
| MediaPipe（旧版） | `com.google.mediapipe:tasks-genai:0.10.24` | Google Maven | Apache-2.0 | 24 | v7a/arm64/x86/x86_64 | 11,950,912 | 48,592,356 |
| **LiteRT-LM**（Googleの後継） | `com.google.ai.edge.litertlm:litertlm-android:0.15.0` | Google Maven | Apache-2.0 | 24 | arm64/x86_64のみ | 21,199,264 | 46,421,288 |
| ONNX Runtime（基盤のみ） | `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` | Maven Central | MIT | 24 | v7a/arm64/x86/x86_64 | 28,748,928 | 118,510,396 |
| ONNX Runtime GenAI | `com.microsoft.onnxruntime:onnxruntime-genai-android` | **未publish** | MIT | — | — | — | — |
| llama.cpp Androidバインディング | 該当なし | **未publish** | MIT | — | — | — | — |

補足（一次情報を確認、確認日2026-08-01）:

- MediaPipe LLM Inference APIの公式ドキュメントは「maintenance-only mode。新機能と最適化はLiteRT-LMへ集約する」と明記している（<https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android>）。新規採用は後継のLiteRT-LMが妥当。
- LiteRT-LMはarmeabi-v7aを同梱していない。32bit端末ではLLM経路を提供できない。
- `onnxruntime-genai-android`はMaven Centralにも Google Mavenにも存在せず（`maven-metadata.xml`が404）、公式手順もソースからのAARビルドのみ（<https://github.com/microsoft/onnxruntime-genai>）。本リポジトリはGradle dependency verification（sha256）で依存を固定しているため、公開artifactの無い依存は運用に載せられない。
- llama.cppのJNIバインディングでAndroid native libraryを同梱するMaven Central artifactは存在しない。最有力の`de.kherud:llama`は対応プラットフォームにAndroidを含まず、最終リリースは2025-06-20で13か月以上更新が無い。NDKによるソースビルドが必要で、これもdependency verificationの外側になる。

### モデルのライセンス（一次情報、確認日2026-08-01）

| モデル | ライセンス | 取得の可否 | 備考 |
| --- | --- | --- | --- |
| Gemma 3 / 3n | Gemma Terms of Use（OSI承認外） | Hugging Faceで`gated: manual`（未認証は401） | §3.1が利用規約の下流への継承と自EULAへの利用制限の組み込みを要求。§3.2でGoogleが遠隔での利用制限権を留保、§4.5で終了時の削除義務。Prohibited Use Policyは随時更新される |
| **Gemma 4** | **Apache-2.0** | ungated（匿名で200） | `litert-community/gemma-4-E2B-it-litert-lm` は 2,588,147,712 B |
| **Qwen2.5-0.5B / 1.5B-Instruct** | **Apache-2.0**（各repoのLICENSE） | ungated | 公式GGUF: 0.5B q4_k_m 491,400,032 B / 1.5B q4_k_m 1,117,320,736 B |
| Qwen2.5-3B-Instruct | `qwen-research`（非商用限定） | ungated | 採用不可 |
| `litert-community/Qwen2.5-0.5B-Instruct` | Apache-2.0 | ungated | `.task` は q8 のみ 546,660,344 B（int4は未公開） |

日本語能力について: Qwen2.5のモデルカードは29言語の一覧に日本語を含むが、これはシリーズ共通の記述で、機械可読frontmatterは`language: en`のみ。技術報告（arXiv:2412.15115v2）でJMMLUが載るのは7B以上の表だけで、0.5B/1.5B Instructを含むTable 10に多言語セクションは無い。**ベンダーによる1.5B以下の日本語スコアは公表されていない**ため、日本語品質は自前の実機評価でしか担保できない。

## Considered Options

### Option A: LiteRT-LM + Gemma 4 を採用し、AAB予算を引き上げる

**メリット**: runtimeもモデルもApache-2.0で、Gemma 4はungatedのため利用者の明示操作でそのまま取得できる。Googleが現在も開発しているため保守が続く見込みが高い。`.litertlm`形式が公式に配布されている。

**デメリット**: arm64-v8a版だけで21,199,264 Bのnative libraryが増え、AAB予算を大幅に超える。armeabi-v7aを同梱しないため32bit端末は対象外。minSdk 24でありAPI 23端末は対象外。モデル本体が2.5 GiB超で、端末の空き容量とダウンロード負担が大きい。日本語品質は未評価。

### Option B: LiteRT-LM + Qwen2.5-1.5B-Instruct を採用する

**メリット**: モデルがApache-2.0でサイズはGemma 4より小さい。

**デメリット**: LiteRT-LM向けの公式`.litertlm`が未提供で、`.task`はq8のみ（0.5Bで546 MB、1.5Bはさらに大きい）。変換は`litert-torch`（Linux専用）に依存し、変換済み重みを誰が配布・検証するかという新しい供給網の問題を生む。日本語スコアは未公表。native libraryのサイズ問題はOption Aと同じ。

### Option C: 独自にllama.cppをNDKでビルドして同梱する

**メリット**: native libraryを最小構成でビルドでき、GGUF（Apache-2.0のQwen2.5）を直接読める。ライセンスはMITで素直。

**デメリット**: 公開Maven artifactが無いため、sha256による依存検証・SBOM・ライセンスレポートの既存の仕組みから外れる。NDK・CMake・submoduleをCIへ持ち込み、供給網の検証責任を自リポジトリが全面的に負う。無料CIの制約下でのビルド時間も課題。

### Option D: runtime非依存の安全基盤だけを先に確定し、runtime採用は承認後に行う

**メリット**: Issueの「実装停止条件」に従いつつ、runtime選定に左右されない部分（能力判定、モデル台帳、取得の検証、原子的な導入、prompt組み立て、出力schema検証、縮退、診断、削除）を実運用経路とJVMテストで先に固定できる。配布サイズは1バイトも増えず、既存の予算・端末対応・ライセンス構成へ影響しない。承認後に必要なのは、台帳へのモデル追加と`LlmInferenceRuntime`実装の差し替えだけになる。

**デメリット**: この版では端末内LLMの回答は得られない。`LlmModelCatalog.models`が空である限り、AI司書は従来どおり規則ベースの回答を返す。

## Decision

**Option D**を採用する。Option A〜Cの採否は、以下をmaintainerが承認した時点で後続ADRまたは本ADRの改訂で確定する。

### 承認が必要な事項

1. **AABサイズ予算の引き上げ**。LiteRT-LM 0.15.0でLLM native libraryをarm64-v8aだけに絞った実測が28,953,242 B（MediaPipeでは28,691,368 B）で、**予算は30,000,000 B以上が必要**（現行21,000,000 Bから+43%）。GitHub Releasesの直接ダウンロード（ADR 0008）では、利用者のダウンロード量がそのまま約1.6倍になる。
2. **対応端末の縮小**。LLM経路はminSdk 24かつarm64-v8a（LiteRT-LMを採る場合）に限定される。minSdk 23自体は維持し、非対応端末では機能を出さない。
3. **モデル配布の扱い**。数百MiB〜2.5 GiBのモデルをHugging Faceから利用者の明示操作で取得する。取得は蔵書データを一切送らない別目的の通信として扱う。
4. **日本語品質の実機評価**。1.5B以下の日本語スコアはベンダー公表値が無く、採用可否は自前評価に依存する。

### 本ADRで確定する規範（runtime非依存）

1. **台帳（allowlist）**: `LlmModelCatalog`に載っていないモデルは取得も読み込みもできない。定義（`LlmModelDefinition`）はid・version・取得URL・期待サイズ・SHA-256・ライセンス・一次情報URL・確認日・minSdk・必要ABI・必要RAM・必要空き容量・context長・追加日・廃止日・既知の制約を必須で持つ。任意URLや未検証モデルを読み込む汎用プラグイン機構は提供しない。
2. **取得の境界**: `LlmModelUrlPolicy`がHTTPS・許可host・port 443・userInfo無し・fragment無し・`..`無しだけを許可し、定義の構築時点で不正なURLを弾く。`LlmModelDownloadSource`はredirectを追わず（`followRedirects(false)`・`followSslRedirects(false)`）、`Content-Length`が台帳と一致しない応答は本文を読まない。送信するのは台帳のURLとUser-Agentだけで、蔵書・質問文・回答は送らない。
3. **原子的な導入**: `FileLlmModelStore`は一時領域へ書き出しながらサイズ上限とSHA-256を計算し、両方が台帳と一致した場合だけrenameで有効化する。検証に失敗した場合は一時ファイルだけを消し、直前の検証済みモデルを保持する。旧versionの削除は新versionの有効化が確定してから行う。
4. **能力判定（fail-closed）**: `LlmCapabilityChecker`がAPI level・ABI・物理RAM・空き容量・low-RAM端末フラグ・runtimeの有無を検査し、一つでも満たさなければ`Unsupported`を返す。取得の可否も同じ条件で判定し、起動できない端末に数GiBをダウンロードさせない。OOMを例外処理で回復する設計にはしない。
5. **データ隔離**: `LlmPromptBuilder`は固定の`AI_LIBRARIAN_SYSTEM_INSTRUCTION`と固定の出力形式指示だけを連結し、質問文と書誌はJSONの値としてescapeして埋め込む。ADR 0007の規範4（未選択項目をpayloadへ出さない）はそのまま適用される。
6. **出力の厳格検証**: `LlmAnswerParser`は、既知のintent/reason、要求内のrefだけ、entries 1〜5件、1ブロックあたりref 8件以下、summary 400字以下・comment 200字以下・label 60字以下を満たす出力だけを受け入れる。一点でも外れれば全体を破棄し`INVALID_RESPONSE`にする（切り詰めて通さない）。
7. **縮退**: `FallbackAiLibrarianProvider`はLLM失敗時に`OnDeviceHeuristicLibrarian`の決定的で検証済みの回答へ切り替え、`AiLibrarianAnswer.degradedFrom`で縮退理由を伝える。未検証の部分回答は表示しない。利用者のキャンセルは縮退させずそのまま伝播する。
8. **診断**: 記録するのはmodel id/version・SHA-256の先頭16桁・runtime id/version・初期化時間・推論時間・prompt文字数・出力文字数・失敗分類だけ。端末内診断ログへ書けるのはallowlistの`DiagnosticCode`（`ON_DEVICE_LLM`カテゴリ）に限られ、質問文・書誌・回答は構造上入らない。
9. **削除**: `LlmModelStore.deleteAll()`でモデルとモデル由来ファイルを全削除できる。配置先は`noBackupFilesDir`配下で、OSクラウドbackup・D2D・エクスポート・同期の対象外。
10. **停止経路**: 台帳が空、能力判定が`Unsupported`、`LlmCapabilityChecker.canAcquire`がfalseのいずれでもLLM経路は起動しない。モデル配布元の停止・ライセンス変更・脆弱性が判明した場合は、台帳から該当versionを外す（または`retiredOn`を設定する）アプリ更新だけでヒューリステックへ戻せる。

## Consequences

**Positive**:

- 配布サイズ・端末対応・ライセンス構成・SBOMへ一切影響を与えないまま、供給網・注入・出力検証・縮退・診断・削除の全経路をJVMテストで固定できる。
- runtime採用時に必要な変更が、台帳へのモデル追加と`LlmInferenceRuntime`実装の差し替えに限定される。
- 予算・端末条件・モデルライセンスの判断材料が実測値として文書に残る。

**Negative / trade-offs**:

- この版では自然文の提案は得られない。`AiLibrarianAnswer.summary`・`entry.comment`は常にnullで、UIは従来どおりの表示になる。
- `UnavailableLlmRuntime`が常に`DEVICE_UNSUPPORTED`を返すため、LLM経路は必ず縮退する。

**Residual risks**:

- MediaPipeのGenAI経路がmaintenance-onlyであり、LiteRT-LMもversion 0.x系で、APIとサイズが今後も変動する。採用時点で再実測が必要。
- Hugging Faceのモデル配布は第三者の運用に依存する。台帳のURLとSHA-256は配布元の再アップロードで無効になり得る。
- モックのfake runtimeで検証できるのは契約・入力上限・出力検証・失敗分類・縮退・保存データ不変までで、実モデルの回答品質・OOM・発熱・電池消費は実機測定でしか確認できない。

## Rollback

`LlmModelCatalog.models`を空に保つ限り、LLM経路は起動しない。`domain/ai/llm`パッケージと`FileLlmModelStore`・`LlmModelDownloadSource`・`AndroidLlmDeviceProbe`・`DiagnosticsLlmTelemetrySink`を削除し、`AppContainer.aiLibrarianProvider`を`OnDeviceHeuristicLibrarian()`へ戻せば、ADR 0007の状態へ完全に戻る。Room schemaと本棚機能へ影響は無い（Room変更を伴わない設計）。導入済みモデルは`noBackupFilesDir/llm-models`配下だけにあり、アプリdataの消去または`deleteAll()`で消える。
