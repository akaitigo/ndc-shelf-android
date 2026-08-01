# ADR 0009: AI司書の端末内LLMは「安全基盤を先に確定し、runtime採用は配布サイズ予算の承認後」とする

- Status: Accepted（2026-08-01、maintainerが配布方式・対応端末・runtime/model・同意区分を承認）
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

> 注: ADR 0007当時の基準値17,590,996 B（2026-07-29）はその後のmain更新で18,409,960 Bへ増えている（残余枠は当初想定より狭い）。上表は#126（i18n）のmerge前に同一commitで測定した比較で、#126をmergeした本ブランチの実測は18,451,644 B。

### runtime候補の実測値

AARを取得して`jni/`配下のELFサイズと`AndroidManifest.xml`の`minSdkVersion`を実測した（すべて2026-08-01時点）。

| Runtime | 座標 / version | repository | ライセンス | minSdk | 同梱ABI | arm64-v8a .so (bytes) | 全ABI合計 (bytes) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **MediaPipe LLM Inference API** | `com.google.mediapipe:tasks-genai:0.10.35` | Google Maven | Apache-2.0 | 21 | v7a/arm64/x86/x86_64 | 26,625,664 | 108,775,920 |
| MediaPipe（旧版） | `com.google.mediapipe:tasks-genai:0.10.24` | Google Maven | Apache-2.0 | 24 | v7a/arm64/x86/x86_64 | 11,950,912 | 48,592,356 |
| **LiteRT-LM**（Googleの後継） | `com.google.ai.edge.litertlm:litertlm-android:0.15.0` | Google Maven | Apache-2.0 | 24 | arm64/x86_64のみ | 21,199,264 | 46,421,288 |
| ONNX Runtime（基盤のみ） | `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` | Maven Central | MIT | 24 | v7a/arm64/x86/x86_64 | 28,748,928 | 118,610,396 |
| ONNX Runtime GenAI | `com.microsoft.onnxruntime:onnxruntime-genai-android` | **未publish** | MIT | — | — | — | — |
| llama.cpp Androidバインディング | 該当なし | **未publish** | MIT | — | — | — | — |

補足（一次情報を確認、確認日2026-08-01）:

- MediaPipe LLM Inference APIの公式ドキュメントは「maintenance-only mode。新機能と最適化はLiteRT-LMへ集約する」と明記している（<https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android>）。新規採用は後継のLiteRT-LMが妥当。
- LiteRT-LMはarmeabi-v7aを同梱していない。32bit端末ではLLM経路を提供できない。
- `onnxruntime-genai-android`はMaven Centralにも Google Mavenにも存在せず（`maven-metadata.xml`が404）、公式手順もソースからのAARビルドのみ（<https://github.com/microsoft/onnxruntime-genai>）。本リポジトリはGradle dependency verification（sha256）で依存を固定しているため、公開artifactの無い依存は運用に載せられない。
- llama.cppのJNIバインディングでAndroid native libraryを同梱するMaven Central artifactは存在しない。最有力の`de.kherud:llama`は対応プラットフォームにAndroidを含まず、最終リリースは2025-06-20で13か月以上更新が無い。NDKによるソースビルドが必要で、これもdependency verificationの外側になる。

### 未解決の技術課題: redirect拒否とHugging Faceの配布形態

本PRの`LlmModelDownloadSource`はredirectを一切追わない。一方Hugging Faceの`/resolve/<rev>/<file>`は実体のCDN（`cdn-lfs-*.hf.co`）へ302で誘導し、CDNのURLには有効期限つきの署名queryが付く。したがって、

- 台帳へ`/resolve/`のURLを書くとredirect拒否で必ず失敗する
- 台帳へCDNの実体URLを書いても署名が失効する

runtimeを採用する版では、次のいずれかを別途決める必要がある。

1. **許可hostへの1段だけのredirect追従を明示的に実装する**（追従先も`ALLOWED_HOSTS`で検査し、追従回数を1回に固定する）
2. **redirectを返さない配布元を選ぶ**（リリース成果物として自リポジトリのGitHub Releasesへ再配布できるライセンスに限る）

いずれもURLポリシー・許可host一覧・`docs/NETWORK_BOUNDARY.md`の更新を伴う。現時点では取得経路が動作しないままだが、モデルが台帳に無いため実行されることはない。

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

配布物を2つのフレーバーへ分け、端末内LLMを含む版だけに別予算を与える。runtimeはLiteRT-LM、モデルはApache-2.0の`.litertlm`だけを台帳へ載せる。

### 1. 配布物の分割（Option A/B/Cの「予算を上げる」に代えて採用）

