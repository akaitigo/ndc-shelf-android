# 引き継ぎ（2026-08-02 時点）

作業を引き継ぐエージェントは、まずこの文書を読む。**古くなったら更新すること。**

## いまの状態

全Issueを完了し、**オープンなのは #55（v1.0最終ゲート）と #58（ロードマップ）の2件だけ**。
#58 は #55 の完了で閉じる。

配布はまだ一度も行われていない。**v0.6.0 が初の署名付きAPK配布**になる。

### 進行中: v0.6.0 の配布

タグ `v0.6.0`（`2c889ed`）とGitHub Release（pre-release）は作成済み。
**Release APK ワークフローがオーナーの承認待ちで止まっている。**

- 承認先: `gh run list --workflow "Release APK"` で最新のrunを見る
- 承認するとAPK・SHA256SUMS・署名情報・mapping・SBOM・NOTICESがReleaseへ添付される
- **承認はオーナーが行う。エージェントは押さない**

承認後にやること:

1. `gh release view v0.6.0 --json assets` でAPKの添付を確認する
2. オーナーが実機へ導入し、動作を確認する
3. `docs/releases/V1.0_RELEASE_CHECKLIST.md` の未チェック項目を埋める

## 次にやること（この順で合意済み）

### A. v0.6.0 を配布可能にする ← 承認待ちで停止中

### C. 書籍の内容情報をAPI取得する

LLMの選書品質は書名だけでは頭打ち。**内容紹介が入って初めて意味のある提案になる**。

**オーナーからAPIキーを受け取る必要がある**（エージェントは発行できない）。

- 楽天ブックス: https://webservice.rakuten.co.jp/ でアプリIDを発行
- Google Books: Google Cloud Console で Books API を有効化しAPIキーを発行

キーを受け取ったら、実蔵書での充足度を実測してから提供元を決める。
計測スクリプトの雛形は本文書末尾に置いた。

**検証済みの前提**（`docs/ON_DEVICE_LLM_FINDINGS.md` に詳細）:

- NDL・openBD・Open Library は内容紹介も目次も持たない（実蔵書のISBNで確認済み）
- **目次を返すAPIは実質存在しない**。内容紹介だけで設計する
- 鍵はAndroidのパッケージ名と署名証明書で制限できる。OSSでも公開して問題ない
- 送信先が増えるので **`ConsentPurpose` を新設する**（既存の様式に合わせる）

### B. 端末内LLMを再設計する

`docs/ON_DEVICE_LLM_FINDINGS.md` の「再設計の方針」に従う。**Cの完了が前提**。

## 絶対に外してはいけない制約

- **コンセプト**: 「自分が持っている本すべてを元に助言する司書」。
  候補を事前に数冊へ絞る設計でコンセプトを崩さない。
  ただし「全件をpromptへ文字列で載せる」ことと同一視しなくてよい。
  **全件が検索・スコアリングの対象であること**で担保する
- **完全オフライン推論**。蔵書・質問文・回答を端末外へ出さない
- **蔵書データは端末内のみ**。クラウドバックアップ・端末間転送の対象外
- 依存は Maven 公開かつ sha256 検証。ただし**自前ビルドの `.so` 同梱は対象外**
- ユーザー向け文言とプロジェクト文書は日本語。コード識別子は英語

## 踏んだ地雷（同じ失敗を繰り返さないため）

### CI

- **`verify-action-pins.sh` と OSVスキャンが、検査対象ゼロのまま成功し続けていた。**
  ゲートを足すときは「対象が無いときに落ちる」ことを必ず確認する
- `upload-artifact` に複数 path を渡すと**共通の親ディレクトリが剥がされる**。
  受け取り側のパスが暗黙に決まるため、平坦な1ディレクトリへ集約する
