#!/usr/bin/env bash
# 배포 스크립트 공통 함수. 다른 스크립트가 source 한다.
set -euo pipefail

STACK_NAME="${STACK_NAME:-autoparkingplan}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"
SSH_KEY_FILE="${DEPLOY_SSH_KEY_FILE:-$HOME/.ssh/${STACK_NAME}_lightsail}"
KNOWN_HOSTS_FILE="${DEPLOY_KNOWN_HOSTS_FILE:-$HOME/.ssh/${STACK_NAME}_known_hosts}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LITE_DIR="$(dirname "$SCRIPT_DIR")"
REPO_DIR="$(cd "$LITE_DIR/../.." && pwd)"

die() { echo "오류: $*" >&2; exit 1; }

# CloudFormation 스택 출력값 하나
stack_output() {
  aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

# 서버 주소: DEPLOY_HOST 환경 변수(GitHub Actions) 또는 스택 출력
server_host() {
  if [ -n "${DEPLOY_HOST:-}" ]; then echo "$DEPLOY_HOST"; else stack_output ServerIp; fi
}

# 처음 접속할 때만 호스트 키를 받아 전용 known_hosts 에 저장하고, 그 뒤로는 바뀌면 거부한다
SSH_OPTS=(-i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o UserKnownHostsFile="$KNOWN_HOSTS_FILE"
          -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -o ServerAliveInterval=30)

remote() { ssh "${SSH_OPTS[@]}" "ubuntu@$HOST" "$@"; }
