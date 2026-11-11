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
[ -n "${BUNDLE_ID:-}" ] && ARGS+=("BundleId=$BUNDLE_ID")
[ "${1:-}" = "--plan" ] && ARGS+=(--no-execute-changeset)

aws cloudformation deploy "${ARGS[@]}" --no-fail-on-empty-changeset
[ "${1:-}" = "--plan" ] && exit 0

echo
aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
  --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" --output table
