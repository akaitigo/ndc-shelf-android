# Implementation Contract: Issue #51

## Objective

mainの必須CIを回避できず、security reportingと依存脆弱性対応が有効で、公開情報と運用手順をAPIで再検証できるrepositoryにする。

## Current State

- branch: `agent/issue-51-repository-governance`
- base: `origin/main`
- related Issue: #51
- viewer permission: `ADMIN`
- before change: rulesetなし、main protectionなし、Dependabot alerts/security updates無効、description/topics空
- existing protection: Secret ScanningとPush Protectionは有効

## Decisions

- main protectionはGitHub branch protection APIで設定する。
- solo repositoryのためPR approvalは0件とするが、PR経由、strict check、conversation解決は必須にする。
- mainで安定している`verify`だけをrequired checkとし、`instrumentation`はPR #99 merge後に追加する。
- administratorsにもprotectionを適用し、緊急変更は最小範囲・24時間以内・復旧検証を必須にする。
- security機能、description、topics、merge後branch削除を有効化する。
- repository内scriptは設定を変更せず、read-only verificationだけを行う。

## Invariants

- check名の誤設定で全PRを停止させない。
- 管理者の通常作業でもdirect push、force push、main削除を許可しない。
- 未公開脆弱性やcredentialをpublic Issue、PR、logへ出さない。
- Dependabot PRをCI成功だけで自動mergeしない。
- branch protectionを回避してstacked PRをmergeしない。

## Scope

- main branch protection
- Dependabot alerts / security updates、Secret Scanning、Push Protection、Private Vulnerability Reporting
- repository description、topics、merge後branch削除
- workflow tokenの最小権限確認
- governance verification script、運用・緊急・Dependabot手順
- PR templateとCONTRIBUTINGからの導線

## Non-goals

- dependencies自体のversion更新
- PR #99の`instrumentation`をmainへ先行移植すること
- organization ruleset、CODEOWNERS、複数reviewerの必須化
- signing key、Play Console、release automation
- GitHub planで利用できない追加security productの導入

## Required Verification

- `./.github/scripts/verify-repository-governance.sh`
- `./gradlew testDebugUnitTest lintDebug assembleDebug`
- `git diff --check`
- Draft PR作成後の`verify`成功
- protectionとsecurity settingsのAPI再取得

## Acceptance Criteria

- [x] mainへPR必須、strict update、conversation解決、linear history、force push・削除禁止を設定する。
- [x] mainで安定したAndroid CI check `verify`を必須化する。
- [x] admin enforcementと期限付き緊急手順を定義する。
- [x] Dependabot alerts / security updates、Secret Scanning、Push Protection、Private Vulnerability Reportingを有効化する。
- [x] descriptionと実装済みtopicsをREADMEに合わせて設定する。
- [x] Dependabot PRのlicense・breaking change・CI確認手順を定義する。
- [x] verification scriptとGitHub APIで最終状態を再確認する。
- [ ] Draft PRを作成し、最終HEADのGitHub Actions成功を確認する。

## Stop Conditions

- 現在存在しないcheckを必須化する必要がある。
- GitHub planまたは権限不足で設定を適用・再取得できない。
- security機能の無効化、管理者恒久bypass、force pushが必要になる。
- 既存stacked PRのbase変更またはcode変更が必要になる。
