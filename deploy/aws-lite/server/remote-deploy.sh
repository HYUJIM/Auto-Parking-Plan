#!/usr/bin/env bash
# 서버에서 실행된다 (scripts/deploy-be.sh 가 올린 뒤 호출). 새 릴리스로 바꾸고 API 를 다시 띄운다.
set -euo pipefail
cd /opt/app

[ -f .env ] || { echo "오류: /opt/app/.env 가 없습니다. 먼저 scripts/server-env.sh 를 실행하세요." >&2; exit 1; }
[ -f incoming/app.jar ] || { echo "오류: 올라온 app.jar 가 없습니다." >&2; exit 1; }

cp incoming/compose.yaml incoming/nginx.conf.template .
mkdir -p blobs
# 컨테이너의 앱 사용자(10001)가 쓸 수 있게
sudo chown -R 10001:10001 blobs

# macOS 에서 올린 묶음에 섞일 수 있는 ._이름 부가 파일 제거 (법령 원문 폴더의 JSON 으로 잘못 읽힌다)
find incoming -name '._*' -delete
rm -rf release.new release.old
mkdir release.new
mv incoming/app.jar release.new/app.jar
mv incoming/data release.new/data
[ -d release ] && mv release release.old
mv release.new release
rm -rf incoming

docker compose pull -q
docker compose up -d db

if [ ! -f .bootstrapped ]; then
  echo "최초 배포: 관할·법령 원문·추출 결과를 적재합니다 (몇 분 걸릴 수 있습니다)."
  docker compose run --rm bootstrap
  touch .bootstrapped
fi

docker compose up -d --force-recreate api
docker compose up -d proxy

echo "API 가 뜰 때까지 기다립니다."
for i in $(seq 1 60); do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q api)" 2>/dev/null || echo starting)
  if [ "$status" = healthy ]; then
    echo "API 정상 (${i}번째 확인)."
    rm -rf release.old
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 5
done

echo "오류: API 가 5분 안에 정상 상태가 되지 않았습니다. 최근 로그:" >&2
docker compose logs --tail 60 api >&2
if [ -d release.old ]; then
  echo "이전 릴리스로 되돌립니다." >&2
  rm -rf release && mv release.old release
  docker compose up -d --force-recreate api
fi
exit 1
