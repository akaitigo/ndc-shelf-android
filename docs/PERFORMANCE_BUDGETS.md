# 性能予算

起動・画面表示・スクロール・データベース・スキャン・同期・配布サイズの予算値と測定方法の正本。
蔵書検索の詳細な予算と採用判断は `docs/LIBRARY_SEARCH_PERFORMANCE.md` を併読する。

基準環境は **Pixel 7相当 / API 35以上 / release相当ビルド（R8有効）** とし、
代表データ量は1,000・5,000・20,000冊（`docs/LIBRARY_SEARCH_PERFORMANCE.md` と同じ匿名生成分布）とする。
測定fixtureに個人蔵書を含めることは禁止で、匿名生成データだけを使う。

## 測定層の分離

flakyな時間指標を必須CIへ入れない方針（Issue #48）に従い、指標を3層に分ける。

| 層 | 実行タイミング | 判定 | 対象指標 |
| --- | --- | --- | --- |
| 1. CI安定層 | 毎PR（`android.yml` verify）＋リリース（`release.yml`） | 予算超過でCI失敗 | EXPLAIN QUERY PLANの索引使用、DBファイル容量、Migration完走・行数保存、検索結果の正しさ（時間はスモーク上限のみ）、リリースAABサイズ |
| 2. 準安定層（Macrobenchmark） | `benchmark.yml`（workflow_dispatch）またはローカルのGradle Managed Device | 予算と比較して人が判定・記録 | cold/warm起動時間、Baseline Profile有無の差分 |
| 3. 実機ラボ層 | リリース前チェックリスト（`docs/releases/`） | 実機実測を記録し予算と比較 | 実機起動時間、主要画面初回表示、スクロールjank、連続スキャンfps・発熱・電池、同期・新刊ウォッチの通信量・wakeup、Play Vitals |

エミュレーター（GMD含む）の絶対時間は共有ランナーの負荷で大きくばらつくため、
層2は「Baseline Profileあり/なしの相対差」と回帰傾向の観測に使い、合否の正本は層3の実機実測とする。

## 起動

| 指標 | 予算 | 測定 | 層 |
| --- | ---: | --- | --- |
| cold start（TTID）p95 | 1,200 ms以下 | Macrobenchmark `StartupTimingMetric` / 実機 `adb shell am start -W` | 2・3 |
| warm start（TTID）p95 | 600 ms以下 | 同上（`StartupMode.WARM`） | 2・3 |
| Baseline Profile効果 | cold start中央値をprofileなし比で有意に短縮（悪化しない） | `:baselineprofile` の `StartupBenchmark` | 2 |
| Play Vitals「起動が遅い」割合 | cold 5秒超 0.1%未満 | Play Console Vitals | 3 |

Baseline Profileは `:baselineprofile` モジュールの `BaselineProfileGenerator` で生成し、
`app/src/release/generated/baselineProfiles/` へコミットする（`saveInSrc = true`、
通常ビルドでの自動再生成は無効）。再生成は
`.github/workflows/benchmark.yml` のworkflow_dispatchか、KVMのある環境での
`./gradlew :app:generateReleaseBaselineProfile` で行う。

## 主要画面の初回表示と一覧スクロール

| 指標 | 5,000冊 | 20,000冊 | 測定 | 層 |
| --- | ---: | ---: | --- | --- |
| 本棚（一覧）初回表示p95 | 700 ms以下 | 1,250 ms以下 | 起動予算500ms＋初期取得予算（検索性能予算の「条件なし初期取得」） | 3 |
| 検索絞り込みp95 | 150 ms以下 | 250 ms以下 | `docs/LIBRARY_SEARCH_PERFORMANCE.md` と同一 | 3 |
| 一覧スクロールのフレームp95 | 16.7 ms以下 | 16.7 ms以下 | Macrobenchmark `FrameTimingMetric` / 実機GPUプロファイル | 3 |
| janky frames率（スクロール中） | 5%未満 | 5%未満 | Play Vitals / `dumpsys gfxinfo` | 3 |

検索の正しさ（結果・順序・キャンセル）はJVM回帰テスト
`LibrarySearchQueryTest` が毎CIで検証し、時間はスモーク上限（5秒）だけを断言する。

## データベース

### 容量（層1、毎CIで判定）

`DatabaseFootprintBudgetTest` が匿名fixtureを投入し、WAL checkpoint後のDBファイル
（本体＋WAL＋SHM）の合計サイズを予算と比較する。

| 冊数 | 実測（2026-07-30） | 予算 |
| ---: | ---: | ---: |
| 1,000 | 778,240 B | 1,200,000 B |
| 5,000 | 2,232,320 B | 3,500,000 B |
| 20,000 | 7,823,360 B | 12,000,000 B |

予算は実測+約50%。超過時はスキーマ・索引の増分を調査し、正当な増加であれば
実測根拠をこの表とテスト定数の両方で更新する。

### クエリ計画（層1、毎CIで判定）

`QueryPlanIndexUsageTest` がEXPLAIN QUERY PLANで次を断言する。

- 蔵書検索（テキスト・読書状態・選択Edition）: JOIN先（editions/works/tiers/shelves/rooms）は
  すべて索引/主キーのSEARCHで解決し、full scanはLIKE部分一致の駆動表1つまで