配布物はGitHub Releasesの署名付きAPK（ADR 0008）であり、**利用者が実際に
ダウンロードするのはAPK**である。AABはストア配布へ切り替える場合の予備成果物として
残すが、端末ごとに分割される前提のサイズなので配布サイズの正本にはしない。

| フレーバー | applicationId | 端末内LLM | APK予算 | APK実測 | AAB予算 | AAB実測 |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| `standard` | `dev.ndcshelf.app` | 含まない | 27,000,000 B | 25,381,403 B | 21,000,000 B（据え置き） | 18,507,420 B |
| `ai` | `dev.ndcshelf.app.ai` | LiteRT-LM（arm64-v8aのみ） | 34,000,000 B | 31,475,717 B | 24,000,000 B | 22,296,712 B |

- 既存利用者のダウンロード量は増えない。端末内LLMを使いたい利用者だけが大きい方を選ぶ。
- **applicationIdが異なるため、2つは別アプリとして扱われる。** 同時インストールでき、相互に上書き更新されない代わりに、**データは共有しない**。乗り換えにはエクスポート→インポートが必要で、この制約はREADMEとリリースノートへ明記する。
- 署名鍵は同一。`release.yml`は両方の署名を検証し、証明書のSHA-256が一致することも確認する。
- **`ai`フレーバーは`abiFilters`でarm64-v8aへ限定する。** LiteRT-LM 0.15.0のnative libraryはarm64-v8aとx86_64しか無く、x86_64はエミュレータ専用で実機の対象にならない。加えて、AI版は「Android 7.0以上・64bit Arm」を対象として配布するため、armeabi-v7a・x86のライブラリを積んでも端末内LLMは動かない。限定によりAPKは47,025,138 Bから31,475,717 Bへ**33%減った**。他ABIの端末は`standard`を使う（アプリ本体の機能に差は無い）。
- CIの通常ジョブは`standard`を主対象にして実行時間を増やさない（`verifyRoborazziStandardDebug` / `lintStandardDebug` / `assembleStandardDebug` / `connectedStandardDebugAndroidTest`）。ただし`ai`専用のソースセットが未検査のままmainへ入らないよう、`assembleAiDebug`と`lintAiDebug`は毎PRで実行する。releaseビルドとサイズ検証は`release.yml`で行う。

### 2. 対応端末

LLM経路は**API 24以上かつarm64-v8a**に限定する。アプリ本体のminSdk 23は維持し、非対応端末では取得も初期化も行わない（`LlmCapabilityChecker`）。`ai`フレーバーの`AndroidManifest.xml`だけに`tools:overrideLibrary="com.google.ai.edge.litertlm"`を置き、`standard`へは影響させない。

### 3. runtimeとモデル

