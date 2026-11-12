#!/usr/bin/env bash
# 서버(호스트) 설정을 코드로 맞춘다. remote-deploy.sh 가 배포 때마다 sudo 로 실행하며, 여러 번 실행해도 결과가 같다.
#   1) SSH: X11 전달·root 로그인 끔, 로그인 대기 30초 (인증은 원래 키만 허용)
#   2) 보안 업데이트 뒤 재부팅이 필요하면 새벽에 자동 재부팅 (컨테이너는 restart 정책으로 다시 뜬다)
#   3) DB 일일 백업 예약 (backup-db.sh)
# 인자: 앱 폴더(기본 /opt/app). HOST_SETUP_DRY_RUN=1 이면 서비스 재적용 없이 파일만 쓴다 (시험용).
set -euo pipefail
APP_DIR="${1:-/opt/app}"
[ "$(id -u)" = 0 ] || { echo "오류: root 로 실행해야 합니다 (sudo)." >&2; exit 1; }

# 내용이 달라졌을 때만 바꾼다. 바뀌었으면 0, 그대로면 1
install_if_changed() { # <대상> <권한> (내용은 표준 입력)
  local target="$1" mode="$2" tmp
  tmp="$(mktemp)"
  cat > "$tmp"
  if [ -f "$target" ] && cmp -s "$tmp" "$target"; then rm -f "$tmp"; return 1; fi
  install -m "$mode" -o root -g root "$tmp" "$target"
  rm -f "$tmp"
  return 0
}

# ── 1) SSH ───────────────────────────────────────────────────────────────
# sshd 는 먼저 읽은 값을 쓴다. sshd_config.d 는 본문보다 먼저 포함되므로 여기 둔 값이 이긴다.
# 포트 전달(AllowTcpForwarding)은 관리자 API 터널에 쓰므로 그대로 둔다 — 배포 전용 키는 authorized_keys 의 restrict 로 막혀 있다.
SSHD_DROPIN=/etc/ssh/sshd_config.d/10-autoparkingplan.conf
if install_if_changed "$SSHD_DROPIN" 644 <<'CONF'
# deploy/aws-lite/server/host-setup.sh 가 관리한다 (직접 고치지 말 것)
X11Forwarding no
PermitRootLogin no
LoginGraceTime 30
CONF
then
  # 문법이 틀리면 되돌리고 멈춘다 (잘못된 설정으로 sshd 를 다시 읽으면 접속이 끊길 수 있다)
  if ! sshd -t; then
    rm -f "$SSHD_DROPIN"
    echo "오류: sshd 설정 검사 실패 — SSH 설정을 되돌렸습니다." >&2
    exit 1
  fi
  [ -n "${HOST_SETUP_DRY_RUN:-}" ] || systemctl reload ssh
  echo "SSH 설정 적용: $SSHD_DROPIN"
fi

# ── 2) 보안 업데이트 뒤 자동 재부팅 ──────────────────────────────────────
# unattended-upgrades 는 커널·libc 같은 패키지를 올린 뒤 재부팅이 필요하다고만 표시한다. 한 대짜리 서버라 방치되기 쉬워 자동으로 한다.
# 시각은 서버 시간(UTC) 19:30 = 한국 04:30 (일일 스냅샷 19:00 뒤).
if install_if_changed /etc/apt/apt.conf.d/52autoparkingplan-reboot 644 <<'CONF'
// deploy/aws-lite/server/host-setup.sh 가 관리한다
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "19:30";
CONF
then
  echo "자동 재부팅 설정 적용 (재부팅이 필요할 때만, UTC 19:30)"
fi

# ── 3) DB 일일 백업 ─────────────────────────────────────────────────────
# UTC 18:10 = 한국 03:10. 04:00 디스크 스냅샷에 그날 덤프가 함께 담긴다.
install -d -m 700 -o ubuntu -g ubuntu "$APP_DIR/backups"
if install_if_changed /etc/cron.d/autoparkingplan-backup 644 <<CONF
# deploy/aws-lite/server/host-setup.sh 가 관리한다
10 18 * * * ubuntu $APP_DIR/backup-db.sh >> $APP_DIR/backups/backup.log 2>&1
CONF
then
  echo "DB 백업 예약 적용 (매일 UTC 18:10)"
fi
