#!/usr/bin/env bash
# FE 배포: 빌드해서 S3 에 올리고 CloudFront 캐시를 비운다. 로컬과 GitHub Actions 가 같은 스크립트를 쓴다.
#   SKIP_BUILD=1  이미 빌드한 FE/dist 를 그대로 올린다
#   FE_BUCKET, CF_DISTRIBUTION_ID 를 주면 스택을 조회하지 않는다 (GitHub Actions)
source "$(dirname "$0")/lib.sh"

DIST="$REPO_DIR/FE/dist"
if [ -z "${SKIP_BUILD:-}" ]; then
  # 같은 주소의 /v1 을 부르도록 VITE_API_BASE 는 비운다. 지도 관련 값도 비워 개발자의 FE/.env 가 운영 빌드에 섞이지 않게 한다
  # (네이버 키나 다른 타일 주소가 들어가면 운영의 CSP 가 그 호스트를 막아 지도가 비어 보인다)
  (cd "$REPO_DIR/FE" && npm ci --ignore-scripts \
    && VITE_API_BASE= VITE_NAVER_MAP_KEY_ID= VITE_MAP_TILE_URL= VITE_MAP_TILE_URL_SATELLITE= npm run build)
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
# 3) 지난 빌드의 자산은 바로 지우지 않는다: 예전 index.html 을 열어 둔 사용자가 그 자산을 아직 받을 수 있어야 한다
#    (바로 지우면 그 사용자는 JS 가 403 이 되어 빈 화면을 본다). 이번 빌드에 없고 올린 지 7일 넘은 것만 지운다
KEEP_DAYS="${ASSET_KEEP_DAYS:-7}"
CUTOFF="$(date -u -d "-${KEEP_DAYS} days" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-"${KEEP_DAYS}"d +%Y-%m-%dT%H:%M:%SZ)"
aws s3api list-objects-v2 --bucket "$BUCKET" --prefix assets/ \
  --query "Contents[?LastModified<'$CUTOFF'].Key" --output text 2>/dev/null | tr '\t' '\n' | while read -r key; do
  [ -n "$key" ] && [ "$key" != "None" ] || continue
  [ -f "$DIST/$key" ] && continue # 이번 빌드도 쓰는 파일
  aws s3 rm "s3://$BUCKET/$key" --only-show-errors
done

aws cloudfront create-invalidation --distribution-id "$DIST_ID" --paths "/*" \
  --query "Invalidation.Id" --output text
echo "FE 배포 완료: s3://$BUCKET"
