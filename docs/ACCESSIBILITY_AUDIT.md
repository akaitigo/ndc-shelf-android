# アクセシビリティ監査

WCAG 2.2 AA と Android のアクセシビリティ推奨に沿って、主要フローを全て利用可能に
するための監査結果と修正状況の正本。CI で自動検出できる層と、実機でしか検証できない
層を分離し、後者はリリースゲート（`docs/releases/`）の証跡として残す。

対象コミット時点のバージョン: v0.4 系（`app/build.gradle.kts` の `versionName`）。

## 監査の観点

| 観点 | 内容 |
| --- | --- |
| label | 操作可能要素にアクセシブルネームがあるか（`contentDescription` / テキスト） |
| role | `Role.Button` / `Role.Checkbox` / `Role.Switch` 等が正しいか |
| state | 選択・チェック・有効無効が `selected` / `stateDescription` で伝わるか |
| heading | 画面・セクション・ダイアログのタイトルに `semantics { heading() }` があるか |
| 読み上げ順 | リスト項目が `mergeDescendants` で1ストップにまとまるか、live region があるか |
| 48dpタップ領域 | 操作可能要素のタッチ領域が48dp以上か |
| コントラスト | 前景・背景のコントラスト比が AA を満たすか |
| 色以外の状態表現 | 状態を色だけで示していないか（テキスト・アイコン併記） |
| 200%文字 | `fontScale 2.0` で切れ・重なりが起きないか |
| モーション低減 | アニメーションが必須の情報伝達になっていないか |

重大度の基準:

- **Critical**: スクリーンリーダーだけでは機能が利用不能・操作不能になる
- **Major**: 利用は可能だが著しく困難、または WCAG 2.2 AA 相当に抵触する
- **Minor**: 改善余地（読み上げ回数が多い、文言の一貫性など）

## 主要フロー × 観点の監査結果

凡例: ✅ 問題なし / 🔧 本Issueで修正 / ⚠ 残課題（実機ゲートで確認）

| フロー | label | role | state | heading | 読み上げ順 | 48dp | コントラスト | 色以外 | 200%文字 | モーション |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| オンボーディング | ✅ | ✅ | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | 🔧 | ✅ |
| 登録（スキャン） | 🔧 | ✅ | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | ✅ | ✅ |
| 登録（手入力・手動登録） | ✅ | ✅ | ✅ | 🔧 | ✅ | ✅ | ⚠ | ✅ | ✅ | ✅ |
| 検索・絞り込み | 🔧 | ✅ | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | 🔧 | ✅ |
| 本詳細・編集 | ✅ | 🔧 | 🔧 | 🔧 | 🔧 | 🔧 | ⚠ | ✅ | ✅ | ✅ |
| 削除・Undo | ✅ | ✅ | ✅ | 🔧 | ✅ | ✅ | ⚠ | ✅ | ✅ | ✅ |
| 棚管理（置き場所） | 🔧 | ✅ | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | ✅ | ✅ |
| シリーズ | ✅ | 🔧 | 🔧 | 🔧 | 🔧 | ✅ | ⚠ | ✅ | ✅ | ✅ |
| タグ・保存済み検索 | ✅ | 🔧 | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | ✅ | ✅ |
| データ管理（出力・取込・バックアップ） | ✅ | ✅ | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | ✅ | ✅ |
| 同意 | ✅ | ✅ | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | ✅ | ✅ |
| 診断 | ✅ | 🔧 | ✅ | 🔧 | 🔧 | ✅ | ⚠ | ✅ | ✅ | ✅ |
| インサイト | ✅ | ✅ | ✅ | ✅ | 🔧 | ✅ | ⚠ | ✅ | 🔧 | ✅ |
| アプリ情報・ライセンス | ✅ | ✅ | ✅ | 🔧 | ✅ | ✅ | ⚠ | ✅ | ✅ | ✅ |

コントラストを全フロー ⚠ としているのは、Android 12 以降の動的配色（Material You）で
実際の描画色が端末ごとに変わり、静的なコード監査では確定できないため。実機ゲートの
「拡大・コントラスト」項目で確認する。

## 個別の指摘と修正状況

### Critical

