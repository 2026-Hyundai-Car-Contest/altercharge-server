#!/usr/bin/env bash
set -Eeuo pipefail

fail() { printf '%s\n' "$*" >&2; exit 1; }

command -v gh >/dev/null 2>&1 || fail 'GitHub CLI(gh)를 설치한 뒤 다시 실행하세요.'
gh auth status >/dev/null 2>&1 || fail 'gh auth login으로 GitHub에 로그인한 뒤 다시 실행하세요.'

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
repository=$(gh repo view --json nameWithOwner --jq '.nameWithOwner')
enabled=$(gh variable get DEPLOY_ENABLED --repo "$repository" 2>/dev/null) \
    || fail '저장소 변수 DEPLOY_ENABLED를 확인할 수 없습니다. 운영 준비 후 true로 설정하세요.'
[[ "$enabled" == true ]] || fail 'DEPLOY_ENABLED=true인 경우에만 배포 요청을 보낼 수 있습니다.'

# The workflow independently verifies the current main SHA and its successful CI.
# Runtime .env validation happens on EC2; this command never reads or sends local secrets.
gh workflow run deploy.yml --ref main --repo "$repository"
printf '%s\n' '배포 워크플로 실행을 요청했습니다. 실제 배포 결과는 GitHub Actions에서 확인하세요.'
