# Issue #52 実装契約

## 問題

依存更新、既知脆弱性、ライセンス、Gradle供給網、SDKのデータ取扱いを、リリースごとに同じ手順で検証し証跡化する仕組みがない。

## 根拠

- アプリはCameraX、ML Kit、Room、Coil、OkHttpなどの直接・推移依存を同梱する。
- タグ指定のGitHub Actionsとチェックサム未指定のGradle配布物は、参照先の不変性を保証しない。
- Data safetyとアプリ内説明は、依存SDKの更新で変わり得る。

## 実装

- Gradle wrapperと依存artifactをSHA-256で検証する。
- GitHub Actionsを完全なcommit SHAへ固定する。
- Dependency ReviewとOSV-ScannerをCIへ追加し、例外を最長90日かつ所有者・根拠必須にする。
- CycloneDX SBOMと第三者NOTICEを生成し、CI artifactとして30日保管してGitHub Release公開時に自動添付する。
- SDK Index、通信、Data safetyのリリースレビュー手順を文書化する。

## 受け入れ条件

- 改変または未承認のGradle配布物・依存物をビルドが拒否する。
- PRでmoderate以上の新規脆弱性をDependency Reviewが拒否する。
- SBOM全体をOSVが走査し、検出時にCIが失敗する。
- OSV例外はID、90日以内の期限、GitHub所有者、根拠がなければCIが失敗する。
- SBOMと直接・推移依存のNOTICEをActionsから取得できる。
- リリース時のSDK Index・通信・Data safety再確認手順が追跡可能である。

## リスクとロールバック

外部脆弱性DBやGitHub APIの障害でCIが一時的に失敗し得る。検査自体を無効化せず再実行し、継続障害時は根拠付きで運用判断する。ロールバックは本PRのrevertとするが、wrapper checksumとdependency verificationは保護を弱めるため原則維持する。