| # | 箇所 | 問題 | 修正 |
| --- | --- | --- | --- |
| C1 | `TagManagementScreen.kt` 保存済み検索の削除 | 確認ダイアログも Undo も無く、誤タップで復旧不能な削除が即時実行される | 🔧 削除確認 `AlertDialog` を追加（`saved_search_delete_confirm_*`） |
| C2 | `LibraryScreen.kt` `BulkTagDialog` のタグ行 | `Checkbox` が隣接するタグ名と関連付けられず、アクセシブルネームを持たない。ラベル部分はタップ不可 | 🔧 行全体を `Modifier.toggleable(role = Role.Checkbox)` 化し、`Checkbox(onCheckedChange = null)` に変更 |
| C3 | `LibraryScreen.kt` `BookCard` の一括選択 | 選択状態が背景色だけで表現され、`selected` セマンティクスが無い | 🔧 `semantics { selected; stateDescription }` を付与し、可視のチェックアイコンを併記。`onClickLabel` / `onLongClickLabel` も付与 |
| C4 | `ScanScreen.kt` スキャン結果・成功オーバーレイ | 非同期に切り替わる結果（取得中・追加・重複・エラー）に live region が無く、成否が音声で伝わらない | 🔧 `ResultSurface` と成功オーバーレイを `liveRegion = LiveRegionMode.Polite` 化 |
| C5 | `CameraPreview.kt` の `AndroidView` | タップでのピント合わせとピンチズームを持つが `contentDescription` が皆無 | 🔧 プレビューに説明を付与し、カメラを使えない場合の代替経路（ISBN手入力・手動登録）も文言に含める |

### Major

| # | 箇所 | 問題 | 修正 |
| --- | --- | --- | --- |
| M1 | 全画面の見出し | 画面タイトル・セクション見出し・ダイアログタイトルの大半に `semantics { heading() }` が無く、TalkBack の見出しジャンプが機能しない | 🔧 13画面へ付与（下記「heading 付与状況」） |
| M2 | `SeriesScreen.kt` 新刊候補スイッチ / `WorkVariantScreen.kt` `SettingRow` | `Switch` が隣接ラベルと結び付かず「スイッチ、オフ」としか読まれない | 🔧 行を `Modifier.toggleable(role = Role.Switch)` 化し `Switch(onCheckedChange = null)` に変更 |
| M3 | `DiagnosticsScreen.kt` 診断セクション選択 / `BookDetailScreen.kt` タグ割当 | C2 と同型の `Checkbox` + ラベル分離 | 🔧 行を `toggleable` 化 |
| M4 | `BookDetailScreen.kt` 読書記録エディタの単一選択チップ群 | 排他選択なのに `selectableGroup()` が無く、「N個中M番目」の位置情報が読まれない | 🔧 コピー・状態・評価の3グループへ `Modifier.selectableGroup()` を付与 |
| M5 | `BookDetailScreen.kt` 読書記録の編集・削除ボタン | 破壊的操作が 4dp 間隔で隣接し誤タップしやすい | 🔧 間隔を 12dp へ拡大 |
| M6 | `LibraryScreen.kt` `LocationItemRow` | 「上へ」「削除」等の説明が対象名を含まず、部屋・棚・段が複数あると区別できない | 🔧 `location_*_target` で対象名入りの説明に変更し、名称と内訳を merge |
| M7 | `LibraryScreen.kt` `LibrarySummary` | 値とラベルが分断して読まれる（「12」「蔵書」） | 🔧 `mergeDescendants` + 「蔵書: 12」形式の `contentDescription` |
| M8 | `LibraryScreen.kt` 一括選択件数 / `ScanScreen.kt` セッション件数 | 動的に変わる件数に live region が無い | 🔧 `liveRegion = LiveRegionMode.Polite` を付与 |
| M9 | `SeriesScreen.kt` `SeriesVolumeCard` / `WorkVariantScreen.kt` グループ一覧 / `TagManagementScreen.kt` `TagRow` | 非クリッカブルカード内の複数 Text が個別フォーカスになり、1件あたり6〜8スワイプ必要 | 🔧 情報部分へ `semantics(mergeDescendants = true)` を付与（操作ボタンは個別ノードのまま） |
| M10 | `DataManagementScreen.kt` `SyncStatusCard` / `ConsentScreen.kt` `ConsentDetailRow` / `DiagnosticsScreen.kt` `DiagnosticsRow`・イベント履歴 / `BookDetailScreen.kt` `DetailValue`・`ReadingSessionCard` / `InsightsScreen.kt` 分類済み一覧 | 同上の読み上げ分断 | 🔧 `mergeDescendants` を付与 |
| M11 | `DiagnosticsScreen.kt` 読み込み中表示 | `CircularProgressIndicator` のみでラベルが無く、何を待っているか伝わらない | 🔧 `diagnostics_loading` のテキストを併記 |

