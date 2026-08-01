# 端末内LLMの実機実測（2026-08-01）

Pixel 7 実機で `#125` の端末内LLMを検証した記録。**推測ではなく実測値**だけを載せる。
再現手段は `app/src/androidTestAi/java/dev/ndcshelf/app/data/llm/` の計測テスト群。

## 測定環境

| 項目 | 値 |
| --- | --- |
| 端末 | Pixel 7 / Tensor G2 / arm64-v8a / RAM 7.6 GB |
| OS | Android 17（API 37） |
| runtime | `com.google.ai.edge.litertlm:litertlm-android:0.15.0` |
| model | `litert-community/Qwen3-0.6B` の `qwen3_0_6b_mixed_int4.litertlm`（497,664,000 B） |
| 実行 | `adb shell am instrument`（UIを介さない） |

## 結論

**動くが、実装を変えないと実用にならない。** 変更すれば全27冊を7.6秒で処理できる。

## 確定した実測値

### 速度

| 条件 | 実測 |
| --- | ---: |
| モデル初期化（warm） | 4,295 ms |
| decode | 12.9 tok/s（配布元公称どおり） |
| **prefill** | **約55 tok/s（公称576 tok/sの1/10）** |
| 時間の近似式（出力8トークン固定） | `2.5秒 + 18ms × 入力文字数` |

**prefillが支配的**。入力を短くすることが最も効く。

### 冊数と表現形式（同じ27冊）

| 1冊の表現 | prompt長 | 結果 |
| --- | ---: | --- |
| 書名＋著者＋出版社＋出版年＋NDCのJSON | 4,258文字 | **context超過で即死**（`LiteRtLmJniException`、0.03秒） |
| `b1｜書名` の1行 | 483文字 | **7,644 ms で妥当な回答** |

**削るべきは冊数ではなく、1冊あたりの冗長な書誌情報。**

### 効かなかったもの

| 施策 | 結果 |
| --- | --- |
| `Backend.CPU()` のthreadCount（null/2/4/6/8） | **差が出ない**（27冊で39.1秒 vs 39.2秒） |
| `maxNumTokens` 2048 → 8192 | 時間・メモリとも**不変** |
| GPU（`Backend.GPU()`） | **`Can not find OpenCL library on this device`**。Tensor G2はOpenCL非公開 |
| no-think専用artifact（`Qwen3-0.6B-int4` ekv1280） | **逆に遅い**（600文字で25.6秒 vs 既定11.8秒） |

### メモリ

peak RSS **2,357,020 KB（2.3 GB）**。context設定では変わらない。
計測中に `com.android.settings` など他アプリがOOMで終了した。

## モデル調査（一次情報で確認）

| モデル | ライセンス | gated | サイズ | 判定 |
| --- | --- | --- | ---: | --- |
| Qwen3-0.6B mixed_int4 | Apache-2.0 | false | 497,664,000 B | 現行。context **2,048**（台帳の8,192は誤り） |
| Qwen3-0.6B-int4 nothink | Apache-2.0 | false | 347,251,840 B | context 1,280。**実測で遅く不採用** |
| **LFM2.5-1.2B-JP** | `lfm-open-license-v1.0` | false | 735,999,360 B | **日本語特化。runtime 0.15.0で動かない**<br>`NOT_FOUND: The given map is missing some output TensorBuffers` |
| **TinySwallow-1.5B-Instruct** | **Apache-2.0** | false | 1,567,604,736 B | **日本語特化。未検証** |
| Gemma3-1B-IT / gemma-4系 | Gemmaライセンス | — | — | Apache-2.0でないため要判断 |

**選定の誤り**: 汎用のQwen3-0.6Bを選び「日本語品質の保証がない」を制約として記載していたが、
同じ配布元に日本語特化モデルが存在した。次に検討するなら **TinySwallow（Apache-2.0）**。

## 実装の欠陥（実測で判明）

| # | 欠陥 | 状態 |
| --- | --- | --- |
| 1 | `LlmModelCatalog` の `contextTokens = 8192`（配布元の実際は **2,048**） | 未修正 |
| 2 | `LlmPromptLimits.MAX_PROMPT_CHARS = 6,000` がcontext 2,048に収まらない | 未修正 |
| 3 | `cache/llm-runtime` を誰も作らず、XNNPack weight cacheが毎回失敗 | **修正済み** |
| 4 | 例外を握り潰し、フィールドで原因追跡できない | **修正済み**（クラス名と位置を記録） |
| 5 | system instructionの `{"id":"bN"}` を、モデルがプレースホルダーのまま返す | 未修正 |
| 6 | 推論モードを止めていないため出力の大半が `<think>` に消える | 未修正 |

## 使っていなかったランタイム機能

`litertlm-android:0.15.0` は次を持つが、現在の実装は**どれも使っていない**。
プロンプトの文章で「JSONだけ出力してください」と依頼する実装になっている。

| API | 用途 |
| --- | --- |
| `ResponseFormat.json(schema)` / `.regex(pattern)` | **出力形式を文法で強制**。#5の形式崩れが根本解決しうる |
| `ConversationConfig(enableResponseFormat = true)` | 上記の有効化 |
| `ThinkingConfig(enableThinking, thinkingTokenBudget)` | **推論モードの正式な制御**（`/no_think` の文字列付加は不要） |
| `ConversationConfig(tools = ..., automaticToolCalling = true)` | **LLM自身に検索させる**。`@Tool` / `@ToolParam` アノテーションで定義 |
| `Conversation.RECURRING_TOOL_CALL_LIMIT` | tool呼び出しループの上限（ランタイムが回す） |
| `LoraConfig` / `Channel` / `extraContext` | 未調査 |

**tool callingが使えると、蔵書数の制約が消える**（promptに載るのは検索結果だけになる）。

