#!/usr/bin/env bash
# 서버의 /opt/app/.env (권한 600) 를 만든다·갱신한다.
#  - 공공 API 키: 이 컴퓨터의 BE/.env 에서 읽어 SSH 로만 보낸다 (화면·명령줄 인자에 나오지 않는다)
#  - CloudFront 확인 값: Secrets Manager 에서 읽는다
#  - DB 비밀번호·관리자 토큰: 서버에 이미 있으면 그대로 두고, 없으면 서버에서 무작위로 만든다
# 키를 바꿨을 때 다시 실행하면 된다 (끝나면 API 를 다시 띄운다).
source "$(dirname "$0")/lib.sh"

ENV_FILE="${BE_ENV_FILE:-$REPO_DIR/BE/.env}"
[ -f "$ENV_FILE" ] || die "$ENV_FILE 이 없습니다 (공공 API 키를 넣은 BE/.env)."
HOST="$(server_host)"
SITE_URL="$(stack_output SiteUrl)"
SECRET_ARN="$(stack_output OriginSecretArn)"

# BE/.env 에서 값 하나 (따옴표·뒤 주석 없는 KEY=VALUE 형식)
env_value() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '\r'; }

{
  for k in JUSO_API_KEY VWORLD_API_KEY VWORLD_DOMAIN BUILDING_HUB_KEY LAWGO_OC ANTHROPIC_API_KEY; do
    echo "$k=$(env_value "$k")"
  done
  echo "LLM_PROVIDER=${LLM_PROVIDER:-none}"
  echo "CORS_ORIGINS=[\"$SITE_URL\"]"
  echo "ORIGIN_SECRET=$(aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" --query SecretString --output text)"
} | remote '
  set -euo pipefail
  umask 077
  cd /opt/app
  keep() { [ -f .env ] && grep -E "^$1=" .env | tail -1 | cut -d= -f2- || true; }
  pg="$(keep POSTGRES_PASSWORD)"; [ -n "$pg" ] || pg="$(openssl rand -hex 24)"
  admin="$(keep ADMIN_TOKEN)";    [ -n "$admin" ] || admin="$(openssl rand -base64 36 | tr -d "\n")"
  { cat; echo "POSTGRES_PASSWORD=$pg"; echo "ADMIN_TOKEN=$admin"; } > .env.new
  mv .env.new .env
  chmod 600 .env
  echo "서버 .env 갱신: $(cut -d= -f1 .env | tr "\n" " ")"
  if [ -f compose.yaml ] && [ -d release ]; then
    docker compose up -d --force-recreate api proxy
  fi
'
