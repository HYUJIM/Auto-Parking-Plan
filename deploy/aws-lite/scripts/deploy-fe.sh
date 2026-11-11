#!/usr/bin/env bash
# FE 배포: 빌드해서 S3 에 올리고 CloudFront 캐시를 비운다. 로컬과 GitHub Actions 가 같은 스크립트를 쓴다.
#   SKIP_BUILD=1  이미 빌드한 FE/dist 를 그대로 올린다
#   FE_BUCKET, CF_DISTRIBUTION_ID 를 주면 스택을 조회하지 않는다 (GitHub Actions)
source "$(dirname "$0")/lib.sh"

DIST="$REPO_DIR/FE/dist"
if [ -z "${SKIP_BUILD:-}" ]; then
  # 같은 주소의 /v1 을 부르도록 VITE_API_BASE 는 비운다
  (cd "$REPO_DIR/FE" && npm ci --ignore-scripts && VITE_API_BASE= npm run build)
fi
[ -f "$DIST/index.html" ] || die "$DIST/index.html 이 없습니다."
BUCKET="${FE_BUCKET:-$(stack_output FeBucketName)}"
DIST_ID="${CF_DISTRIBUTION_ID:-$(stack_output DistributionId)}"

# 1) 해시 파일명 자산: 1년 캐시. 먼저 올려서 새 index.html 이 가리킬 파일이 항상 있게 한다
aws s3 sync "$DIST/assets" "s3://$BUCKET/assets" --only-show-errors \
  --cache-control "public,max-age=31536000,immutable"
# 2) index.html 등 나머지: 매번 확인. 이번 빌드에 없는 파일은 지운다
aws s3 sync "$DIST" "s3://$BUCKET" --only-show-errors --delete \
  --exclude "assets/*" --cache-control "no-cache"
aws s3 sync "$DIST/assets" "s3://$BUCKET/assets" --only-show-errors --delete \
  --cache-control "public,max-age=31536000,immutable"

aws cloudfront create-invalidation --distribution-id "$DIST_ID" --paths "/*" \
  --query "Invalidation.Id" --output text
echo "FE 배포 완료: s3://$BUCKET"