### Minor

| # | 箇所 | 問題 | 修正 |
| --- | --- | --- | --- |
| m1 | `LibraryScreen.kt` / `ScanScreen.kt` | `contentDescription` に日本語がハードコードされ、他箇所の `stringResource` 利用と不整合 | 🔧 `library_search_clear` / `scan_close` 等へ切り出し。画面タイトル・サブタイトル・検索プレースホルダも文字列リソース化 |
| m2 | `LibraryScreen.kt` `BookCard` の鉛筆アイコン | 行全体のクリックで編集が開くため、アイコンの説明が読み上げに重複混入する | 🔧 `contentDescription = null`（装飾扱い）とし、代わりに `onClickLabel` で操作を告知 |
| m3 | `SeriesScreen.kt` カタログカードの `ChevronRight` | カード全体が `Role.Button` で merge されるためシリーズ名が二重読み上げになる | 🔧 `contentDescription = null` |
| m4 | `OnboardingScreen.kt` | `Text("")` が無意味なフォーカス停止点になる | 🔧 `Spacer` へ置換 |
| m5 | `NdcShelfApp.kt` `NavigationBarItem` | ラベル常時表示なのにアイコンにも同一文言の `contentDescription` があり重複読み上げの可能性 | ⚠ 未修正。Material3 の `NavigationBarItem` は内部で merge するため実害が小さく、実機 TalkBack で挙動を確認してから対応する |
| m6 | `SeriesScreen.kt` シリーズ紐付け解除 / タグ削除 | 確認ダイアログはあるが確定後の Undo が無い（本の編集・削除・読書履歴削除は Undo 付き） | ⚠ 未修正。Undo にはリポジトリ層の履歴保持が必要でIssue #43の範囲を超えるため、別Issueとして起票する |
| m7 | `ScanScreen.kt` カメラプレビューの固定高さ 310dp | `clip` によりガイド文言が fontScale 2.0 で切れる可能性 | ⚠ 未修正。カメラ実描画を含むためエミュレーターで再現できず、実機ゲートで確認する |

### heading 付与状況

`semantics { heading() }` を付与した画面（本Issue修正後）:

`LibraryScreen` / `ScanScreen` / `OnboardingScreen` / `BookDetailScreen` /
`DataManagementScreen` / `InsightsScreen` / `ConsentScreen` / `DiagnosticsScreen` /
`AppInfoScreen` / `SeriesScreen` / `SeriesSuggestionScreen` / `TagManagementScreen` /
`WorkVariantScreen`

## 検証済みの良好な実装（変更不要）

- **カメラ以外の登録経路**: `ScanScreen` は `ManualIsbnEntry`（ISBN手入力）と
  `manual_registration_open`（ISBNなしの手動登録）を常時表示し、`OnboardingScreen` も
  `onManualEntry` / `onImport` を提供する。カメラ権限を拒否しても登録フローが完結する
  ことをコードで確認した。本Issueでは経路自体の変更は不要で、`CameraPreview` の説明
  文言へ代替経路への言及を追加するにとどめた（C5）。
- **グラフの同等テキスト**: `InsightsScreen` の月別読了棒グラフ（`FinishedTrendChart`）と
  NDC分布（`ClassificationRow`）は、#41 の対応で数値テキストの併記と
  `mergeDescendants` による `contentDescription` を実装済み。色・高さのみに依存しない
  ことを再確認した。本Issueでの追加対応は不要。
- **色以外の状態表現**: タグの色スウォッチ（`TagColorSwatch`）は隣接テキストで必ず
  ラベルを併記する設計。同意状態・エラー表示・欠巻候補も文言で状態を表しており、
  色のみに依存する箇所は見つからなかった。
- **自然言語検索の解釈チップ**（`LibraryScreen` の `InterpretationChipsRow`、#118 で追加）:
  `InputChip` 全体が解除操作のクリック対象で、末尾の × アイコンには
  `nl_search_chip_dismiss` により対象ラベルを含む説明が付く。Material3 の
  `InputChip` はタッチ領域48dpを満たす（下記実測）。追加対応は不要と判断した。
- **`Card(onClick = ...)` の自動 merge**: クリッカブルなカードは Compose の既定動作で
  子孫が merge されるため、`AppInfoScreen` のライセンス一覧などは追加対応不要。
