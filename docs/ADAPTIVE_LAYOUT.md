# 適応レイアウト（タブレット・横画面・折りたたみ）

タブレット、横画面、分割画面、折りたたみ端末で同じコードベースを破綻なく使うための
レイアウト方針の正本。実装は `ui/adaptive/`（`AdaptiveLayout` / `AdaptiveNavigationScaffold`）、
画面遷移との関係は [docs/NAVIGATION.md](NAVIGATION.md)、読み上げ順の扱いは
[docs/ACCESSIBILITY_AUDIT.md](ACCESSIBILITY_AUDIT.md) を参照する。

## 原則

1. **機種名で分岐しない。ウィンドウサイズと姿勢能力で分岐する。**
   「タブレットかどうか」「折りたたみかどうか」は判定しない。判定するのは
   **アプリへ与えられたウィンドウの幅**だけである。分割画面・自由ウィンドウ・
   折りたたみの開閉・外部ディスプレイ接続は、すべてウィンドウ幅の変化として現れるため、
   幅で分岐すれば端末種別の列挙が不要になる。
2. **サイズクラスの分岐は1箇所に集約する。**
   `AdaptiveLayout` の生成は `NdcShelfApp` 直下の `AdaptiveNavigationScaffold` だけで行う。
   各画面は `twoPane: Boolean` や `listPaneWidth: Dp` のような**与えられた指示**だけを受け取り、
   自分でサイズクラスを問い合わせない。これにより端末種別ごとの分岐が画面数×端末数へ
   増殖することを防ぐ。
3. **姿勢が変わってもデータを失わない。**
   選択・入力・スクロール位置は `rememberSaveable`（またはroute引数）で保持する。
   サイズクラスが変わってもペインの呼び出し箇所を1つに保ち、composition identityを維持する。

## サイズクラスごとの方針

breakpointは `androidx.window` の `WindowSizeClass`（Material 3準拠）に従う。

| サイズクラス | ウィンドウ幅 | 代表的な状況 | ナビゲーション | カラム構成 | コンテンツ最大幅 | 水平余白 |
| --- | --- | --- | --- | --- | --- | --- |
| compact | < 600dp | ハンドセット縦持ち、分割画面の狭い側 | 下部 `NavigationBar` | 1ペイン（一覧⇔詳細は画面遷移） | 制限なし（端末幅いっぱい） | 0dp |
| medium | 600dp 以上 840dp 未満 | ハンドセット横持ち、小型タブレット、折りたたみ内側 | 左 `NavigationRail` | 1ペイン | 720dp（中央寄せ） | 8dp |
| expanded | 840dp 以上 | タブレット横持ち、デスクトップ級ウィンドウ | 左 `NavigationRail` | 本棚・シリーズは list-detail 2ペイン。他は1ペイン中央寄せ | 1080dp（中央寄せ） | 12dp |

- **一覧ペイン幅**: expandedでは 360dp 固定。詳細ペインは残り幅（最大 1080 − 360 = 720dp）を占める。
  詳細ペインの上限を単一ペインの上限（720dp）と一致させ、1ペイン⇔2ペインで本文の
  読みやすさが変わらないようにしている。
- **最大幅を設ける理由**: 1行あたりの文字数が増えすぎると視線の戻りが大きくなり可読性が
  落ちるため。上限を超えた分は左右の余白になる（中央寄せ）。

## list-detail の採用範囲

| 画面 | compact / medium | expanded |
| --- | --- | --- |
| 本棚（`LibraryScreen`） | 一覧 → タップで詳細へ遷移（`BookDetailScreen` が全面） | 左：一覧、右：詳細。未選択時は右に案内文（`EmptyDetailPane`） |
| シリーズ（`SeriesScreen`） | 一覧 → タップで巻一覧へ遷移 | 左：シリーズ一覧、右：巻一覧 |
| スキャン・分析・データ・情報 | 1ペイン | 1ペイン（最大幅で中央寄せ） |

### routeとの関係

**routeは1ペイン／2ペインで変えない。** 同じ `LibraryRoute` / `SeriesRoute` が、
与えられたサイズクラスに応じてペイン数を選ぶ。route引数へ「詳細を開いているか」を
持たせると、サイズクラス変更のたびにroute書き換えが必要になり、
`docs/NAVIGATION.md` のroute規則（安定IDのみを渡す）とも噛み合わないため採用しない。

### 戻る操作

2ペインでは一覧が常に見えているため、詳細ペインの**戻る矢印と `BackHandler` を出さない**
（`showBackAction = false`）。システム戻るはタブ／画面スタックの解決に使われ、
1ペイン時のみ「詳細 → 一覧」を担う。

