# 画面遷移

`ui/navigation/AppRoutes.kt`の`@Serializable` routeを唯一の遷移定義とする。採用判断は[ADR 0006](adr/0006-navigation-compose.md)を参照。

## ルートと引数

| route | 引数 | 役割 |
| --- | --- | --- |
| `LibraryGraph` → `LibraryRoute` | なし | 本棚タブの開始画面 |
| `LibraryGraph` → `WorkVariantRoute` | `workId: String` | 版違い（作品グループ）編集 |
| `LibraryGraph` → `TagManagementRoute` | なし | タグとコレクション（保存済み検索）の管理 |
| `LibraryGraph` → `AiLibrarianRoute` | なし | AI司書への相談（対象範囲・送信項目は画面内で選ぶ） |
| `ScanRoute` | なし | スキャン・書店モード |
| `SeriesGraph` → `SeriesRoute` | なし | シリーズ一覧・詳細 |
| `SeriesGraph` → `SeriesSuggestionRoute` | `workId: String?` | シリーズ候補の確認・確定（nullは一覧起点） |
| `InsightsRoute` | なし | 読書傾向 |
| `DataGraph` → `DataRoute` | なし | データ管理タブの開始画面 |
| `DataGraph` → `ConsentRoute` | なし | プライバシーと同意（目的別同意の確認・付与・撤回） |
| `DataGraph` → `DiagnosticsRoute` | なし | 診断とサポート情報（端末内のみ・選択式の診断ファイル生成） |
| `InfoRoute` | なし | アプリ情報 |
| `OnboardingRoute` | なし | 初回オンボーディング（未完了時に自動表示、情報画面から再表示可） |

## 規則

- route引数へは安定IDだけを渡す。DBモデル、タイトル等の個人データ、一時オブジェクトをBundleへ保存しない。
- 引数のIDが無効・削除済みの場合、対象画面はエラー表示または選択解除で安全に処理し、クラッシュ・空白画面にしない。
- トップレベルのタブは`saveState`/`restoreState`付きで切り替え、タブごとの画面状態（検索条件、スクロール、詳細スタック）を保存・復元する。
- システム戻るは詳細→タブ→開始タブ（本棚）→終了の順に解決する。詳細画面は`popBackStack`で閉じる。
- ディープリンク`ndcshelf://book/{editionId}`は`MainActivity`でID形式を検証してから本棚タブへ渡す。未知IDは選択を残さない。
- Storage Access FrameworkのActivity Result launcherは`NdcShelfApp`ルートで登録を維持し、遷移先には置かない（プロセス再生成後の結果配送を保証するため）。

## ウィンドウサイズクラスとの関係

方針の正本は[docs/ADAPTIVE_LAYOUT.md](ADAPTIVE_LAYOUT.md)。routeとの接点は次の3点だけ。

- **routeはサイズクラスで変わらない。** compactでもexpandedでも同じrouteを使い、
  一覧＋詳細を1ペインで順に見せるか、2ペインで同時に見せるかだけが変わる。
  「詳細を開いているか」をroute引数へ持たせない（サイズクラス変更のたびにroute書き換えが
  必要になり、安定IDのみを渡す規則とも噛み合わないため）。
- **トップレベル遷移の見た目だけが変わる。** compactは下部`NavigationBar`、medium/expandedは
  左`NavigationRail`。遷移そのもの（`popUpTo` + `saveState`/`restoreState`）は同一で、
  切り替えは`ui/adaptive/AdaptiveNavigationScaffold.kt`の1箇所に集約する。
- **2ペインでは詳細の戻る操作を出さない。** 一覧が同時に見えているため、詳細ペインの
  戻る矢印と`BackHandler`を無効化する（`showBackAction = false`）。システム戻るは
  タブ・画面スタックの解決だけに使われる。

## ViewModelの分割方針

- タブ横断で共有する状態（本棚検索、スキャン、インポート・バックアップ進行）は`MainViewModel`が保持する。
- 特定routeでしか使わない状態は、そのroute専用のViewModelへ分離し、route引数を`SavedStateHandle`（`toRoute()`）から復元する。第一弾は`WorkVariantViewModel`。
- `AiLibrarianRoute`は`AiLibrarianViewModel`を持つ。対象範囲となる「現在の検索結果」はroute引数ではなく、`MainViewModel.librarySearchResult`から取得したcopyIdの集合を`setSearchResultCopyIds`で渡す（冊名や検索文をBundleへ載せないため）。
- 新しい詳細画面を追加する際は、`MainViewModel`へStateFlowを足すのではなく、この方式に従う。