- **既存の Undo パターン**: 本の編集・本の削除・読書履歴削除は
  `SnackbarHostState.showSnackbar(actionLabel = ..., withDismissAction = true)` と
  `SnackbarDuration.Long` で Undo を提供する。スキャンセッションの取り消しは
  自動消滅する Snackbar ではなく `AlertDialog` による明示的な確認で、より確実な代替経路
  になっている。

### 48dpタップ領域の実測結果

Material3 1.4.0 のコンポーネントについて、Robolectric 上で `SemanticsNode` の
`touchBoundsInRoot` を実測した（`density` 換算、fontScale 1.0）:

| コンポーネント | 描画上の高さ | タッチ領域の高さ |
| --- | --- | --- |
| `FilterChip` / `InputChip` | 36dp | **48dp** |
| `TextButton` | 52dp | **52dp** |

`FilterChip` は描画上 36dp だが、Material3 が内部で最小タッチ領域を確保しており
48dp を満たす。したがって `Modifier.minimumInteractiveComponentSize()` の明示付与は
無効果（付与前後でスクリーンショットに差分が出ないことも確認）であり、コードノイズに
なるため採用しなかった。`IconButton` / `Checkbox` は Material3 が
`minimumInteractiveComponentSize()` を内部適用していることをバイトコードで確認済み。

48dp 不足として修正したのは、破壊的操作が近接していた
`BookDetailScreen` の編集・削除ボタン間隔のみ（M5）。

## 自動チェックの範囲

### スクリーンショット回帰（JVM / CI の `verify` ジョブ）

`ScreenshotRegressionTest`（Robolectric + Roborazzi）で以下を golden 比較する。

| ケース | 内容 |
| --- | --- |
| `library_light` / `library_dark` | 本棚のライト・ダーク |
| `library_large_font` | `fontScale 1.5` |
| `library_font_scale_200` | **`fontScale 2.0`（本Issueで追加）** |
| `insights_light` / `insights_dark` | インサイトのライト・ダーク |
| `insights_font_scale_200` | **`fontScale 2.0`（本Issueで追加）** |
| `onboarding_light` / `onboarding_dark` | オンボーディングのライト・ダーク |
| `onboarding_font_scale_200` | **`fontScale 2.0`（本Issueで追加）** |
| `library_medium_rail` | **medium幅（w720dp）の NavigationRail + 1ペイン（#47で追加）** |
| `library_expanded_two_pane` | **expanded幅（w1280dp）の NavigationRail + list-detail 2ペイン（#47で追加）** |

`fontScale 2.0` の3枚を目視確認し、テキストの切れ・重なりが無いこと、絞り込みチップが
横スクロールで到達可能なことを確認した。golden の更新は
`./gradlew recordRoborazziDebug` の結果をレビューしてコミットする。

### Accessibility Test Framework（instrumentation / CI の `android-test` ジョブ）

`androidx.compose.ui:ui-test-junit4-accessibility` を導入し、Accessibility Scanner と
同じ Accessibility Test Framework（ATF）の検査を instrumentation テストで実行する。
API 26 / 29 / 35 のエミュレーターで `connectedStandardDebugAndroidTest` として動作する。

| テスト | 対象 |
| --- | --- |
| `AccessibilityChecksTest` | `LibraryScreen` / `DataManagementScreen` / `ScanSessionPanel` を描画し `tryPerformAccessibilityChecks()` |
| `LibrarySearchScreenTest` | `@Before` でチェックを有効化。操作（クリック）のたびに検査が走る |
| `DataManagementScreenTest` | 同上 |
| `ScanSessionPanelTest` | 同上 |

判定は `setThrowExceptionFor(ERROR)` により ERROR のみを失敗とする。

**抑制しているチェックと理由**:

| チェック | 抑制理由 |
| --- | --- |
| `TextContrastCheck` | Android 12 以降の動的配色（Material You）で実際の描画色が端末ごとに変わり、エミュレーターの既定配色での判定が実機を代表しない。コントラストは本書のコントラスト欄と実機ゲート（拡大・TalkBack実走）で担保する |
| `ImageContrastCheck` | 同上。加えて表紙画像は外部データで、内容に応じたコントラストをアプリ側で保証できない |

抑制は `AccessibilityChecksTest.runChecks()` と各テストの `@Before` に集約しており、
追加・解除はこの表と合わせて更新する。