## 状態保持の設計

| 状態 | 保持場所 | サイズクラス変更 | プロセス再生成 |
| --- | --- | --- | --- |
| 選択中の版（本棚） | `LibraryScreen` の `rememberSaveable` + `MainViewModel` の検索条件 | 保持 | 保持 |
| 選択中のシリーズ | `NdcShelfApp` の `rememberSaveable` | 保持 | 保持 |
| 検索文字列 | `LibraryScreen` の `rememberSaveable`（または ViewModel の検索条件） | 保持 | 保持 |
| 一覧のスクロール位置 | `LibraryScreen` / `SeriesScreen` 本体の `rememberLazyListState` | 保持 | 保持 |
| 編集中の入力（編集シート） | `LibraryScreen` 本体（ペインの外） | 保持 | 保持 |

ペイン内部で `rememberSaveable` を持つと、1ペイン⇔2ペインの切り替えで
composition identity が変わり状態が失われる。そのため**スクロール状態と
ダイアログ可視状態は画面本体で保持し、ペインへは引数で渡す**。あわせて
一覧・詳細それぞれの呼び出し箇所を1つに保ち、`Modifier` だけを差し替える。

## タップ領域・キーボード・ポインタ

- **48dpタップ領域**: `NavigationRailItem` を含め Material 3 の
  `minimumInteractiveComponentSize()` により48dpを満たす。実測結果は
  `docs/ACCESSIBILITY_AUDIT.md` の「48dpタップ領域の実測結果」を参照。
  回帰は `AdaptiveNavigationTest.railItemKeepsMinimumTouchTarget` で検証する。
- **キーボード**: Tabフォーカス順は「ナビゲーション（rail） → 一覧ペイン → 詳細ペイン」。
  レール項目は `Modifier.selectable` 由来のフォーカス可能ノードで、Enter / Space で発火する
  （`AdaptiveNavigationTest.railItemIsFocusableAndActivatesWithEnterKey`）。
- **マウス**: クリック対象は既存のタップ対象と同一で、追加のホバー専用操作は設けない
  （ホバーでしか到達できない機能を作らない）。

## 依存関係の判断

サイズクラスの breakpoint 定義には `androidx.window:window-core` の
`androidx.window.core.layout.WindowSizeClass` を使う。

- `androidx.compose.material3:material3-window-size-class` の
  `androidx.compose.material3.windowsizeclass` は、この `androidx.window` の定義へ
  置き換えられている。新規に採用する理由がない。
- `androidx.window` は既に推移的依存として解決済み（1.5.0）で、明示宣言しても
  **新規artifactもAPKサイズ増加も発生しない**。供給網の面でも追加リスクがない。
- `androidx.compose.material3.adaptive:adaptive-layout` の `ListDetailPaneScaffold` は
  本アプリの2画面（本棚・シリーズ）には過剰で、依存追加と引き換えに得るものが
  少ないため採用しない。ペイン構成は約40行の `Row` で表現できている。

## 代表サイズの確認方法

| 手段 | 対象 |
| --- | --- |
| Preview（`AdaptiveNavigationScaffold.kt`） | compact 411dp / medium 700dp / expanded 1280dp のナビゲーション切り替え |
| `ScreenshotRegressionTest.libraryMediumRail` | `library_medium_rail.png`（w720dp、rail + 1ペイン + 最大幅） |
| `ScreenshotRegressionTest.libraryExpandedTwoPane` | `library_expanded_two_pane.png`（w1280dp、rail + 2ペイン） |
| `AdaptiveNavigationTest` | qualifiers 411dp / 720dp / 1280dp でのバー⇔レール切り替え |
| `AdaptivePaneTest` | 2ペイン表示、サイズクラス変更・プロセス再生成での状態保持 |
| `AdaptiveLayoutTest` | breakpoint境界値（599/600/839/840dp） |

golden を追加する際は、CI時間とリポジトリ肥大を避けるため**代表1枚に絞る**。
既存の compact golden は変更しない。

## 実機での確認（リリースゲート）

エミュレーターで再現しきれないものは `docs/DEVICE_TEST_MATRIX.md` の手動実機層で確認する。

1. 折りたたみ端末を開閉し、編集中の入力・一覧のスクロール位置・選択が保持されること
2. 分割画面で幅を段階的に変え、レイアウトが破綻せず操作を継続できること
3. 物理キーボード接続時に Tab だけで主要操作へ到達でき、Enter / Space で発火すること
4. スタイラス・マウスのホバー時にクリック領域が視認できること
