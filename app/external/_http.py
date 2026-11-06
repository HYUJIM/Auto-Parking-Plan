"""
외부 API 공통 HTTP 처리 (동기 httpx).

  * 기본 시간 제한 3.0초 (프로토타입 API 명세). httpx 의 시간 제한은 단계별(connect/read/write/pool)이다.
  * GET 은 시간 초과·연결 오류·5xx 에 한해 1회 재시도하고, 그래도 실패하면 UpstreamError.
  * 인증키는 예외 메시지·로그에 절대 넣지 않는다. httpx 자체 로거("httpx")가 INFO 로 남기는 요청 URL 에서도
    key·confmKey·serviceKey 값을 가린다(필터 1회 설치).
"""

from __future__ import annotations

import json
import logging
import re
from collections.abc import Iterable, Mapping
from datetime import UTC, datetime
from typing import Any

import httpx

from app.external.types import UpstreamError

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 3.0
DEFAULT_RETRIES = 1
_SECRET_PARAM_RE = re.compile(r"(?i)\b((?:confm|service|api)?key)=([^&\s\"']+)")


def redact_secrets(text: str, secrets: Iterable[str] = ()) -> str:
    """문자열에서 인증키 파라미터 값과 주어진 비밀값을 '***' 로 가린다."""
    out = _SECRET_PARAM_RE.sub(r"\1=***", text)
    for s in secrets:
        if s and len(s) >= 4:
            out = out.replace(s, "***")
    return out


class _RedactKeysFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:  # pragma: no cover - 로깅 설정에 따라 다름
        try:
            msg = record.getMessage()
        except Exception:
            return True
        redacted = redact_secrets(msg)
        if redacted != msg:
            record.msg = redacted
            record.args = ()
        return True


_httpx_logger = logging.getLogger("httpx")
if not any(isinstance(f, _RedactKeysFilter) for f in _httpx_logger.filters):
    _httpx_logger.addFilter(_RedactKeysFilter())


def utcnow() -> datetime:
    return datetime.now(UTC)


class HttpJsonClient:
    """서비스 하나(juso/vworld/building_hub)에 대한 GET+JSON 호출기."""

    def __init__(
        self,
        service: str,
        *,
        timeout: float = DEFAULT_TIMEOUT,
        client: httpx.Client | None = None,
        retries: int = DEFAULT_RETRIES,
        secrets: Iterable[str] = (),
    ) -> None:
        if timeout <= 0:
            raise ValueError("timeout 은 0보다 커야 합니다")
        self.service = service
        self.timeout = float(timeout)
        self.retries = max(0, int(retries))
        self._secrets = tuple(s for s in secrets if s)
        self._owns_client = client is None
        self._client = client if client is not None else httpx.Client(timeout=self.timeout)
        # starlette TestClient(httpx2 기반) 등 httpx.Client 가 아닌 객체는 요청별 timeout 인자를 받지 않는다
        self._per_request_timeout = isinstance(self._client, httpx.Client)

    # --- 수명 관리 ---------------------------------------------------------------------------
    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def scrub(self, text: object) -> str:
        """원천이 돌려준 메시지에서도 인증키를 가린다."""
        return redact_secrets(str(text), self._secrets)

    # --- 호출 --------------------------------------------------------------------------------
    def get(self, url: str, params: Mapping[str, Any], *, endpoint: str) -> httpx.Response:
        """GET 1회 + (시간 초과·연결 오류·5xx 이면) 재시도. 5xx 가 끝까지 나면 UpstreamError.

        4xx 는 응답을 그대로 돌려준다(공공데이터포털처럼 4xx 본문에 오류 코드를 담는 API 가 있어서).
        """
        attempts = 1 + self.retries
        last_error: UpstreamError | None = None
        for attempt in range(1, attempts + 1):
            try:
                if self._per_request_timeout:
                    resp = self._client.get(url, params=dict(params), timeout=self.timeout)
                else:
                    resp = self._client.get(url, params=dict(params))
            except httpx.TimeoutException as exc:
                last_error = UpstreamError(
                    self.service,
                    f"{endpoint} 요청 시간 초과({self.timeout:g}초, {type(exc).__name__})",
                    retriable=True,
                )
            except httpx.TransportError as exc:
                last_error = UpstreamError(self.service, f"{endpoint} 연결 오류({type(exc).__name__})", retriable=True)
            else:
                if resp.status_code >= 500:
                    last_error = UpstreamError(
                        self.service,
                        f"{endpoint} 서버 오류(HTTP {resp.status_code})",
                        retriable=True,
                        status_code=resp.status_code,
                    )
                else:
                    return resp
            if attempt < attempts:
                logger.warning(
                    "[%s] %s 재시도 %d/%d: %s", self.service, endpoint, attempt, self.retries, last_error.message
                )
        assert last_error is not None
        raise last_error

    def get_json(self, url: str, params: Mapping[str, Any], *, endpoint: str) -> tuple[int, Any]:
        """GET 후 JSON 해석. (HTTP 상태코드, 본문) 을 돌려준다. JSON 이 아니면 UpstreamError."""
        resp = self.get(url, params, endpoint=endpoint)
        return resp.status_code, self.decode_json(resp, endpoint=endpoint)

    def decode_json(self, resp: httpx.Response, *, endpoint: str) -> Any:
        try:
            try:
                return json.loads(resp.content)  # UTF-8/16/32 자동 판별
            except UnicodeDecodeError:
                return json.loads(resp.content.decode(resp.encoding or "utf-8", errors="replace"))
        except ValueError:
            raise UpstreamError(
                self.service,
                f"{endpoint} 응답이 JSON 이 아닙니다(HTTP {resp.status_code})",
                retriable=resp.status_code >= 500,
                status_code=resp.status_code,
            ) from None


def join_url(base_url: str, path: str) -> str:
    return base_url.rstrip("/") + "/" + path.lstrip("/")


def to_float(value: Any) -> float | None:
    """'133361.6' / 133361.6 → float. 빈 값·숫자 아님 → None."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int | float):
        return float(value)
    s = str(value).strip().replace(",", "")
    if not s:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def to_positive_float(value: Any) -> float | None:
    """0 이하(원천의 '값 없음' 표기로 보임)는 None. None = 미확인 원칙."""
    v = to_float(value)
    return v if v is not None and v > 0 else None


def to_positive_int(value: Any) -> int | None:
    v = to_float(value)
    if v is None or v <= 0:
        return None
    return int(round(v))


def clean_str(value: Any) -> str | None:
    if value is None:
        return None
    s = str(value).strip()
    return s or None