## 大画面レイアウト（#47）での読み上げ順とタップ領域

タブレット・横画面・折りたたみ向けにナビゲーションと本文の配置を変えた際
（方針は `docs/ADAPTIVE_LAYOUT.md`）、読み上げ順の論理性が崩れないことを次のとおり確認した。

### 読み上げ順（見出し → 内容）の維持

| 変更点 | 読み上げ順への影響 | 対応 |
| --- | --- | --- |
| medium/expanded で下部 `NavigationBar` → 左 `NavigationRail` | railを`Row`の先頭に置くため、TalkBackは「ナビゲーション → 本文」の順に読む。compactは従来どおりScaffoldの`bottomBar`として本文の後に読む | 位置の入れ替えのみで、いずれも「現在地の把握 → 内容」の順序を保つ |
| expanded の list-detail 2ペイン | 一覧ペインを`Row`の先頭、詳細ペインを後に置く。各ペイン内の見出し（`library_title` / `book_detail_title` / `series_title`）と`semantics { heading() }`は変更していないため、見出しジャンプの順序は「本棚 → 本の詳細」になる | ペイン順序を固定（`AdaptivePaneTest` で一覧・詳細が同時に表示されることを検証） |
| 2ペインで詳細の戻る矢印を非表示 | 一覧が同時に見えているため「一覧へ戻る」導線は不要。読み上げ・フォーカスの停止点が1つ減る | `showBackAction = false`。1ペイン時は従来どおり表示（`AdaptivePaneTest` で両方を検証） |
| 詳細ペイン未選択時のプレースホルダ | 空白ペインは読み上げるものが無く、次の操作が分からない | `EmptyDetailPane` で「左の一覧から選ぶ」旨を文言で明示 |
| 2ペインでの一覧側の選択強調 | 背景色だけでは選択中の項目が伝わらない | `BookCard` / `SeriesCatalogCard` に `selected` セマンティクスと `stateDescription`（「詳細表示中」）を付与。一括選択中は従来の選択状態表現を優先する |

既存の `AccessibilityChecksTest`（ATF）は `LibraryScreen` を1ペイン構成で描画しており、
本変更後も同じfixtureで通ることを確認した。2ペイン構成は `AdaptivePaneTest`（Robolectric）と
`library_expanded_two_pane` golden で回帰を検出する。

### 48dpタップ領域（大画面で追加された操作）

| コンポーネント | 実測 | 備考 |
| --- | --- | --- |
| `NavigationRailItem` | **48dp 以上（幅・高さとも）** | `AdaptiveNavigationTest.railItemKeepsMinimumTouchTarget` で回帰検証。Material 3 が `minimumInteractiveComponentSize()` を内部適用している（`FilterChip` と同じ仕組み・上表参照） |

### キーボード操作

物理キーボード接続時のフォーカス順は「NavigationRail → 一覧ペイン → 詳細ペイン」。
レール項目は `Modifier.selectable` 由来のフォーカス可能ノードで、Enter / Space で発火する
（`AdaptiveNavigationTest.railItemIsFocusableAndActivatesWithEnterKey`）。
2ペインでも一覧→詳細の順序が保たれるため、Tab だけで詳細へ到達できる。

## 実機ゲート

以下は実機でしか検証できないため、CI では扱わず**リリースゲートで実施する**。
`docs/DEVICE_TEST_MATRIX.md` の「手動実機層」と、各リリースの
`docs/releases/V*_RELEASE_CHECKLIST.md` の実機ゲート節に組み込む。

結果は表形式（端末・OS・支援技術のバージョン・結果・日付・実施者）でチェックリストへ
残し、失敗時は再試行ではなく Issue 化して原因を修正する。スクリーンショット・ログ・
Issue に fixture 以外の個人データ（実在ISBN・氏名・実際の棚位置・通知内容）を含めない。

### 1. TalkBack スモーク

Android 13 以上の実機で TalkBack を有効化し、以下を**画面を見ずに**完了できることを確認する。

1. オンボーディングを最後まで進め、「手動登録」へ到達できる
2. カメラ権限を拒否した状態で、ISBN手入力から1冊登録できる
3. 本棚で目的の本を検索し、詳細を開き、読書状態を変更して保存できる
4. 本を削除し、Undo の Snackbar のアクションを実行して復元できる
   （読み上げ中に Snackbar が消えないか、消えた場合の復旧手段も確認する）
