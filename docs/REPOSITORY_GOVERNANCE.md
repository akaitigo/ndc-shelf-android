# Repository governance

最終確認日: 2026-07-28

この文書はGitHub上の設定とrepository内の開発手順を一致させるための、NDC Shelf固有の運用正本です。repository ownerが管理する共通governanceに変更があった場合は、より厳しい安全側の基準へ同期します。

## 適用済み設定

### main protection

| Setting | Expected value | Reason |
| --- | --- | --- |
| Pull Request required | enabled、approval 0件 | solo保守を停止せず直接pushを禁止する |
| Required check | `verify` | mainで安定して存在するAndroid CI check |
| Require branches up to date | enabled | 古いbaseでのmergeを防ぐ |
| Conversation resolution | enabled | 未解決review指摘を残さない |
| Linear history | enabled | squash mergeを前提に履歴を単純化する |
| Enforce for administrators | enabled | 通常作業でadmin bypassを使わない |
| Force push | disabled | review済み履歴の置換を防ぐ |
| Branch deletion | disabled | default branchの削除を防ぐ |

`instrumentation`はPR #99で追加され、まだmainの安定checkではありません。PR #99がmainへmergeされ、mainのpushと後続PRで同名checkが成功することを確認した後、required checkへ追加します。それ以前に必須化すると既存PRを停止するため禁止します。

### Security and repository metadata

- Dependabot vulnerability alerts: enabled
- Dependabot security updates / automated fixes: enabled
- Secret Scanning: enabled
- Push Protection: enabled
- Private Vulnerability Reporting: enabled
- GitHub Actions default token: read-only、PR approval不可
- merge後のhead branch自動削除: enabled
- description: `ISBNスキャンとNDC分類で、蔵書・本棚・シリーズを端末中心に管理するプライバシー重視のAndroidアプリ。`
- topics: `android`、`camerax`、`isbn`、`jetpack-compose`、`kotlin`、`ml-kit`、`ndc`、`personal-library`、`privacy-first`、`room`

公開済みの脆弱性情報はDependabotで扱います。未公開の脆弱性、実利用者data、credentialはpublic IssueやPRへ記載せず、Private Vulnerability Reportingを使用します。

## Verification

admin権限を持つ`gh` sessionと`jq`が必要です。scriptはread-onlyで、設定を変更しません。

```bash
./.github/scripts/verify-repository-governance.sh
```

check名を変更する場合は、workflow変更をmergeして新旧両checkの実在を確認し、保護設定を更新し、scriptの期待値を同じPRで変更します。先に旧checkを削除してmerge不能にしてはいけません。

## Pull Request and merge procedure

1. 1 Issue 1 PRでDraftを作成し、関連Issueを`Closes #123`で明記する。
2. 対象test、lint、build、必要なAndroid OS・実機確認を行う。
3. security、privacy、data migration、performance、rollbackへの影響を自己reviewする。
4. base branchを最新化し、`verify`成功と未解決conversation 0件を確認する。
5. DraftをReady for reviewへ変更し、最終差分を確認する。
6. squash mergeし、自動削除されたbranchと閉じたIssueを確認する。

stacked PRは同時に大量mergeしません。最下段から1件ずつmergeし、直上PRのbase・差分・CIを再確認します。通常のstack depthは3以下とします。

## Dependabot review

Dependabot PRも自動mergeしません。1件ずつ次を確認します。

1. upstream release note、公式migration guide、既知のbreaking changeと脆弱性修正を確認する。
2. 直接・推移依存のlicenseとNOTICE要件を確認する。
3. Gradle、AGP、Kotlin、KSP、Compose、Roomはcompatibility matrixと生成物差分を確認する。
4. `verifyLicenseReport testStandardDebugUnitTest lintStandardDebug assembleStandardDebug`を実行する。
5. Android plugin、runtime、UI、databaseへ影響する更新はemulatorの`connectedStandardDebugAndroidTest`も実行する。
6. 1 PRずつsquash mergeし、main CI失敗時はmerge commitをrevertする。force pushやcheck skipは使用しない。

複数Dependabot PRが同じversion catalogまたはwrapperへ競合する場合も、内容を手作業でまとめて元PRを無条件closeしません。採用PR、重複理由、検証結果を各PRへ記録します。

## Emergency procedure

管理者にもprotectionを適用するため、通常はbypassできません。公開済みの重大障害や脆弱性で保護設定の一時変更が不可避な場合だけ、次を行います。

1. 公開可能な障害はIssue、未公開脆弱性はSecurity Advisoryへ、理由・所有者・対象setting・期限を記録する。
2. 変更前のprotection JSONをprivateな運用記録へ保存する。tokenや非公開脆弱性をrepositoryへcommitしない。
3. 必要なsettingだけを最小時間変更し、直接pushではなく可能な限りhotfix PRと成功済みcheckを維持する。
4. 24時間以内を上限として元の設定へ戻す。
5. verification scriptとGitHub APIで復旧を確認し、公開可能になった時点でpostmortemを作成する。

required check名の誤りでmerge不能になった場合は、実在するcheck名へ訂正するだけに留めます。required checks全体、force-push禁止、Secret Scanningをまとめて解除してはいけません。

## Rollback

設定変更で開発が停止した場合は、変更前のAPI snapshotと本書の期待値を比較し、原因settingだけを戻します。Security機能を無効化してrollbackしてはいけません。descriptionやtopicsはREADMEの実装状況と不一致が判明した場合に修正し、削除ではなく正しい値へ置換します。
