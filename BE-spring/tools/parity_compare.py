"""
파이썬판(BE) ↔ 스프링판(BE-spring) 응답 대조.

같은 모의 공공 API·같은 법령 원문으로 두 서버를 띄우고, 예시 필지마다 같은 요청을 보내 응답 JSON 을 필드 단위로 비교한다.
매번 달라지는 값(UUID, 조회·생성 시각, 검증 시각)만 자리표시자로 바꾸고 나머지는 모두 같아야 한다 (실수는 1e-9 상대 오차 허용).

실행 (파이썬판 가상환경, 스프링판 실행 파일 필요):
    cd BE-spring && ./gradlew :server:bootJar
    cd ../BE && .venv/bin/python ../BE-spring/tools/parity_compare.py [--report ../BE-spring/var/parity_report.json]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

BE = Path(__file__).resolve().parents[2] / "BE"
SPRING = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BE))

from mockserver import load_parcels  # noqa: E402

UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?([+-]\d{2}:\d{2}|Z)?$")
VOLATILE_KEYS = {"fetched_at", "checked_at", "created_at", "approved_at", "at"}
# 규칙 id(무작위 UUID)가 들어간 해시 → DB 가 다르면 원래 다르다
ID_DERIVED_KEYS = {"ruleset_hash"}
REVIEWER = "parity-reviewer"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait(url: str, t: float = 90) -> None:
    t0 = time.time()
    while time.time() - t0 < t:
        try:
            if httpx.get(url, timeout=2).status_code < 500:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.3)
    raise RuntimeError(f"서버가 뜨지 않음: {url}")


def norm(x, key=None):  # noqa: ANN001, ANN201
    """비교용 정규화: UUID·시각을 자리표시자로."""
    if isinstance(x, dict):
        return {k: ("<id-derived>" if k in ID_DERIVED_KEYS else norm(v, k)) for k, v in x.items()}
    if isinstance(x, list):
        return [norm(v) for v in x]
    if isinstance(x, str):
        if key in VOLATILE_KEYS and TS_RE.match(x):
            return "<ts>"
        return UUID_RE.sub("<uuid>", x)
    return x


def diff(a, b, path="$", out=None):  # noqa: ANN001, ANN201
    out = [] if out is None else out
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                out.append(f"{path}.{k}: {'파이썬에만' if k in a else '스프링에만'} 있음")
            else:
                diff(a[k], b[k], f"{path}.{k}", out)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            out.append(f"{path}: 길이 {len(a)} != {len(b)}")
        for i, (x, y) in enumerate(zip(a, b, strict=False)):
            diff(x, y, f"{path}[{i}]", out)
    elif isinstance(a, bool) or isinstance(b, bool):
        if a is not b:
            out.append(f"{path}: {a!r} != {b!r}")
    elif isinstance(a, (int, float)) and isinstance(b, (int, float)):
        if not (a == b or (math.isfinite(a) and math.isfinite(b) and math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9))):
            out.append(f"{path}: {a!r} != {b!r}")
    elif a != b:
        out.append(f"{path}: {str(a)[:120]!r} != {str(b)[:120]!r}")
    return out


def run(cmd: list[str], cwd: Path, env: dict, log: Path) -> subprocess.Popen:
    return subprocess.Popen(cmd, cwd=cwd, env=env, stdout=open(log, "w", encoding="utf-8"), stderr=subprocess.STDOUT)  # noqa: SIM115


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else (shutil.which("java") or "java")


def poll_review(c: httpx.Client, rid: str) -> dict:
    for _ in range(600):
        r = c.get(f"/v1/reviews/{rid}").json()
        if r.get("schema_version") or r.get("status") in ("failed",):
            return r
        time.sleep(0.2)
    raise RuntimeError(f"검토가 끝나지 않음: {rid}")


def collect(base: str, parcels: list[dict]) -> dict:
    c = httpx.Client(base_url=base, timeout=60)
    out: dict = {"regions": c.get("/v1/regions").json()}
    for p in parcels:
        key = p["id"]
        o: dict = {}
        o["search"] = c.get("/v1/address/search", params={"q": p["road_address"]}).json()
        o["parcel"] = c.get(f"/v1/parcels/{p['pnu']}").json()
        for audience in ("owner", "architect"):
            r = c.post("/v1/reviews", json={"pnu": p["pnu"], "audience": audience, "as_of_date": "2026-11-06"})
            if r.status_code != 202:
                o[f"review_{audience}"] = {"status_code": r.status_code, "body": r.json()}
                continue
            rid = r.json()["review_id"]
            res = poll_review(c, rid)
            o[f"review_{audience}"] = res
            o[f"sim_{audience}"] = c.post(f"/v1/reviews/{rid}/parking-simulations", json={}).json()
            o[f"sim_{audience}_ref"] = c.post(f"/v1/reviews/{rid}/parking-simulations", json={"include_unverified": True}).json()
            if audience == "owner":
                rule_ids = sorted({x for it in res.get("items", []) for b in it.get("basis", []) for x in [b.get("rule_id")] if x})
                o["basis"] = [c.get(f"/v1/rules/{x}/basis", params={"as_of_date": "2026-11-06"}).json() for x in rule_ids]
                # 규칙 id 는 서버마다 다르므로 rule_key 로 정렬해 비교한다
                o["basis"].sort(key=lambda b: (b.get("rule_key") or "", b.get("title") or ""))
        out[key] = o
    out["errors"] = {
        "not_found": c.get("/v1/parcels/2726010100199990000").json(),
        "bad_pnu": c.get("/v1/parcels/abc").json(),
        "invalid": c.post("/v1/reviews", json={"pnu": "123"}).status_code,
        "no_review": c.get("/v1/reviews/00000000-0000-0000-0000-000000000000").json(),
    }
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", default=str(SPRING / "var" / "parity_report.json"))
    ap.add_argument("--py-db-url", help="파이썬판 DB (기본: SQLite 임시 파일). 예: postgresql+psycopg://app:app@127.0.0.1:5432/py")
    ap.add_argument("--spring-db-url", help="스프링판 DB (기본: H2 임시 파일). 예: postgresql://app:app@127.0.0.1:5432/sp")
    args = ap.parse_args()
    work = Path(tempfile.mkdtemp(prefix="app-parity-"))
    mock_port, py_port, sp_port = free_port(), free_port(), free_port()
    mock = f"http://127.0.0.1:{mock_port}"
    base_env = dict(os.environ, PYTHONUNBUFFERED="1", APP_ENV="test", BLOB_STORE="local", JUSO_API_KEY="mock-key", JUSO_BASE_URL=mock,
                    VWORLD_API_KEY="mock-key", VWORLD_DATA_URL=mock, VWORLD_NED_URL=mock, BUILDING_HUB_KEY="mock-key", BUILDING_HUB_URL=mock,
                    LLM_PROVIDER="fake", ADMIN_TOKEN="parity-admin", REVIEW_EXECUTOR="thread", LOG_LEVEL="WARNING", TZ="Asia/Seoul")
    py_env = dict(base_env, DATABASE_URL=args.py_db_url or f"sqlite:///{(work / 'py.sqlite3').as_posix()}", BLOB_LOCAL_DIR=str(work / "py-blobs"))
    sp_env = dict(base_env, DATABASE_URL=args.spring_db_url or f"jdbc:h2:file:{(work / 'spring').as_posix()}", BLOB_LOCAL_DIR=str(work / "sp-blobs"))
    jar = str(SPRING / "server" / "build" / "libs" / "app.jar")
    procs = []
    try:
        procs.append(run([sys.executable, "-m", "mockserver", "--port", str(mock_port), "--host", "127.0.0.1"], BE, base_env, work / "mock.log"))
        wait(f"{mock}/")
        boot = ["bootstrap", "--approve-as", REVIEWER, "--ack-warnings", "--confirm-transcriptions"]
        r1 = subprocess.run([sys.executable, "-m", "app.cli", *boot], cwd=BE, env=py_env, capture_output=True, text=True, encoding="utf-8")
        r2 = subprocess.run([java(), "-jar", jar, *boot], cwd=SPRING, env=sp_env, capture_output=True, text=True, encoding="utf-8")
        boot_diff = [] if r1.stdout.splitlines() == [ln for ln in r2.stdout.splitlines() if not ln.startswith("20")] else \
            [f"bootstrap 출력 다름:\n--- python\n{r1.stdout}\n--- spring\n{r2.stdout}\n{r2.stderr[-2000:]}"]
        procs.append(run([sys.executable, "-m", "uvicorn", "app.main:app", "--port", str(py_port), "--host", "127.0.0.1"], BE, py_env, work / "py.log"))
        procs.append(run([java(), "-jar", jar, "serve", "--port", str(sp_port)], SPRING, sp_env, work / "spring.log"))
        wait(f"http://127.0.0.1:{py_port}/healthz")
        wait(f"http://127.0.0.1:{sp_port}/healthz")
        parcels = load_parcels()
        a = norm(collect(f"http://127.0.0.1:{py_port}", parcels))
        b = norm(collect(f"http://127.0.0.1:{sp_port}", parcels))
        diffs = boot_diff + diff(a, b)
        report = {"compared_parcels": len(parcels), "differences": diffs, "python": a, "spring": b}
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"필지 {len(parcels)}개 대조: 차이 {len(diffs)}건 → {args.report}")
        for d in diffs[:60]:
            print("  " + d)
        return 0 if not diffs else 1
    finally:
        for p in reversed(procs):
            p.terminate()
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                p.kill()
        print(f"작업 폴더: {work}")


if __name__ == "__main__":
    raise SystemExit(main())
