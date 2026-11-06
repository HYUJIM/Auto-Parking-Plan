# APP 법규 서버 — AWS ECS(Fargate) / 로컬 docker compose 공용 이미지
FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Seoul

WORKDIR /srv/app

COPY pyproject.toml README.md ./
COPY app ./app
COPY mockserver ./mockserver
RUN pip install --upgrade pip && pip install ".[aws,openai]"

COPY data ./data
COPY scripts ./scripts

RUN useradd --create-home --uid 10001 appuser && mkdir -p /srv/app/var/blobs && chown -R appuser /srv/app/var
# 이름 있는 볼륨은 처음 붙을 때 이미지 안 디렉터리의 소유자를 물려받는다 (blobs 를 미리 만들어 둠)
USER appuser

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=4).status==200 else 1)"

# 기본: API 서버. 워커는 command 를 ["python","-m","app.cli","worker"] 로 바꿔 실행
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