- ISBN・copyId単発検索: full scanゼロ（`index_book_editions_isbn13` 等を使用）
- シリーズ: `seriesId` 索引・`workId` 索引の使用
- 同期キュー: `index_sync_operations_state_deviceId_counter`・`index_sync_operations_deviceId_counter` の使用、full scanゼロ
- 新刊ウォッチ: `index_series_watches_enabled`・`index_series_release_candidates_seriesId` の使用、full scanゼロ

### Migration（層1で完走、層3で時間）

- 完走: `DatabaseFootprintBudgetTest` がv1スキーマへ20,000冊を投入し、最新版まで全Migrationを
  実行して全行保存と外部キー整合を断言する（時間は断言しない）
- 時間予算（実機・参考）: 20,000冊でアプリ起動をブロックするMigrationは合計5秒以下。
  超える場合は分割やバックグラウンド移行を検討し、ADRへ記録する

### export / import（層3、リリース前に実測）

| 指標 | 5,000冊 | 20,000冊 |
| --- | ---: | ---: |
| CSVエクスポート完了 | 5 秒以下 | 15 秒以下 |
| CSVインポート完了（検証込み） | 15 秒以下 | 60 秒以下 |
| エクスポート中の追加ヒープ | 32 MiB以下 | 64 MiB以下 |

測定は匿名fixtureをインポートした実機で行い、`docs/releases/` のチェックリストへ記録する。

## 配布サイズ（層1、release workflowで判定）

| 指標 | 実測（2026-07-29、R8有効・未署名） | 予算 |
| --- | ---: | ---: |
| リリースAAB（`app-release.aab`） | 17,590,996 B | 21,000,000 B |

`:app:verifyReleaseBundleSize` が `bundleRelease` の成果物サイズを予算と比較する。
`bundleRelease` は毎PRのverifyには重いため必須ジョブへは入れず、
`release.yml`（リリース時に必ず実行）と `benchmark.yml`（workflow_dispatch）で実行する。
主なサイズ要因はML Kitバーコードモデルと `libbarhopper_v3.so`（4 ABI）で、
超過時はABI別配信・依存の見直しを先に検討し、正当な増加であればこの表と
タスク定数の両方を更新する。

## 連続スキャン（層3）

`docs/SCAN_DEVICE_TESTING.md` の手順（2系統以上の実機、100冊相当連続読取り）に予算を重ねる。

| 指標 | 予算 | 測定 |
| --- | ---: | --- |
| プレビュー描画 | 24 fps以上を維持 | `dumpsys gfxinfo` またはGPUプロファイルバー |
| 読取り成功の間隔p50（通常照明） | 1.5 秒以下 | スキャン100冊相当のセッションログ |
| 15分連続スキャンの電池消費 | 6%以下 | `adb shell dumpsys batterystats` |
| 15分連続スキャンの熱状態 | `THERMAL_STATUS_MODERATE` 以下（サーマルスロットリングによるfps半減なし） | `adb shell dumpsys thermalservice` |

## 同期・新刊ウォッチ（層3＋設計検証）

| 指標 | 予算 | 測定・検証 |
| --- | ---: | --- |
| 新刊ウォッチ1回の通信量 | 有効ウォッチ20件で500 KiB以下 | 実機のネットワーク使用量（`dumpsys netstats` / Studio Network Inspector） |
| 同期1サイクルの通信量（差分100操作） | 1 MiB以下 | 同上 |
| バックグラウンドwakeup | 新刊ウォッチは一意周期Work1件のみ、同期はユーザー操作起点＋周期Work1件以内 | WorkManager Inspectorで一意Work名と件数を確認 |
| 失敗時の再試行 | 上限付き指数バックオフ＋jitter（`docs/SYNC_PROTOCOL.md`）、`battery-not-low`・ネットワーク接続制約付き | WorkManager Inspector（`docs/SERIES_WATCH_DEVICE_TESTING.md`） |
| 電池 | Play Vitals「過度なwakeup」警告ゼロ | Play Console Vitals |

## 実機ラボ測定手順と記録先

1. リリース候補のAABから `bundletool` でuniversal APKを作成し、系統A/B実機（`docs/SCAN_DEVICE_TESTING.md` と同基準）へインストールする
2. 匿名fixture（`tools/benchmark_library_search.main.kts` と同分布のCSV）を5,000冊・20,000冊インポートする
3. 起動（`am start -W` を10回、中央値とp95）→ 一覧スクロール（`dumpsys gfxinfo`）→ export/import → 連続スキャン15分 → 新刊ウォッチ・同期の通信量の順に測定する
4. 結果と機種・Android版・ビルド番号を `docs/releases/` の該当リリースチェックリストへ記録し、予算超過があればリリースブロッカーとして起票する
5. リリース後1週間のPlay Vitals（起動・jank・wakeup）を確認して同じチェックリストへ追記する

## CIでの実行まとめ

- 毎PR（`android.yml` verify・変更なしで従来通り）: `testDebugUnitTest` に含まれる
  `QueryPlanIndexUsageTest`・`DatabaseFootprintBudgetTest`・`LibrarySearchQueryTest`
- リリース（`release.yml`）: 上記＋`:app:verifyReleaseBundleSize`
- 手動（`benchmark.yml` workflow_dispatch）: Gradle Managed Device（Pixel 7 / API 35 / aosp）での
  `StartupBenchmark` 実行、Baseline Profile再生成、AABサイズ検証
