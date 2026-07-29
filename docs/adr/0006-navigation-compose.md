# ADR 0006: 画面遷移をNavigation Composeの型安全routeへ移行する

- Status: Accepted
- Date: 2026-07-29
- Issue: #46
- Decision owners: repository maintainer

## Context

これまでの`NdcShelfApp`は`rememberSaveable`のenumと`when`分岐で6画面を切り替え、詳細系画面（版違い編集、シリーズ候補確認）は画面内フラグで重ねて表示していた。v1.0では詳細・設定・同期・診断・AIの画面が追加され、戻る履歴、プロセス再生成、ディープリンクを一貫して扱う必要がある。enum切替は戻るスタックを持たず、詳細画面の対象IDがプロセス再生成で失われる構造だった。

## Decision Drivers

- 戻る・回転・プロセス再生成・ディープリンクの挙動を宣言的に定義する
- route引数を安定IDだけに限定し、DBモデルや個人データをBundleへ入れない
- 画面追加ごとの分岐増殖と巨大MainViewModel化を止める
- 依存追加はライセンス・APKサイズ・保守性を確認して最小にする

## Considered Options

### Option A: 自前のenum + backstackリストを拡張する

**メリット**: 依存追加なし。
**デメリット**: saveable backstack、ネスト遷移、ディープリンク、型安全引数を全て自前実装・保守することになり、標準挙動との差分がバグ源になる。

### Option B: Navigation Compose（androidx.navigation:navigation-compose）を採用する

**メリット**: kotlinx-serializationによる型安全route、`rememberSaveable`統合の状態復元、階層graph、AndroidXの長期保守。Apache-2.0でライセンス互換。依存は既存のAndroidX/kotlinx-serialization系のみで、APK増分は実測で約379KiB（未縮小debug APK比較。R8適用のreleaseではさらに縮小）。
**デメリット**: ライブラリ規約（graph定義、popUpToの意味論）の学習が必要。

### Option C: Navigation 3（alpha）を採用する

**メリット**: 新世代API。
**デメリット**: 2026-07時点でstable未満であり、リリースゲートを持つ本アプリの基盤には時期尚早。

## Decision

Option Bを採用し、navigation-compose 2.9.8（stable）を追加する。routeは`ui/navigation/AppRoutes.kt`の`@Serializable`型だけを使い、引数は安定IDに限定する。詳細画面のViewModelはroute引数を`SavedStateHandle`から復元する画面スコープへ分離し、`MainViewModel`から責務を移す（第一弾として`WorkVariantViewModel`）。ルート・引数・戻る規則は`docs/NAVIGATION.md`を正本とする。

## Consequences

- 版違い編集はプロセス再生成後もroute引数のworkIdから復元される。
- タブはbackstack保存・復元（`saveState`/`restoreState`）で切り替え、システム戻るは開始タブ（本棚）経由で終了する。
- 今後の新画面はrouteを追加し、`when`分岐や画面内フラグを増やさない。
- 依存検証（verification-metadata）、SBOM、ライセンスNOTICEへnavigation-composeが追加される。
