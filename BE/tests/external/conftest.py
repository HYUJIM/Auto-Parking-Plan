"""tests/external 공통 도우미: 응답 fixture 로더, httpx MockTransport 클라이언트, mock 서버(TestClient/실서버 스레드)."""

from __future__ import annotations

import json
import socket
import threading
import time
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import httpx
import pytest
import uvicorn
from starlette.testclient import TestClient

from mockserver.app import create_app

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "external"
MOCK_BASE = "http://testserver"  # starlette TestClient 기본 base_url


def load_fixture(name: str) -> Any:
    """tests/fixtures/external/<name>. observed_* = 2026-11-06 실호출 응답, synthetic_* = 공식 필드표로 만든 가상 응답."""
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class Recorder:
    """httpx.MockTransport 핸들러. 응답(또는 예외)을 순서대로 돌려주고 요청을 기록한다. 마지막 항목은 반복."""

    def __init__(self, *outcomes: Any) -> None:
        self.outcomes = list(outcomes)
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        idx = min(len(self.requests) - 1, len(self.outcomes) - 1)
        out = self.outcomes[idx]
        if callable(out) and not isinstance(out, httpx.Response):
            out = out(request)
        if isinstance(out, BaseException):
            raise out
        if isinstance(out, httpx.Response):
            return out
        return httpx.Response(200, json=out)

    @property
    def params(self) -> httpx.QueryParams:
        return self.requests[-1].url.params


def json_response(payload: Any, status: int = 200) -> httpx.Response:
    return httpx.Response(status, json=payload)


@pytest.fixture
def mock_http() -> Callable[..., tuple[httpx.Client, Recorder]]:
    """mock_http(outcome1, outcome2, ...) → (httpx.Client, Recorder)."""
    clients: list[httpx.Client] = []

    def factory(*outcomes: Any) -> tuple[httpx.Client, Recorder]:
        rec = Recorder(*outcomes)
        client = httpx.Client(transport=httpx.MockTransport(rec))
        clients.append(client)
        return client, rec

    yield factory
    for c in clients:
        c.close()


@pytest.fixture(scope="module")
def mock_app():
    return create_app(slow_delay_s=0.05)


@pytest.fixture
def test_client(mock_app) -> Iterator[TestClient]:
    with TestClient(mock_app) as client:
        yield client


@pytest.fixture(scope="session")
def live_mock_url() -> Iterator[str]:
    """uvicorn 을 백그라운드 스레드로 띄운 실제 HTTP mock 서버(시간 초과 시험용, slow 지연 1.0초)."""
    app = create_app(slow_delay_s=1.0)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    config = uvicorn.Config(app, log_level="warning", lifespan="off", loop="asyncio", http="h11", access_log=False)
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, kwargs={"sockets": [sock]}, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not server.started:
        if time.monotonic() > deadline or not thread.is_alive():
            raise RuntimeError("mock 서버가 시작되지 않았습니다")
        time.sleep(0.02)
    try:
        yield f"http://127.0.0.1:{port}"
    finally:
        server.should_exit = True
        thread.join(timeout=5)
        sock.close()