## 書籍の内容情報（内容紹介・目次）の入手性

実際の蔵書のISBNで検証した。

| API | 鍵 | 内容紹介 | 目次 | 備考 |
| --- | --- | :---: | :---: | --- |
| NDL（現行） | 不要 | ❌ | ❌ | SRU・OpenSearch両方で確認。返るのは配架場所のみ |
| openBD | 不要 | ❌ | ❌ | 出版社の登録依存。技術書は空 |
| Open Library | 不要 | ❌ | ❌ | 和書が1冊もヒットしない |
| Google Books | 必要 | ○ | ❌ | 鍵無しではIPごと429 |
| 楽天ブックス | 必要 | ○ | △ | 和書のカバレッジ良好。出典表示が規約上必須 |

**目次を持つAPIは実質存在しない。** 内容紹介はGoogle Booksか楽天のいずれかで、どちらも鍵が要る。

## 計測ハーネス

`app/src/androidTestAi/java/dev/ndcshelf/app/data/llm/` に置く（LiteRT-LMへ直接依存するため
aiフレーバー専用のソースセット。standardのテストビルドには含まれない）。
モデル未取得・非対応ABI・runtime非同梱の端末では `assumeTrue` でskipするため、
CIのエミュレータでは常にskipされる。

| テスト | 用途 |
| --- | --- |
| `OnDeviceLlmMeasurementTest` | 初期化・推論時間・peak RSSの基本計測 |
| `OnDeviceLlmSweepTest` | backend × context × 出力上限 × thinking の総当たり |
| `OnDeviceLlmContractTest` | 出力契約（厳格JSON / 1行1件 / IDのみ / 自然文）の比較 |
| `OnDeviceLlmEndToEndTest` | 製品と同じ経路（`LlmPromptBuilder` → 推論 → `LlmAnswerParser`） |
| `OnDeviceLlmThreadingTest` | threadCountの効果 |
| `OnDeviceLlmPrefillTest` | artifact別のprefillの傾き |
| `OnDeviceLlmJapaneseTest` | 日本語特化モデルとの比較 |

実行例:

```bash
# connectedAiDebugAndroidTest は実行後にアプリをアンインストールするため使わない
./gradlew :app:assembleAiDebug :app:assembleAiDebugAndroidTest
adb install -r app/build/outputs/apk/ai/debug/app-ai-debug.apk
adb install -r app/build/outputs/apk/androidTest/ai/debug/app-ai-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class dev.ndcshelf.app.data.llm.OnDeviceLlmPrefillTest \
  dev.ndcshelf.app.ai.test/androidx.test.runner.AndroidJUnitRunner
adb shell "cat /sdcard/Android/data/dev.ndcshelf.app.ai/files/llm-prefill.txt"
```

結果はlogcatではなく `getExternalFilesDir(null)` のファイルへ書く（logcatはバッファが流れる）。

## 実機検証で分かった運用上の注意

- **`connectedAndroidTest` は実行後にアプリをアンインストールする。** 取得済みモデルも消える。
  繰り返し計測するときは `adb install` ＋ `am instrument` を直接使う
- モデルはPCから `adb push` ＋ `run-as` で配置できる。UI操作を経ずに計測環境を復元できる
- `adb shell "run-as ... cat"` はバイナリを壊す（改行変換）。**`adb exec-out` を使う**

## 再設計の方針（B）

実測とCodexとの議論から、次の順で作り直す。**コンセプト（所有する本すべてを踏まえて助言する）を
崩さないこと**が制約。

### 使うべきランタイム機能

1. **`ThinkingConfig(enableThinking = false)`** — 推論モードを正式に止める。
   実測で11.9秒→4.2秒。文字列 `/no_think` の付加は不要になる
2. **`ResponseFormat.json(schema)` + `enableResponseFormat`** — 出力形式を文法で強制する。
   プロンプトで「JSONだけ出力してください」と依頼する現在の実装は0.6Bには守れない
   （`{"id":"bN"}` のプレースホルダーをそのまま返した）
3. **`tools` + `automaticToolCalling`** — LLM自身に蔵書検索させる。
   `@Tool` / `@ToolParam` アノテーションでKotlinの関数を公開できる。
   **これが使えると蔵書数の制約が消える**（promptに載るのは検索結果だけ）

### 蔵書表現

書誌JSONではなく1冊1行へ。`b1|書名` で27冊483文字（実測7.6秒）。
内容紹介を持たせる場合は全件をpromptへ載せられないため、
**全件を端末内で検索 → 質問に効く数冊を中身ごと渡す**形にする。
全件が検索対象なのでコンセプトは保たれる。

### 先に作るもの

**固定の評価セット（100〜300問）**。形式成功率・選択の妥当性・p50/p95・peak RSSを
同時に比較できないと、モデル変更やfew-shot追加が改善なのか判断できない。
計測ハーネスは揃っているので、評価セットを足せば回せる。

### 未検証で残っている選択肢

- **TinySwallow-1.5B-Instruct**（Apache-2.0・日本語特化・1.57 GB）
- **llama.cpp の自前ビルド** — Maven非公開でも、自分でビルドした `.so` の同梱は依存検証の
  対象外。GGUFが使えるためモデルの幅が最大になり、ARM最適化でprefillが改善する可能性がある。
  採用ゲートを先に決めること（prefill 1.5倍以上、peak PSS 25%減または1.5 GB未満など）
- **MediaPipe `tasks-genai`**（Google Maven・`.task`形式）。公式にmaintenance-onlyだが動作は実測済み
- **ONNX Runtime**（`com.microsoft.onnxruntime:onnxruntime-android:1.22.0` はMaven Centralにある）。
  生成ループを自前で書く必要があるが、モデルの幅は最も広い
