#!/usr/bin/env bash
# PostgreSQL 논리 백업 (cron 이 매일 실행, host-setup.sh 가 예약한다). 최근 7개만 남긴다.
# 디스크 스냅샷은 서버 전체를 되돌리는 용도이고, 이 덤프는 데이터만 특정 날짜로 되돌리거나 다른 서버로 옮길 때 쓴다.
#   복원: docker compose exec -T db pg_restore -U app -d app --clean --if-exists < backups/app-YYYY-MM-DD.dump
set -euo pipefail
cd "$(dirname "$0")"
umask 077
mkdir -p backups
out="backups/app-$(date -u +%F).dump"
# 실패한 덤프가 정상 파일을 덮어쓰지 않게 임시 파일에 쓴 뒤 바꾼다
docker compose exec -T db pg_dump -U app -d app --format=custom > "$out.tmp"
[ -s "$out.tmp" ] || { echo "$(date -u +%FT%TZ) 오류: 빈 덤프" >&2; rm -f "$out.tmp"; exit 1; }
mv "$out.tmp" "$out"
ls -1t backups/app-*.dump | tail -n +8 | xargs -r rm -f
echo "$(date -u +%FT%TZ) 백업 완료: $out ($(du -h "$out" | cut -f1))"
# 로그도 무한히 자라지 않게 최근 200줄만
if [ -f backups/backup.log ]; then tail -n 200 backups/backup.log > backups/backup.log.tmp && mv backups/backup.log.tmp backups/backup.log; fi