5. 置き場所を追加・改名・並べ替え・削除できる（対象名が読み上げに含まれるか）
6. タグを作成し、本棚の一括選択で複数冊へ付与できる（選択状態が読み上げられるか）
7. 保存済み検索を削除しようとしたとき、確認ダイアログが読み上げられる
8. データ管理でエクスポート→インポートを往復し、結果通知が読み上げられる
9. シリーズ画面で新刊候補スイッチを切り替え、対象と状態が読み上げられる
10. 見出しジャンプ（TalkBack の読み上げ単位を「見出し」にして上下スワイプ）で
    各画面のセクションを移動できる

### 2. 両言語でのTalkBack読み上げ

英語（`values/`）と日本語（`values-ja/`）の両方で読み上げを確認する。
文言リソースの方針と用語集は `docs/I18N.md` を参照する。

端末の言語を「日本語」「English (United States)」へ切り替えて、それぞれで実施する。

1. TalkBack の音声エンジンが端末の言語に追従し、上記スモークの 1〜4 を
   その言語の音声だけで完了できること
2. `contentDescription` が翻訳されていること。特に以下を読み上げで確認する
   - 本カードの `book_card_open_label` / `book_card_selected` / `book_card_unselected`
   - NDC類の行（`insights_ndc_row_description`）で類番号・類名・冊数・比率が読まれること
   - 検索の解釈チップ解除（`nl_search_chip_dismiss`）にチップ名が含まれること
   - 表紙の有無（`book_cover_description` / `book_cover_missing`）
3. 冊数の読み上げが英語で単複を取り違えないこと
   （`<plurals>` 化済み。1冊のとき "1 copy"、2冊以上で "copies"）
4. 日付が端末ロケールの書式で読まれること（`DateFormat.MEDIUM` / `SHORT`）
5. ISBN と NDC コードが言語に関わらず同じ文字列で読まれること
6. 未翻訳の日本語が英語UIの読み上げに混ざらないこと。
   混入が疑われる場合は擬似ロケール `en-XA` のスクリーンショット
   （`app/roborazzi/library_pseudo_en_xa.png` ほか）でアクセントの付かない文字列を探す
7. RTL 言語の読み上げ順は `ar-XB` 擬似ロケールの golden で崩れがないことを確認したうえで、
   実機では日英のみ読み上げ確認する（RTL言語は正式対応言語ではない）

### 3. Switch Access

1. スイッチだけで上記 1〜4 のフローを完了できる
2. スキャン画面のカメラプレビューがフォーカス可能で、代替経路へ到達できる
3. フォーカスが視覚的に追える（ハイライトが隠れない）

### 4. 大画面・折りたたみでのTalkBack

1. タブレット横持ち（expanded）で、TalkBackの見出しジャンプが
   「本棚 → 本の詳細」の順に移動できること
2. 折りたたみを開閉しても読み上げ位置とフォーカスが失われないこと
3. 物理キーボードのTabだけで NavigationRail → 一覧 → 詳細 へ到達し、Enter で発火すること

### 5. 拡大とコントラスト

1. 「表示サイズとテキスト」でフォントサイズ最大・表示サイズ最大にし、
   主要画面で文字の切れ・重なり・操作不能な要素が無いこと
   （特に `ScanScreen` のカメラガイド文言とスキャン結果カード）
2. 画面の拡大（3本指ダブルタップ）で操作を継続できること
3. 「ハイコントラストテキスト」を有効化して主要画面を確認する
4. 動的配色（壁紙変更で配色が変わる端末）で主要画面のコントラストを確認する

### 6. モーション低減

1. 開発者オプションでアニメーションを全てオフ（0x）にし、
   画面遷移・スキャン成功オーバーレイ・Snackbar が正しく機能すること
2. アニメーションが唯一の情報伝達手段になっていないこと

## 残課題

- **m5**: `NavigationBarItem` のアイコンとラベルの重複読み上げ（実機 TalkBack で確認後に判断）
- **m6**: シリーズ紐付け解除・タグ削除の Undo（リポジトリ層の履歴保持が必要。別Issueで対応）
- **m7**: カメラプレビュー固定高さの fontScale 2.0 でのクリップ（実機ゲートで確認）
- 実機 TalkBack / Switch Access / 拡大 / モーション低減の実走（リリースゲートで実施）
- 日英両言語での TalkBack 読み上げ実走（リリースゲートで実施。上記「2. 両言語でのTalkBack読み上げ」）
