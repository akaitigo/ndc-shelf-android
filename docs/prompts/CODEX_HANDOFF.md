# Codexへの引き継ぎプロンプト

以下をそのままCodexへ貼る。作業ディレクトリは `~/code/projects/ndc-shelf-android`。

---

ndc-shelf-android の開発を引き継いでほしい。プライバシー重視のAndroid蔵書アプリで、
ISBNバーコードをスキャンして日本十進分類法（NDC）で蔵書を整理する。

## まず読むもの

作業を始める前に、この順で読むこと。**推測で動かず、書いてある実測値を前提にする。**

1. `docs/HANDOFF.md` — 作業状態・次の一手・踏んだ地雷。**最重要**
2. `AGENTS.md` — ビルド手順と実装ルール
3. `docs/ON_DEVICE_LLM_FINDINGS.md` — 端末内LLMの実機実測（次のタスクの前提）

## いまの状態

- 全Issueを完了済み。オープンなのは **#55（v1.0最終ゲート）と #58（ロードマップ）だけ**
- **配布はまだ一度も行われていない。v0.6.0 が初の署名付きAPK配布**になる
- タグ `v0.6.0` とGitHub Release（pre-release）は作成済み。
  **Release APK ワークフローがオーナーの承認待ちで止まっている**

## 進め方の合意（この順）

**A. v0.6.0 を配布可能にする** ← 承認待ちで停止中

承認はオーナーが押す。**エージェントは承認しない。**
承認後、`gh release view v0.6.0 --json assets` でAPKの添付を確認し、
`docs/releases/V1.0_RELEASE_CHECKLIST.md` の該当項目を埋める。

**C. 書籍の内容情報をAPI取得する** ← 次に着手する

蔵書アプリのAI司書は、書名だけでは推測でしか答えられない。**内容紹介が入って初めて
意味のある選書ができる**。オーナーは写真OCRではなくAPI取得を希望している。

検証済みの前提（`docs/ON_DEVICE_LLM_FINDINGS.md` に実蔵書での確認結果あり）:

- NDL・openBD・Open Library は内容紹介も目次も持たない
- **目次を返すAPIは実質存在しない**。内容紹介だけで設計する
- 候補は楽天ブックスとGoogle Books。どちらも**APIキーが要る**

**最初にオーナーへ依頼すること**: 両方のキーを発行してもらう。
受け取ったら実蔵書での充足度（ヒット率・内容紹介がある冊数・平均文字数・書影の有無）を
実測し、**数字を見せてから**提供元を決める。手順は `docs/HANDOFF.md` の末尾にある。

決定後の実装:

- `ConsentPurpose` を新設する（送信先が増えるため。既存の様式に合わせる）
- 取得した内容紹介を端末内へ保存する（Room。migrationとexported schemaとテストを伴う）
- APIキーはAndroidのパッケージ名と署名証明書で制限する。リポジトリへ直接書かない

**B. 端末内LLMを再設計する** ← Cの完了後

`docs/ON_DEVICE_LLM_FINDINGS.md` の「再設計の方針」に従う。要点だけ:

- ランタイムの `ThinkingConfig` / `ResponseFormat` / tool calling を使う。
  **現実装はどれも使わず、プロンプトの文章でJSONを依頼している**
- 蔵書は書誌JSONではなく1冊1行へ。実測で4,258文字は失敗、483文字は7.6秒で成功
- 先に固定の評価セット（100〜300問）を作る。作らないと変更が改善なのか判断できない

## 絶対に外してはいけない制約

- **コンセプト**: 「自分が持っている本すべてを元に助言する司書」。
  候補を事前に数冊へ絞る設計でコンセプトを崩さない。
  ただし「全件をpromptへ文字列で載せる」ことと同一視しなくてよい。
  **全件が検索・スコアリングの対象であること**で担保する
- **完全オフライン推論**。蔵書・質問文・回答を端末外へ出さない
- **蔵書データは端末内のみ**
- ユーザー向け文言とプロジェクト文書は日本語

## 作業のしかた

- **提案で終わらせず、実装・テスト・CI通過・マージまで完了させる**
- 変更にはテストを伴う。スタブやTODOを残さない
- **マージ前に全チェックが pass/skipping であることを件数で確認する**（目視で済ませない）
- 依存を変えたら `gradle/verification-metadata.xml` を再生成する
- **実機の操作・APIキーの発行・リリースの承認はオーナーが行う。** 必要なら明示的に依頼する
- 実測して分かったことは、コミット前に `docs/HANDOFF.md` か
  `docs/ON_DEVICE_LLM_FINDINGS.md` へ落とす。**セッションが切れても失われないように**

## 検証コマンド

```bash
export JAVA_HOME="$HOME/.local/share/mise/installs/java/temurin-17.0.20+8"
./gradlew verifyV06ReleaseConfiguration verifyBackupPolicy verifyLicenseReport \
  :app:cyclonedxDirectBom verifyRoborazziStandardDebug lintStandardDebug \
  assembleStandardDebug assembleAiDebug lintAiDebug
.github/scripts/verify-translations.sh
git diff --exit-code -- app/schemas
```

まず `docs/HANDOFF.md` を読み、いまの状態を自分で確認してから、
何から着手するかを述べてほしい。