- ripgrep は GitHub runner に無い。CIスクリプトは POSIX の grep/sed/awk で書く
- `emulator-runner` の `script:` は行ごとに実行される。行継続 `\` で分割すると
  意図しない行が単独実行される。**1行で書く**
- AboutLibraries の生成タスクをフレーバー間で並列に走らせると、
  ライセンス本文が片方だけ空になる。`mustRunAfter` で直列化してある

### ビルド

- **JDK 17 必須**。GraalVM 21 は `JdkImageTransform` で落ちる
- **AABにネイティブデバッグシンボルが入ると9.2MB増える**。
  抽出にはNDKが要るため、NDKの無い環境では生成されず**ローカルとCIで計測が食い違う**。
  `release { ndk { debugSymbolLevel = "none" } }` で無効化済み
- **利用者がダウンロードするのはAPK**。AABはストア配布用の予備。
  実測でAPKはAABの約1.4倍。両方に予算ゲートがある
- LiteRT-LM に依存するコードは `ai` フレーバー専用のソースセットへ置く。
  共通の `androidTest` へ置くと `standard` のテストビルドが壊れる

### 実機検証

- **`connectedAndroidTest` は実行後にアプリをアンインストールする**。
  取得済みモデル（475MB）も消える。繰り返し計測は `adb install` ＋ `am instrument` を使う
- **`adb shell "run-as ... cat"` はバイナリを壊す**（改行変換）。`adb exec-out` を使う
- `adb shell input text` は日本語IMEに変換される。ASCIIでもスペースは `%s` でエスケープ
- ワイヤレスデバッグのIPがVPN側になっていると接続できない。VPNを切ってもらう
- 開発端末の `v0.1.2` はデバッグ鍵で署名されている。**リリース鍵のAPKへは上書き更新できない**
  （外部利用者への影響は無い。v0.1.2 は配布していないため）

### エージェント運用

- **調査を深めるほどゴールが漂流する**。実測は必ずドキュメントへ落としてからコミットする。
  この文書と `docs/ON_DEVICE_LLM_FINDINGS.md` がその受け皿
- マージ前に**全チェックが pass/skipping であることを判定してから**マージする。
  `gh pr checks` の結果を目視ではなく件数で確認する

## 正本となる文書

| 文書 | 内容 |
| --- | --- |
| `docs/HANDOFF.md` | この文書。作業状態と次の一手 |
| `docs/ON_DEVICE_LLM_FINDINGS.md` | 端末内LLMの実機実測・モデル調査・API調査・再設計方針 |
| `docs/releases/V1.0_RELEASE_CHECKLIST.md` | #55 の完成判定。実機検証の証跡 |
| `docs/RELEASE_PROCESS.md` | 署名付きAPKの生成・配布・ロールバック |
| `docs/adr/` | 設計判断。0008=APK配布、0009=端末内LLM |
| `AGENTS.md` | ビルド手順と実装ルール |

## 内容情報の充足度を測るスクリプト

キーを受け取ったら、実蔵書のISBNで次を測って提供元を決める。

- ヒット率、**内容紹介がある冊数**、平均／最短／最長の文字数、書影の有無
- 取れなかった本の書名と理由

蔵書は端末から取得する。

```bash
adb exec-out "run-as dev.ndcshelf.app cat databases/ndc-shelf.db" > /tmp/lib.db
sqlite3 /tmp/lib.db "select e.isbn13, w.title from owned_copies c
  join book_editions e on c.editionId=e.id join book_works w on e.workId=w.id
  where e.isbn13 is not null;"
```

**取得したISBNと書名は個人の蔵書データ。ログや公開文書へ残さない。**

## 過去のセッションを読む

作業の経緯を掘り返したいときの手順。**生の記録をそのまま読ませてはいけない。**
Claude Code のセッション記録は数十MB（このプロジェクトの最長は22.4MB / 7,834行）あり、
どの文脈にも載らない。中身の大半はビルドログやUIダンプなどのツール出力で、読む価値も薄い。

まず `docs/HANDOFF.md` と `docs/ON_DEVICE_LLM_FINDINGS.md` を読むこと。
生ログは、そこに書かれていない細部を確認したいときだけ使う。

```bash
S=~/.claude/projects/-home-ryusei-code/<session-id>.jsonl

# 会話だけを時系列で（22.4MB → 約278KB）
python3 tools/read_session.py "$S"

# 利用者の指示だけ（何を依頼されたかの一覧。約60KB）
python3 tools/read_session.py "$S" --user-only

# 語で絞る（前後の文脈つき）
python3 tools/read_session.py "$S" --grep prefill --context 2

# 実行したコマンドの一覧
python3 tools/read_session.py "$S" --commands
```

セッションIDは `~/.claude/projects/-home-ryusei-code/` のファイル名。
`ls -lt` で新しい順に並ぶ。同じセッションの続きをやるなら `claude --resume <session-id>`。

**記録には個人の蔵書データ（実在のISBN・書名）が含まれる。抽出結果を公開文書へ貼らない。**