**runtime: LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.15.0`（Apache-2.0、Google Maven）。**
MediaPipe LLM Inference APIは公式にmaintenance-onlyで後継がLiteRT-LM、ONNX Runtime GenAIはAndroid artifactが未publish、llama.cppはMaven artifactが存在しない、という比較結果に従う。

**モデル: `litert-community/Qwen3-0.6B` の `qwen3_0_6b_mixed_int4.litertlm`（Apache-2.0、ungated）。**

| 項目 | 値 | 確認方法（2026-08-01実測） |
| --- | --- | --- |
| サイズ | 497,664,000 B | HF API `tree/main` の `size` |
| SHA-256 | `b1baab462f6be49d70eada79d715c2c52cd9ece0cad00bddf6a2c097d23498e9` | 同 `lfs.oid` |
| ライセンス | apache-2.0 | HF API `cardData.license` |
| gated | false | HF API `gated` |

**Qwen2.5-0.5B/1.5B-Instructを採らなかった理由**: `litert-community/Qwen2.5-0.5B-Instruct`には`.litertlm`が存在せず（`.task`と`.tflite`のみ）、LiteRT-LMでは読めない。`Qwen2.5-1.5B-Instruct`の`.litertlm`はq8で1,597,931,520 Bあり、0.6Bのint4（497 MB）より端末負担が大きい。

**日本語について**: 配布元・上流いずれのモデルカードにも、日本語品質に関する公式な主張は無い（上流Qwen3は「100+ languages」と記すが日本語を名指ししていない）。日本語を明示的に謳う`.litertlm`は`LFM2.5-1.2B-JP`（非OSIライセンス）と`TinySwallow-1.5B-Instruct`（Gemma Termsが付随）だけで、どちらも本プロジェクトの方針では採用できない。**日本語品質は保証せず、その旨をモデル管理画面と本ADRへ明記する。**

### 4. 同意の区分

`ConsentPurpose.MODEL_DOWNLOAD`を新設し、`AI_LIBRARIAN`（端末内推論・通信なし）とは別目的にする。送信先・送信内容（台帳のモデルURLとUser-Agentのみ）・保存期間・第三者提供を既存の同意画面と同じ様式で表示する。同意していない場合、モデル取得は一度も開始しない。端末内ファイルからの導入は通信を伴わないため、この同意を必要としない。

### 5. リダイレクト（未解決課題の解消）

Hugging Faceの`/resolve/`は署名付きCDNへ302で誘導する（実測の`Location`ホストは`us.aws.cdn.hf.co`）。ホストはリージョンとストレージ方式で変わり得るため、**許可ドメイン（`hf.co` / `huggingface.co`とそのサブドメイン）内に限って1回だけ追従する**実装を採る。追従先はscheme・port・userInfo・path traversalを台帳URLと同じ基準で検査し、CDNの署名queryだけを追加で許可する。2回目のリダイレクトは失敗にする。

### 本ADRで確定する規範（runtime非依存）

1. **台帳（allowlist）**: `LlmModelCatalog`に載っていないモデルは取得も読み込みもできない。定義（`LlmModelDefinition`）はid・version・取得URL・期待サイズ・SHA-256・ライセンス・一次情報URL・確認日・minSdk・必要ABI・必要RAM・必要空き容量・context長・追加日・廃止日・既知の制約を必須で持つ。id・version・fileNameは端末内のパス組み立てへ入るため、区切りと親参照を許さない文字集合へ制限する。任意URLや未検証モデルを読み込む汎用プラグイン機構は提供しない。
2. **取得の境界**: `LlmModelUrlPolicy`がHTTPS・許可host・port 443・userInfo無し・query無し・fragment無し・`..`無しだけを許可し、定義の構築時点で不正なURLを弾く。`LlmModelDownloadSource`はredirectを追わず（`followRedirects(false)`・`followSslRedirects(false)`）、`Content-Length`が台帳と一致しない応答は本文を読まない。送信するのは台帳のURLとUser-Agentだけで、蔵書・質問文・回答は送らない。
3. **原子的な導入**: `FileLlmModelStore`は一時領域へ書き出しながらサイズ上限とSHA-256を計算し、両方が台帳と一致した場合だけrenameで有効化する。renameは既存ファイルを不可分に置換するため事前削除しない（renameが失敗しても旧モデルが残る）。検証に失敗した場合は一時ファイルだけを消し、直前の検証済みモデルを保持する。旧versionの削除は新versionの有効化が確定してから行う。
3-1. **ロード前の再検証**: 導入後にファイルが差し替えられていないことを、`OnDeviceLlmLibrarian`がプロセスごとに1度だけ全バイトのSHA-256で確認する。不一致ならモデルを削除し、`MODEL_CORRUPTED`として縮退する。この所要時間は`initializationMillis`へ含める。
4. **能力判定（fail-closed）**: `LlmCapabilityChecker`がAPI level・ABI・物理RAM・空き容量・low-RAM端末フラグ・runtimeの有無を検査し、一つでも満たさなければ`Unsupported`を返す。取得の可否も同じ条件で判定し、起動できない端末に数GiBをダウンロードさせない。OOMを例外処理で回復する設計にはしない。
5. **データ隔離**: `LlmPromptBuilder`は固定の`AI_LIBRARIAN_SYSTEM_INSTRUCTION`と固定の出力形式指示だけを`systemInstruction`へ、質問文と書誌はJSONの値としてescapeした`userMessage`へ入れる。LiteRT-LMの`Conversation`はこの2つを別roleとして扱うため、書誌文字列がsystem roleへ混ざらない。ADR 0007の規範4（未選択項目をpayloadへ出さない）はそのまま適用される。promptが上限（6,000文字）を超える要求は組み立てを拒否し、規則ベースの回答へ縮退する（切り詰めない）。
6. **出力の厳格検証**: `LlmAnswerParser`は、既知のintent/reason、要求内のrefだけ、entries 1〜5件、1ブロックあたりref 8件以下、summary 400字以下・comment 200字以下・label 60字以下を満たす出力だけを受け入れる。一点でも外れれば全体を破棄し`INVALID_RESPONSE`にする（切り詰めて通さない）。自由文はISO制御文字とU+2028/U+2029を空白へ置換し、全角空白とNBSPを含む連続空白を1つへ畳む。
7. **縮退**: `FallbackAiLibrarianProvider`はLLM失敗時に`OnDeviceHeuristicLibrarian`の決定的で検証済みの回答へ切り替え、`AiLibrarianAnswer.degradedFrom`で縮退理由を伝える。未検証の部分回答は表示しない。利用者のキャンセルは縮退させずそのまま伝播する。
8. **診断**: 記録するのはmodel id/version・SHA-256の先頭16桁・runtime id/version・初期化時間・推論時間・prompt文字数・出力文字数・失敗分類だけ。端末内診断ログへ書けるのはallowlistの`DiagnosticCode`（`ON_DEVICE_LLM`カテゴリ）に限られ、質問文・書誌・回答は構造上入らない。モデル導入結果と整合性確認の失敗は`DiagnosticsLoggingLlmModelStore`が、ヒューリスティックへの縮退は`FallbackAiLibrarianProvider`の通知経路が、それぞれ同じallowlistへ記録する。
9. **削除**: `LlmModelStore.deleteAll()`でモデルとモデル由来ファイルを全削除できる。配置先は`noBackupFilesDir`配下で、OSクラウドbackup・D2D・エクスポート・同期の対象外。
10. **停止経路**: 台帳が空、能力判定が`Unsupported`、`standard`フレーバーのいずれでもLLM経路は起動しない。モデル配布元の停止・ライセンス変更・脆弱性が判明した場合は、台帳から該当versionを外す（または`retiredOn`を設定する）アプリ更新だけでヒューリスティックへ戻せる。runtime側の問題は`ai`フレーバーの`PlatformLlmRuntime`を`UnavailableLlmRuntime`へ差し替えるだけで止められる。

## Consequences

**Positive**:

- 既存利用者（`standard`）の配布サイズ・端末対応・ライセンス構成は変わらない。予算21,000,000 Bを据え置ける。
- 端末内LLMが必要な利用者だけが29 MBの`ai`を選べる。両者は同時インストールでき、片方の不具合がもう片方へ波及しない。
- runtime差し替えは`ai`フレーバーの`PlatformLlmRuntime`だけ、モデル差し替えは台帳だけで済む。
- 供給網・注入・出力検証・縮退・診断・削除の全経路をJVMテストで固定できる。

**Negative / trade-offs**:

- **`standard`と`ai`はapplicationIdが異なる別アプリで、データを共有しない。** 乗り換えにはエクスポート→インポートが必要。この制約を知らずに`ai`を入れると蔵書が空に見える。
- リリース成果物とCIジョブが増える（APK/AAB/mapping/署名記録が各2つ）。
- LLM経路はAPI 24以上のarm64-v8a端末に限られる。armeabi-v7a・x86_64端末では`ai`を入れても規則ベースのまま。
- モデルの取得に約475 MBのダウンロードと約1 GBの空き容量が要る。

**Residual risks**:

- **日本語品質が未知。** 採用したQwen3-0.6Bは配布元・上流とも日本語について公式の主張が無く、0.6Bという規模から誤答・指示の取りこぼしが起こりやすい。UIとモデル台帳へ明記し、回答には常に参照本と不確実性の注記を添える。
- LiteRT-LMは0.x系で、APIとnative libraryのサイズが今後も変動し得る。version更新時は再実測が必要。
- Hugging Faceの配布は第三者の運用に依存する。台帳のURL・サイズ・SHA-256は再アップロードで無効になり得る。CDNのホスト振り分けが`hf.co`ドメイン外へ変わると取得が失敗する（fail-closed）。
- モデルのSHA-256はHugging Face APIの`lfs.oid`から取得した値で、**開発環境でモデル全体をダウンロードして再計算した値ではない**。不一致の場合は取得が必ず失敗する側へ倒れる。
- fake runtimeで検証できるのは契約・入力上限・出力検証・失敗分類・縮退・保存データ不変まで。**実モデルの回答品質・OOM・発熱・電池消費・実機の通信監査は実機測定でしか確認できない。**

## Rollback

- **モデルだけを止める**: `LlmModelCatalog.models`から定義を外す。`ai`フレーバーでも能力判定が`NO_MODEL_AVAILABLE`になり、規則ベースへ縮退する。
- **runtimeを止める**: `app/src/ai/.../PlatformLlmRuntime`の`runtime`を`UnavailableLlmRuntime`に、`isAvailable()`を`false`に戻す。
- **配布を止める**: `ai`フレーバーの成果物をリリースへ添付しない。`standard`は影響を受けない。
- **全部戻す**: `productFlavors`と`domain/ai/llm`パッケージ、`data/local/FileLlmModelStore`・`data/remote/LlmModelDownloadSource`・`data/local/AndroidLlmDeviceProbe`・`data/diagnostics/DiagnosticsLlmTelemetrySink`・`LlmModelViewModel`・`LlmModelScreen`を削除し、`AppContainer.aiLibrarianProvider`を`OnDeviceHeuristicLibrarian()`へ戻す。Room schemaと本棚機能へ影響は無い（Room変更を伴わない設計）。`ConsentPurpose.MODEL_DOWNLOAD`の記録は残るが、目的が未提供へ戻れば同意画面の一覧から外す。

導入済みモデルは`noBackupFilesDir/llm-models`配下だけにあり、アプリdataの消去または`deleteAll()`で消える。
