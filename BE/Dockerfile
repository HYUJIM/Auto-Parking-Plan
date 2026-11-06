# APP 법규 서버 — AWS ECS(Fargate) / 로컬 docker compose 공용 이미지
FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Asia/Seoul

WORKDIR /srv/app

COPY pyproject.toml README.md ./
COPY app ./app
COPY mockserver ./mockserver
RUN pip install --upgrade pip && pip install ".[aws,openai]" && rm -rf build ./*.egg-info

COPY data ./data
COPY scripts ./scripts

# 코드(/srv/app)는 root 소유·읽기 전용으로 두고, 앱 사용자는 쓰기 경로(var/blobs: 로컬 모드 원문 보관)만 가진다.
# 이름 있는 볼륨은 처음 붙을 때 이미지 안 디렉터리의 소유자를 물려받는다 (blobs 를 미리 만들어 둠)
RUN groupadd --gid 10001 appuser \
 && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin appuser \
 && mkdir -p /srv/app/var/blobs && chown -R 10001:10001 /srv/app/var
# 운영(ECS)은 readonlyRootFilesystem 이므로 임시 파일(tempfile, 원문 재수집)은 /tmp 볼륨에만 쓴다.
# Fargate 는 VOLUME 경로와 같은 containerPath 의 바인드 마운트에 이미지의 디렉터리(권한 1777 포함)를 복사한다.
VOLUME ["/tmp"]
# 숫자 UID:GID — 오케스트레이터가 non-root 여부를 이미지 메타데이터만으로 확인할 수 있게 한다
USER 10001:10001

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=4).status==200 else 1)"

# 기본: API 서버. 워커는 command 를 ["python","-m","app.cli","worker"] 로 바꿔 실행
# 프록시 헤더(X-Forwarded-For/Proto)는 FORWARDED_ALLOW_IPS 환경변수의 주소에서 온 것만 믿는다 (기본 127.0.0.1).
# AWS 에서는 Terraform 이 VPC 대역(=ALB)을 넣는다. '*' 는 클라이언트가 넣은 값으로 IP 가 위조되므로 쓰지 않는다.
# 앱의 요청 제한·관리자 잠금은 X-Forwarded-For 와 TRUSTED_PROXY_HOPS(ALB=1)로 실제 IP 를 따로 계산한다.
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--no-server-header"]
