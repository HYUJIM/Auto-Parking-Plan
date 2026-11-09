# APP 법규 서버 (스프링판) — AWS ECS(Fargate) / 로컬 docker compose 공용 이미지
# 1단계: Gradle 로 실행 파일(app.jar) 빌드 (테스트는 CI 에서 돌린다)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# 의존성 먼저 받아 두면 소스만 바뀔 때 레이어를 재사용한다
COPY common/build.gradle.kts common/
COPY external/build.gradle.kts external/
COPY legal/build.gradle.kts legal/
COPY parking/build.gradle.kts parking/
COPY ai/build.gradle.kts ai/
COPY db/build.gradle.kts db/
COPY server/build.gradle.kts server/
RUN ./gradlew --no-daemon -q :server:dependencies --configuration runtimeClasspath > /dev/null
COPY common/src common/src
COPY external/src external/src
COPY legal/src legal/src
COPY parking/src parking/src
COPY ai/src ai/src
COPY db/src db/src
COPY server/src server/src
RUN ./gradlew --no-daemon -q :server:bootJar -x test

# 2단계: 실행 이미지 (JRE 만)
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Seoul \
    LANG=C.UTF-8 \
    APP_HOME=/srv/app \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp"
WORKDIR /srv/app
COPY --from=build /build/server/build/libs/app.jar ./app.jar
COPY data ./data

# 코드(/srv/app)는 root 소유·읽기 전용으로 두고, 앱 사용자는 쓰기 경로(var/blobs: 로컬 모드 원문 보관)만 가진다.
# 이름 있는 볼륨은 처음 붙을 때 이미지 안 디렉터리의 소유자를 물려받는다 (blobs 를 미리 만들어 둠)
RUN groupadd --gid 10001 appuser \
 && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin appuser \
 && mkdir -p /srv/app/var/blobs && chown -R 10001:10001 /srv/app/var
# 운영(ECS)은 readonlyRootFilesystem 이므로 임시 파일(원문 재수집, JVM 임시 파일)은 /tmp 볼륨에만 쓴다.
VOLUME ["/tmp"]
USER 10001:10001

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8000 && printf "GET /healthz HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && head -1 <&3 | grep -q " 200"'

# 기본: API 서버. 워커는 command 를 ["java","-jar","/srv/app/app.jar","worker"] 로 바꿔 실행.
# 서버는 X-Forwarded-For 로 연결 주소를 바꾸지 않는다 (server.forward-headers-strategy=none).
# 요청 제한·관리자 잠금의 실제 클라이언트 IP 는 TRUSTED_PROXY_HOPS(ALB=1)로 X-Forwarded-For 맨 뒤 값을 쓴다.
ENTRYPOINT ["java", "-jar", "/srv/app/app.jar"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8000"]
