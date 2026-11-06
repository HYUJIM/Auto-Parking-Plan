"""
요청 제한 (프로세스 내 토큰 버킷) + 관리자 인증 실패 잠금 + 실제 클라이언트 IP 판별.

  * 비로그인 공개 API 라서 요청 하나가 외부 공공 API 호출(일일 할당량)·DB 쓰기·배치 계산으로 이어진다.
    IP 별로 묶음(경로 종류)마다 분당 허용량을 두어, 한 사람이 할당량을 태우거나 서버를 붙잡지 못하게 한다.
  * 이 제한은 컨테이너(태스크) 하나 안에서만 센다. 여러 태스크 전체를 묶는 제한은 앞단 AWS WAF 의
    rate-based 규칙이 맡는다 (deploy/aws). 둘 다 있어야 한다.
  * 시계는 time.monotonic — 몇 초~몇 분 단위 계산이라 윈도우의 15.6ms 해상도로도 충분하다.
"""
from __future__ import annotations

import ipaddress
import math
import threading
import time
from collections import OrderedDict, deque
from collections.abc import Callable
from dataclasses import dataclass


def client_ip(scope: dict, trusted_proxy_hops: int = 0) -> str:
    """요청을 보낸 실제 IP.

    trusted_proxy_hops = 앞단에 있는 '우리' 프록시 수 (AWS ALB 하나면 1). 프록시는 자기가 본 IP 를
    X-Forwarded-For 맨 뒤에 덧붙이므로 뒤에서 hops 번째 값만 믿는다. 앞쪽 값은 클라이언트가 마음대로
    넣을 수 있어 믿지 않는다 (uvicorn --forwarded-allow-ips '*' 는 맨 앞 값을 쓰므로 위조된다).

    hops=0 이면 연결한 쪽 주소(scope client)를 쓴다. 그러려면 서버가 믿지 않는 곳의 X-Forwarded-For 로 그 값을
    바꾸지 않아야 한다 — `app.cli serve` 는 proxy_headers=False, 도커 이미지는 FORWARDED_ALLOW_IPS(기본 127.0.0.1, AWS 는 VPC 대역).
    """
    peer = (scope.get("client") or ("unknown", 0))[0] or "unknown"
    if trusted_proxy_hops <= 0:
        return peer
    values = [v.decode("latin-1") for k, v in scope.get("headers") or () if k == b"x-forwarded-for"]
    parts = [p.strip() for p in ",".join(values).split(",") if p.strip()]
    if len(parts) >= trusted_proxy_hops:
        cand = parts[-trusted_proxy_hops]
        try:
            return str(ipaddress.ip_address(cand))
        except ValueError:
            pass
    return peer


@dataclass(frozen=True)
class Policy:
    name: str
    per_minute: int
    burst: int


class TokenBucketLimiter:
    """(정책, IP) 별 토큰 버킷. 오래 안 쓴 키는 max_keys 를 넘으면 버린다 (메모리 상한)."""

    def __init__(self, *, max_keys: int = 50_000, clock: Callable[[], float] = time.monotonic):
        self._buckets: OrderedDict[tuple[str, str], tuple[float, float]] = OrderedDict()
        self._lock = threading.Lock()
        self._clock = clock
        self.max_keys = max_keys

    def hit(self, policy: Policy, key: str) -> float:
        """토큰 1개 사용. 허용이면 0, 거부면 다음 토큰까지 남은 초."""
        rate = policy.per_minute / 60.0
        now = self._clock()
        k = (policy.name, key)
        with self._lock:
            tokens, last = self._buckets.get(k, (float(policy.burst), now))
            tokens = min(float(policy.burst), tokens + (now - last) * rate)
            if tokens >= 1.0:
                tokens -= 1.0
                wait = 0.0
            else:
                wait = (1.0 - tokens) / rate if rate > 0 else 60.0
            self._buckets[k] = (tokens, now)
            self._buckets.move_to_end(k)
            while len(self._buckets) > self.max_keys:
                self._buckets.popitem(last=False)
        return wait


class AuthFailureGuard:
    """관리자 토큰 대입 공격 방지: window_s 안에 max_failures 번 틀리면 lock_s 동안 그 IP 의 관리자 요청을 막는다."""

    def __init__(self, *, max_failures: int = 10, window_s: float = 600, lock_s: float = 900, max_keys: int = 10_000,
                 clock: Callable[[], float] = time.monotonic):
        self.max_failures, self.window_s, self.lock_s, self.max_keys = max_failures, window_s, lock_s, max_keys
        self._fails: OrderedDict[str, deque[float]] = OrderedDict()
        self._locked: dict[str, float] = {}
        self._lock = threading.Lock()
        self._clock = clock

    def retry_after(self, ip: str) -> int:
        """잠겨 있으면 남은 초(올림), 아니면 0."""
        with self._lock:
            until = self._locked.get(ip)
            if until is None:
                return 0
            left = until - self._clock()
            if left <= 0:
                del self._locked[ip]
                return 0
            return max(1, math.ceil(left))

    def failure(self, ip: str) -> None:
        now = self._clock()
        with self._lock:
            q = self._fails.setdefault(ip, deque())
            self._fails.move_to_end(ip)
            q.append(now)
            while q and now - q[0] > self.window_s:
                q.popleft()
            if len(q) >= self.max_failures:
                self._locked[ip] = now + self.lock_s
                q.clear()
                if len(self._locked) > self.max_keys:  # 만료된 잠금 정리 (메모리 상한)
                    self._locked = {k: v for k, v in self._locked.items() if v > now}
            while len(self._fails) > self.max_keys:
                self._fails.popitem(last=False)

    def success(self, ip: str) -> None:
        with self._lock:
            self._fails.pop(ip, None)
