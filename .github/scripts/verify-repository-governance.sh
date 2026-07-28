#!/usr/bin/env bash

set -euo pipefail

repository="${1:-${GITHUB_REPOSITORY:-akaitigo/ndc-shelf-android}}"
expected_description="ISBNスキャンとNDC分類で、蔵書・本棚・シリーズを端末中心に管理するプライバシー重視のAndroidアプリ。"
expected_topics="android,camerax,isbn,jetpack-compose,kotlin,ml-kit,ndc,personal-library,privacy-first,room"

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "ERROR: required command not found: $1" >&2
        exit 1
    }
}

assert_equal() {
    local name="$1"
    local actual="$2"
    local expected="$3"

    if [[ "$actual" != "$expected" ]]; then
        echo "ERROR: $name: expected '$expected', got '$actual'" >&2
        exit 1
    fi
    echo "OK: $name=$actual"
}

require_command gh
require_command jq

protection="$(gh api "repos/$repository/branches/main/protection")"
repository_state="$(gh api "repos/$repository")"
workflow_permissions="$(gh api "repos/$repository/actions/permissions/workflow")"
private_reporting="$(gh api "repos/$repository/private-vulnerability-reporting")"
automated_fixes="$(gh api "repos/$repository/automated-security-fixes")"

assert_equal "required status checks" \
    "$(jq -r '.required_status_checks.contexts | sort | join(",")' <<<"$protection")" \
    "verify"
assert_equal "strict status checks" \
    "$(jq -r '.required_status_checks.strict' <<<"$protection")" \
    "true"
assert_equal "pull request required" \
    "$(jq -r '.required_pull_request_reviews != null' <<<"$protection")" \
    "true"
assert_equal "required approvals" \
    "$(jq -r '.required_pull_request_reviews.required_approving_review_count' <<<"$protection")" \
    "0"
assert_equal "conversation resolution" \
    "$(jq -r '.required_conversation_resolution.enabled' <<<"$protection")" \
    "true"
assert_equal "linear history" \
    "$(jq -r '.required_linear_history.enabled' <<<"$protection")" \
    "true"
assert_equal "admin enforcement" \
    "$(jq -r '.enforce_admins.enabled' <<<"$protection")" \
    "true"
assert_equal "force pushes" \
    "$(jq -r '.allow_force_pushes.enabled' <<<"$protection")" \
    "false"
assert_equal "branch deletion" \
    "$(jq -r '.allow_deletions.enabled' <<<"$protection")" \
    "false"

assert_equal "description" \
    "$(jq -r '.description' <<<"$repository_state")" \
    "$expected_description"
assert_equal "topics" \
    "$(jq -r '.topics | sort | join(",")' <<<"$repository_state")" \
    "$expected_topics"
assert_equal "delete branch on merge" \
    "$(jq -r '.delete_branch_on_merge' <<<"$repository_state")" \
    "true"
assert_equal "Dependabot security updates" \
    "$(jq -r '.security_and_analysis.dependabot_security_updates.status' <<<"$repository_state")" \
    "enabled"
assert_equal "secret scanning" \
    "$(jq -r '.security_and_analysis.secret_scanning.status' <<<"$repository_state")" \
    "enabled"
assert_equal "push protection" \
    "$(jq -r '.security_and_analysis.secret_scanning_push_protection.status' <<<"$repository_state")" \
    "enabled"
assert_equal "private vulnerability reporting" \
    "$(jq -r '.enabled' <<<"$private_reporting")" \
    "true"
assert_equal "Dependabot automated security fixes" \
    "$(jq -r '.enabled and (.paused | not)' <<<"$automated_fixes")" \
    "true"
assert_equal "workflow token default" \
    "$(jq -r '.default_workflow_permissions' <<<"$workflow_permissions")" \
    "read"
assert_equal "workflow PR approval" \
    "$(jq -r '.can_approve_pull_request_reviews' <<<"$workflow_permissions")" \
    "false"

gh api "repos/$repository/vulnerability-alerts" >/dev/null
echo "OK: Dependabot vulnerability alerts=enabled"
echo "Repository governance verification passed for $repository."
