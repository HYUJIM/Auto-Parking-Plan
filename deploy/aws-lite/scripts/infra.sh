#!/usr/bin/env bash
# AWS 인프라 생성·갱신 (Lightsail 서버, 고정 IP, S3, CloudFront, GitHub 배포 역할).
# 비용이 발생한다. 필요 권한: CloudFormation, Lightsail, S3, CloudFront, IAM, Secrets Manager.
#   ./infra.sh            생성 또는 변경 적용
#   ./infra.sh --plan     바뀔 내용만 보기 (적용하지 않음)
source "$(dirname "$0")/lib.sh"

KEY_PAIR_NAME="${STACK_NAME}-deploy"

if [ ! -f "$SSH_KEY_FILE" ]; then
  echo "SSH 키를 만듭니다: $SSH_KEY_FILE"
  ssh-keygen -t ed25519 -N "" -C "$KEY_PAIR_NAME" -f "$SSH_KEY_FILE" >/dev/null
fi
if ! aws lightsail get-key-pair --key-pair-name "$KEY_PAIR_NAME" >/dev/null 2>&1; then
  echo "공개 키를 Lightsail 에 등록합니다: $KEY_PAIR_NAME"
  aws lightsail import-key-pair --key-pair-name "$KEY_PAIR_NAME" \
    --public-key-base64 "$(cat "$SSH_KEY_FILE.pub")" >/dev/null
fi

ARGS=(--stack-name "$STACK_NAME" --template-file "$LITE_DIR/template.yaml"
      --capabilities CAPABILITY_NAMED_IAM
      --parameter-overrides "Name=$STACK_NAME" "KeyPairName=$KEY_PAIR_NAME")
[ -n "${GITHUB_REPO:-}" ] && ARGS+=("GitHubRepo=$GITHUB_REPO")
# 저장소가 쓰는 OIDC sub 형식을 GitHub 에 물어 역할의 신뢰 조건에 그대로 넣는다 (gh 가 없으면 기본 형식)
REPO_FOR_SUB="${GITHUB_REPO:-HYUJIM/Auto-Parking-Plan}"
SUB_PREFIX="${GITHUB_SUB_PREFIX:-$(gh api "repos/$REPO_FOR_SUB/actions/oidc/customization/sub" -q '.sub_claim_prefix // ""' 2>/dev/null || true)}"
[ -n "$SUB_PREFIX" ] && ARGS+=("GitHubSubPrefix=$SUB_PREFIX")
[ -n "${BUNDLE_ID:-}" ] && ARGS+=("BundleId=$BUNDLE_ID")
# 월 예산 알림: BUDGET_EMAIL=you@example.com [BUDGET_LIMIT_USD=30] ./infra.sh  (한 번 주면 스택이 기억한다)
[ -n "${BUDGET_EMAIL:-}" ] && ARGS+=("BudgetEmail=$BUDGET_EMAIL")
[ -n "${BUDGET_LIMIT_USD:-}" ] && ARGS+=("BudgetLimitUsd=$BUDGET_LIMIT_USD")
[ "${1:-}" = "--plan" ] && ARGS+=(--no-execute-changeset)

aws cloudformation deploy "${ARGS[@]}" --no-fail-on-empty-changeset
[ "${1:-}" = "--plan" ] && exit 0

# 실수로 스택을 지우지 못하게 종료 보호를 켠다 (지우려면 콘솔이나 CLI 에서 먼저 꺼야 한다)
aws cloudformation update-termination-protection --stack-name "$STACK_NAME" --enable-termination-protection >/dev/null

echo
aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" --output table
