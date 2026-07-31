# 画面遷移

`ui/navigation/AppRoutes.kt`の`@Serializable` routeを唯一の遷移定義とする。採用判断は[ADR 0006](adr/0006-navigation-compose.md)を参照。

## ルートと引数

| route | 引数 | 役割 |
| --- | --- | --- |
| `LibraryGraph` → `LibraryRoute` | なし | 本棚タブの開始画面 |
| `LibraryGraph` → `WorkVariantRoute` | `workId: String` | 版違い（作品グループ）編集 |
| `LibraryGraph` → `TagManagementRoute` | なし | タグとコレクション（保存済み検索）の管理 |
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
- 下部タブは`saveState`/`restoreState`付きで切り替え、タブごとの画面状態（検索条件、スクロール、詳細スタック）を保存・復元する。
- システム戻るは詳細→タブ→開始タブ（本棚）→終了の順に解決する。詳細画面は`popBackStack`で閉じる。
- ディープリンク`ndcshelf://book/{editionId}`は`MainActivity`でID形式を検証してから本棚タブへ渡す。未知IDは選択を残さない。
- Storage Access FrameworkのActivity Result launcherは`NdcShelfApp`ルートで登録を維持し、遷移先には置かない（プロセス再生成後の結果配送を保証するため）。

## ViewModelの分割方針

- タブ横断で共有する状態（本棚検索、スキャン、インポート・バックアップ進行）は`MainViewModel`が保持する。
- 特定routeでしか使わない状態は、そのroute専用のViewModelへ分離し、route引数を`SavedStateHandle`（`toRoute()`）から復元する。第一弾は`WorkVariantViewModel`。
- 新しい詳細画面を追加する際は、`MainViewModel`へStateFlowを足すのではなく、この方式に従う。
