# テスト行列と証跡

E2E・スクリーンショット・互換性検証の正本。CIで自動化する層と、実機でしか
検証できない層を分離し、結果をリリースゲート（`docs/releases/`）の証跡へ残す。

## OS行列

| 層 | API / OS | 実行方法 | 対象 |
| --- | --- | --- | --- |
| 最小SDK | 23 (Android 6.0) | 手動実機（またはローカルAVD） | 起動、権限、Room、backup |
| 下位互換 | 26 (Android 8.0) | CIエミュレーター（`android-test`行列） | instrumentation全件 |
| 中間 | 29 (Android 10) | CIエミュレーター | instrumentation全件 |
| 最新安定 | 35 (Android 15) | CIエミュレーター | instrumentation全件 |
| 最新target | 37 | 手動実機（target挙動・権限UI） | スモーク+カメラ |

カメラ・ML Kitは実機依存が強いため、Pixel系1台と別ベンダー1台での手動確認を
リリースゲート必須項目とする（`docs/SCAN_DEVICE_TESTING.md`参照）。

## 自動化層（CI）

- **JVMスクリーンショット回帰**: `ScreenshotRegressionTest`（Robolectric +
  Roborazzi）。主要画面のライト・ダーク・大文字（fontScale 1.5）を匿名fixture
  で描画し、`app/roborazzi/` のgoldenと比較する。verifyジョブの
  `verifyRoborazziDebug` が差分で失敗し、`screenshot-diffs` artifactへ比較画像を
  出力する。goldenの更新は `./gradlew recordRoborazziDebug` の結果をレビューして
  コミットする。大文字は fontScale 1.5 に加え 2.0（library / insights / onboarding）も
  golden 化し、200%文字での切れ・重なりを検出する。
  日英スクリーンショットは#44（i18n）導入後にlocale軸を追加する。
  大画面はwindow qualifiersで medium（w720dp）と expanded（w1280dp）の代表2枚を
  golden化し、NavigationRailとlist-detail 2ペインの回帰を検出する
  （方針は `docs/ADAPTIVE_LAYOUT.md`）。
- **ウィンドウサイズクラス回帰**: `AdaptiveLayoutTest`（breakpoint境界）、
  `AdaptiveNavigationTest`（qualifiers 411 / 720 / 1280dp でのバー⇔レール切り替え、
  48dpタップ領域、キーボードのフォーカスとEnter発火）、`AdaptivePaneTest`
  （2ペイン表示、サイズクラス変更とプロセス再生成での選択・入力・スクロール保持）を
  JVM層で実行する。折りたたみの物理的な姿勢変更はエミュレーターで再現しないため、
  下記の手動実機層で確認する。
- **エミュレーターinstrumentation**: API 26 / 29 / 35 × pixel_7 で
  `connectedDebugAndroidTest`。画面単位テストに加え、`E2eManualRegistrationTest`
  がオンボーディング→手動登録→本棚表示→Activity再生成の主要フローを実DBで検証する。
- **アクセシビリティ自動チェック**: `AccessibilityChecksTest` と主要画面テスト
  （`LibrarySearchScreenTest` / `DataManagementScreenTest` / `ScanSessionPanelTest`）で
  Accessibility Test Framework（Accessibility Scanner相当）の検査を実行し、ラベル欠落・
  48dp未満のタップ領域・重複クリック領域をERROR判定で失敗させる。抑制中のチェックと
  理由は `docs/ACCESSIBILITY_AUDIT.md` の「自動チェックの範囲」を参照する。
- 検証成果は各リリースのGitHub Actions run URLをリリースチェックリストへ記録する。

- **更新インストール回帰**: `update-install`ジョブがv0.1.2（commit d852975、versionCode 3）の
  APKをビルドして`adb install`し、アプリdataへマーカーを置いてから現行debug APKを
  `adb install -r`し、data保持・署名互換・更新後起動を検証する。旧schema DBの引き継ぎは
  `LegacyDatabaseUpgradeInstrumentationTest`がAPI 26／29／35で検証する。これによりリリース
  ゲートの「旧版から更新して既存蔵書が保持される」はDB層・パッケージ層が自動証跡となり、
  実機ゲートには実UI操作と実端末の確認だけが残る。

## 手動実機層（リリースゲート証跡）

各リリース前に `docs/releases/V*_RELEASE_CHECKLIST.md` の実機ゲートで実施:

1. カメラスキャン（明所・暗所・小型バーコード）と権限拒否→手入力の代替経路
2. DocumentProviderの実プロバイダ（Drive等）でexport/import/backup往復
3. 通知（新刊候補）表示・通知拒否時の挙動
4. process death（開発者オプション「アクティビティを保持しない」）と回転
5. 低容量端末でのbackup失敗時の安全性
6. 更新インストール（旧versionCode→新versionCode）でのデータ保持
7. アクセシビリティ実機ゲート（TalkBackスモーク・Switch Access・拡大とコントラスト・
   モーション低減）。手順は `docs/ACCESSIBILITY_AUDIT.md` の「実機ゲート」に定義し、
   実走はリリースゲートで実施する。CIのATF自動チェックはこれを代替しない。
8. 大画面・折りたたみゲート（折りたたみの開閉、分割画面の幅変更、タブレット横持ち、
   物理キーボードのTab移動とEnter発火）。手順は `docs/ADAPTIVE_LAYOUT.md` の
   「実機での確認（リリースゲート）」に定義する。

結果は表形式（端末・OS・結果・日付・実施者）でチェックリストへ残し、失敗時は
再試行ではなくIssue化して原因を修正する。

## データ方針

テストデータ・スクリーンショット・証跡に実在ISBN、氏名、実際の棚位置、通知内容を
含めない。fixtureは「匿名サンプル図書」系の命名だけを使う。
