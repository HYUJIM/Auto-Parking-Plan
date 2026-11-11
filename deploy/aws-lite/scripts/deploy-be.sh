#!/usr/bin/env bash
# BE 배포: app.jar 를 빌드해 서버에 올리고 다시 띄운다. 로컬과 GitHub Actions 가 같은 스크립트를 쓴다.
#   SKIP_BUILD=1  이미 빌드한 BE/server/build/libs/app.jar 를 그대로 올린다
source "$(dirname "$0")/lib.sh"

JAR="$REPO_DIR/BE/server/build/libs/app.jar"
if [ -z "${SKIP_BUILD:-}" ]; then
  (cd "$REPO_DIR/BE" && ./gradlew --no-daemon -q :server:bootJar -x test)
fi
[ -f "$JAR" ] || die "$JAR 이 없습니다."
[ -f "$SSH_KEY_FILE" ] || die "SSH 키가 없습니다: $SSH_KEY_FILE"
HOST="$(server_host)"

# macOS 의 tar 는 확장 속성을 ._이름 파일로 끼워 넣는다. 서버가 그것을 법령 원문(JSON)으로 읽다 실패하므로 끈다
export COPYFILE_DISABLE=1

echo "서버($HOST)에 올립니다: app.jar $(du -h "$JAR" | cut -f1)"
remote 'test -f /opt/app/.server-ready || { echo "서버 초기 설정이 아직 끝나지 않았습니다. 1~2분 뒤 다시 실행하세요." >&2; exit 1; }
        rm -rf /opt/app/incoming && mkdir -p /opt/app/incoming'
tar --no-xattrs -C "$REPO_DIR/BE/server/build/libs" -cf - app.jar | remote 'tar -C /opt/app/incoming -xf -'
tar --no-xattrs -C "$REPO_DIR/BE" -cf - data | remote 'tar -C /opt/app/incoming -xf -'
tar --no-xattrs -C "$LITE_DIR/server" -cf - compose.yaml nginx.conf.template remote-deploy.sh | remote 'tar -C /opt/app/incoming -xf -'
remote 'install -m 755 /opt/app/incoming/remote-deploy.sh /opt/app/remote-deploy.sh && rm /opt/app/incoming/remote-deploy.sh && /opt/app/remote-deploy.sh'
