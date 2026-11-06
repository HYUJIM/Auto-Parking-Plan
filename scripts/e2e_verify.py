"""
종단(E2E) 검증 — 실제 프로세스로 전체 연동을 확인한다 (윈도우·맥·리눅스 공통).

  1) 외부 공공 API 모의 서버 실행 (도로명주소·브이월드·건축HUB 응답 형식 재현, 예시 필지 12개)
  2) DB 준비 + 실제 수집 원문 적재 + 기록된 추출 결과 기계 검증 + 검수자 승인 (bootstrap)
  3) API 서버 실행 (+ 선택: 별도 검토 워커)
  4) 시나리오: 수성구 예시 필지 10개 × 조건 2개(토지소유주·직각주차 / 실무자·평행주차) = 20건
     주소 검색 → 필지 조회 → 검토 요청(202) → 진행 상태 → 결과(review_result.schema 계약) → 값 대조(원문)
     → 근거 상세 → 쉬운 설명 검증 보고서 → 주차장 배치 시뮬레이션(기하 불변식)
  5) 오류·예외: 주소 없음 404, 잘못된 PNU, 요청 형식 422, 미지원 지역, 외부 API 장애 503, 관리자 인증
  6) 검수 흐름: draft 규칙 확인 → 관리자 승인, L4 특화구역 등록 → ZONE_OVERLAY 격상, 설명 캐시 재사용

실행:  python scripts/e2e_verify.py [--report var/e2e_report.json] [--executor thread|worker] [--keep]
PostgreSQL 로 검증:  E2E_DATABASE_URL=postgresql+psycopg://app:app@localhost:5432/app python scripts/e2e_verify.py
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from jsonschema import Draft202012Validator  # noqa: E402
from shapely.geometry import shape  # noqa: E402

from app.console import ensure_utf8_stdio  # noqa: E402

ADMIN = "e2e-admin-token-local"
REVIEWER = "e2e-reviewer(개발용 자동 승인)"


# ── 결과 기록 ────────────────────────────────────────────────
@dataclass
class Report:
    checks: list[dict] = field(default_factory=list)
    scenarios: list[dict] = field(default_factory=list)

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.checks.append({"name": name, "ok": bool(ok), "detail": detail[:500]})
        print(("  [통과] " if ok else "  [실패] ") + name + (f" — {detail[:200]}" if detail and not ok else ""))
        return bool(ok)

    @property
    def failed(self) -> list[dict]:
        return [c for c in self.checks if not c["ok"]]


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_http(url: str, timeout: float = 60) -> None:
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if httpx.get(url, timeout=2).status_code < 500:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.3)
    raise RuntimeError(f"서버가 뜨지 않음: {url}")


def start(cmd: list[str], env: dict, log: Path) -> subprocess.Popen:
    fh = open(log, "w", encoding="utf-8")  # noqa: SIM115
    kwargs = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
    return subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=fh, stderr=subprocess.STDOUT, **kwargs)


def stop(p: subprocess.Popen | None) -> None:
    if p and p.poll() is None:
        p.terminate()
        try:
            p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            p.kill()


# ── 기대값: 원문 번들에서 독립적으로 읽은 대구 조례·시행령 값 (엔진 경로와 별개) ─────────
def expected_ratios() -> dict[str, dict]:
    def table(bundle: str, art: str) -> dict[str, list[str]]:
        b = json.loads((ROOT / "data" / "legal_sources" / bundle).read_text(encoding="utf-8"))
        text = next(a["text"] for a in b["articles"] if a["label"] == art)
        out, cur = {}, None
        for line in text.split("\n"):
            m = re.match(r"^\s*\d+\.\s*(\S+지역)\s*:\s*(.+)$", line)
            if m:
                cur = m.group(1)
                out[cur] = m.group(2)
            elif re.match(r"^\s*[①-⑳]|^\s*\d+\.", line):
                cur = None
            elif cur:  # 다음 줄로 이어지는 단서 '(다만, …)'
                out[cur] += "\n" + line
        return out

    def num(s: str) -> float:
        s = s.replace(" ", "")
        m = re.search(r"(\d+)천(\d{3})?퍼센트|(\d+)퍼센트", s)
        return float(int(m.group(1)) * 1000 + int(m.group(2) or 0)) if m.group(1) else float(m.group(3))

    bcr = table("daegu.ord.urban_planning__2026-05-11.json", "제75조")
    far = table("daegu.ord.urban_planning__2026-05-11.json", "제80조")
    return {z: {"bcr": num(bcr[z].split("(다만")[0]), "far": num(far[z].split("(다만")[0]), "far_has_exception": "다만" in far[z]}
            for z in bcr if z in far}


def schema_validator() -> Draft202012Validator:
    from app.review.service import review_result_validator  # noqa: PLC0415

    return review_result_validator()


# ── 시나리오 ────────────────────────────────────────────────
def run_scenarios(api: str, rep: Report, parcels: list[dict], exp: dict) -> None:
    v = schema_validator()
    c = httpx.Client(base_url=api, timeout=30)
    H = {"X-Admin-Token": ADMIN}
    seen_reports: dict[tuple, str] = {}

    for p in parcels:
        pnu, zone = p["pnu"], p["zone"]
        supported = p["supported_region"]
        conditions = [("owner", ["PERPENDICULAR_90"]), ("architect", ["PARALLEL_0"])] if supported else [("owner", None)]
        beh = p.get("behavior") or {}
        land_use_error = beh.get("land_use") == "server_error"
        slow_lc = beh.get("land_characteristics") == "slow"
        overlay = "지구단위계획구역" in (p.get("designations") or [])
        print(f"\n▶ {p['id']} {p['jibun_address']} ({zone}, {p['area_m2']}㎡) {p.get('tags')}")
        r = c.get("/v1/address/search", params={"q": p["road_address"]})
        ok = r.status_code == 200 and any(i["pnu"] == pnu for i in r.json().get("items", []))
        rep.check(f"{p['id']} 주소 검색 → PNU", ok, r.text[:200])
        if ok:
            item = next(i for i in r.json()["items"] if i["pnu"] == pnu)
            rep.check(f"{p['id']} 지원 지역 표시 = {supported}", item["supported"] is supported, str(item))
        r = c.get(f"/v1/parcels/{pnu}")
        rep.check(f"{p['id']} 필지 조회 200", r.status_code == 200, r.text[:200])
        parcel = r.json() if r.status_code == 200 else {}
        notices = {n["code"] for n in parcel.get("data_notices", [])}
        if land_use_error:
            rep.check(f"{p['id']} 토지이용계획 장애 → designations=null + DESIGNATION_UNAVAILABLE",
                      parcel.get("designations") is None and "DESIGNATION_UNAVAILABLE" in notices, str(notices))
        elif slow_lc:
            rep.check(f"{p['id']} 토지특성 지연(>3초) → 시간 초과 후 PARCEL_PARTIAL", "PARCEL_PARTIAL" in notices, str(notices))
        else:
            zones = [d["name"] for d in parcel.get("designations") or [] if d["kind"] == "zoning"]
            rep.check(f"{p['id']} 용도지역 = {zone}", zones == [zone], str(zones))
            rep.check(f"{p['id']} 대지면적 = {p['area_m2']}", parcel.get("land_area_m2") == p["area_m2"], str(parcel.get("land_area_m2")))
        rep.check(f"{p['id']} GeoJSON 경계", (parcel.get("location") or {}).get("geometry", {}).get("type") in ("Polygon", "MultiPolygon"))
        rep.check(f"{p['id']} 모의 서버 필지 → is_example=true", parcel.get("is_example") is True, str(parcel.get("is_example")))

        for audience, angles in conditions:
            sc = {"parcel": p["id"], "pnu": pnu, "zone": zone, "audience": audience, "angles": angles, "result": "fail"}
            rep.scenarios.append(sc)
            r = c.post("/v1/reviews", json={"pnu": pnu, "audience": audience})
            if not rep.check(f"{p['id']}/{audience} 검토 요청 202", r.status_code == 202, r.text[:200]):
                continue
            rid = r.json()["review_id"]
            res, states = None, []
            t0 = time.time()
            while time.time() - t0 < 90:
                g = c.get(f"/v1/reviews/{rid}").json()
                if "schema_version" in g:
                    res = g
                    break
                states.append(g.get("status"))
                if g.get("status") == "failed":
                    break
                time.sleep(0.3)
            if not rep.check(f"{p['id']}/{audience} 검토 완료", res is not None, f"states={states[-3:]} {g}"):
                continue
            errs = [f"{'/'.join(map(str, e.absolute_path))}: {e.message}" for e in v.iter_errors(res)]
            rep.check(f"{p['id']}/{audience} review_result@1.0 계약", not errs, "; ".join(errs[:3]))
            items = {i["category"]: i for i in res["items"]}
            codes = {n["code"] for n in res["data_notices"]}
            sc["elapsed_s"] = round(time.time() - t0, 2)
            if not supported:
                rep.check(f"{p['id']} 미지원 지역 → items 없음 + REGION_NOT_SUPPORTED", not items and "REGION_NOT_SUPPORTED" in codes, str(codes))
                sc["result"] = "pass"
                continue
            rep.check(f"{p['id']}/{audience} 자동 검토 6항목 + 별도 확인 4항목", len(items) == 6 and len(res["manual_checks"]) == 4,
                      f"{list(items)} / {len(res['manual_checks'])}")
            rep.check(f"{p['id']}/{audience} 시행 예정 개정 안내(대구 건축 조례 2026-11-12)", "PENDING_AMENDMENT" in codes, str(codes))
            bcr, far = items["BUILDING_COVERAGE"], items["FLOOR_AREA_RATIO"]
            if land_use_error:
                rep.check(f"{p['id']} 용도지역 미확인 → 건폐율 별도 확인(DATA_MISSING)",
                          bcr["status"] == "MANUAL_CHECK" and bcr["reason_code"] == "DATA_MISSING", str(bcr["reason_code"]))
            elif overlay:
                rep.check(f"{p['id']} 지구단위계획구역 → ZONE_OVERLAY 격상", bcr["reason_code"] == "ZONE_OVERLAY", str(bcr["reason_code"]))
            elif zone in exp:
                e = exp[zone]
                rep.check(f"{p['id']} 건폐율 = 조례 {e['bcr']}%", bcr["applied"]["value"] == e["bcr"] and bcr["status"] == "REVIEWABLE",
                          f"{bcr['status']} {bcr['applied']['value']}")
                far_status = "CONDITIONAL" if e["far_has_exception"] else "REVIEWABLE"
                rep.check(f"{p['id']} 용적률 = 조례 {e['far']}% ({far_status})", far["applied"]["value"] == e["far"] and far["status"] == far_status,
                          f"{far['status']} {far['applied']['value']}")
                if parcel.get("land_area_m2"):
                    want = round(parcel["land_area_m2"] * e["bcr"] / 100, 2)
                    got = (bcr["applied"]["reference_value"] or {}).get("value")
                    rep.check(f"{p['id']} 최대 건축면적 참고값 = {want}㎡", got is not None and abs(got - want) < 0.02, str(got))
                layers = [n["layer"] for n in bcr["basis"]]
                titles = [n["document_title"] for n in bcr["basis"]]
                rep.check(f"{p['id']} 근거 트리 법률→시행령→조례", "국토의 계획 및 이용에 관한 법률" in titles and "대구광역시 도시계획 조례" in titles
                          and layers.index("L1_NATIONAL") < layers.index("L3_LOCAL"), str(titles))
            st = items["PARKING_STALL_SIZE"]
            rep.check(f"{p['id']} 주차구획 일반형 2.5m×5m (원문 표 전사본)", "2.5" in (st["applied"]["criteria_text"] or "") and st["status"] == "REVIEWABLE",
                      str(st["applied"]["criteria_text"]))
            # 근거 상세 + 원문 일치
            rid0 = next((n["rule_id"] for n in (bcr["basis"] or st["basis"]) if n.get("rule_id")), None)
            if rid0:
                b = c.get(f"/v1/rules/{rid0}/basis").json()
                rep.check(f"{p['id']} 근거 상세: 인용문이 원문 조문 안에 그대로 있음",
                          all(ev["quoted_text"] in ev["provision_text"] for ev in b["evidence"]), "")
            # 쉬운 설명: 화면에 나가는 것은 검증 통과분만
            for cat, it in items.items():
                ex = it["explanation"]
                if ex["display_source"] != "basis_only":
                    vr = c.get(f"/admin/verifications/{ex['verification']['report_id']}", headers=H).json()
                    rep.check(f"{p['id']}/{audience} {cat} 설명 = 검증 PASS 보고서 연결 ({ex['display_source']})", vr.get("verdict") == "PASS", str(vr)[:200])
                    key = (zone, cat, it["status"], audience)
                    if key in seen_reports:
                        rep.check(f"{p['id']}/{audience} {cat} 설명 캐시 재사용", seen_reports[key] == ex["verification"]["report_id"])
                    seen_reports.setdefault(key, ex["verification"]["report_id"])
            # 주차장 배치 시뮬레이션
            sim = c.post(f"/v1/reviews/{rid}/parking-simulations", json={"angles": angles}).json()
            elig = sim.get("eligibility", {}).get("status")
            sc["parking_eligibility"] = elig
            if elig in ("REVIEWABLE", "CONDITIONAL"):
                ok = sim.get("simulated") is True
                rep.check(f"{p['id']}/{audience} 주차장 설치 검토 가능({elig}) → 배치 계산", ok, str(sim.get("constraint_reasons")))
            else:
                rep.check(f"{p['id']}/{audience} 주차장 설치 미확인({elig}) → 배치 계산 보류", sim.get("simulated") is False, str(sim)[:200])
                sim = c.post(f"/v1/reviews/{rid}/parking-simulations", json={"angles": angles, "include_unverified": True}).json()
                rep.check(f"{p['id']}/{audience} include_unverified → 참고 배치(reference_only)", sim.get("simulated") is True and sim.get("reference_only") is True,
                          str(sim.get("constraint_reasons")))
            if sim.get("simulated"):
                site = shape(res["parcel"]["location"]["geometry"]).buffer(1e-7)
                alts = sim["result"]["alternatives"]
                sc["stalls"] = alts[0]["stall_count"] if alts else 0
                sc["constraints"] = sim.get("constraint_reasons")
                if alts:
                    a = alts[0]
                    stalls = [shape(f["geometry"]) for f in a["layers"]["stalls"]["features"]]
                    rep.check(f"{p['id']}/{audience} 배치: 주차구획 {len(stalls)}면이 필지 안", all(site.contains(s0) for s0 in stalls), "")
                    rep.check(f"{p['id']}/{audience} 배치: 구획 수 = metrics", len(stalls) == a["stall_count"] == a["metrics"]["stall_count"])
                    want_w = 2.5 if angles == ["PERPENDICULAR_90"] else 2.0
                    rep.check(f"{p['id']}/{audience} 배치: 법정 치수({want_w}m) 사용", a["metrics"]["stall_width_m"] == want_w, str(a["metrics"]["stall_width_m"]))
                    rep.check(f"{p['id']}/{audience} 배치: 출입구·차로 레이어", bool(a["layers"]["aisles"]["features"]) and a["layers"]["gate"]["geometry"]["type"] == "Point")
                else:
                    rep.check(f"{p['id']}/{audience} 배치 불가 사유 제공", bool(sim.get("constraint_reasons")), "")
            sc["result"] = "pass"


def run_errors_and_admin(api: str, rep: Report, parcels: list[dict]) -> None:
    c = httpx.Client(base_url=api, timeout=30)
    H = {"X-Admin-Token": ADMIN}
    print("\n▶ 오류·예외 처리")
    r = c.get("/v1/address/search", params={"q": "존재하지않는주소예시"})
    rep.check("주소 없음 → 404 ADDRESS_NOT_FOUND + 기능정의서 문구", r.status_code == 404 and r.json()["error"]["code"] == "ADDRESS_NOT_FOUND"
              and "주소를 찾을 수 없습니다" in r.json()["error"]["message"], r.text[:200])
    r = c.get("/v1/parcels/2726099999999999999")
    rep.check("없는 필지 → 404 PARCEL_NOT_FOUND", r.status_code == 404 and r.json()["error"]["code"] == "PARCEL_NOT_FOUND", r.text[:200])
    r = c.post("/v1/reviews", json={"pnu": "12345"})
    rep.check("잘못된 요청 → 422 INVALID_REQUEST", r.status_code == 422 and r.json()["error"]["code"] == "INVALID_REQUEST", r.text[:200])
    r = c.get("/v1/reviews/00000000-0000-4000-8000-000000000000")
    rep.check("없는 검토 → 404 REVIEW_NOT_FOUND", r.status_code == 404, r.text[:200])
    rep.check("관리자 API 토큰 없음 → 401", c.get("/admin/rules").status_code == 401)
    rep.check("지원 지역 = 대구 수성구", c.get("/v1/regions").json()["supported"] == [{"code": "27260", "name": "대구광역시 수성구"}])
    s07 = next((p for p in parcels if p["id"] == "S07"), None)
    if s07:
        g = s07["centroid"]
        r = c.get("/v1/parcels/at", params={"lng": g[0], "lat": g[1]})
        rep.check("지도 클릭(좌표) → 필지", r.status_code == 200 and r.json()["pnu"] == s07["pnu"], r.text[:200])

    print("\n▶ 검수(관리자) 흐름")
    rules = c.get("/admin/rules", params={"status": "approved"}, headers=H).json()["items"]
    rep.check("승인 규칙 존재 (국가 법령 + 조례 + 주차 기준)", len(rules) >= 90, str(len(rules)))
    d = c.get(f"/admin/rules/{rules[0]['id']}", headers=H).json()
    rep.check("규칙 상세: 원문 하이라이트 + 승인된 사실 원장", d["evidence"] and d["ledgers"] and d["ledgers"][0]["review_status"] == "approved"
              and d["ledgers"][0]["gap_count"] == 0, "")
    s02 = next(p for p in parcels if p["id"] == "S02")
    geom = c.get(f"/v1/parcels/{s02['pnu']}").json()["location"]["geometry"]
    poly = shape(geom).buffer(0.0005).envelope.__geo_interface__
    r = c.post("/admin/zones", headers=H, json={
        "reviewer": REVIEWER, "zone_type": "district_unit_plan", "name": "E2E 예시 지구단위계획구역 (실제 고시 아님)",
        "notice_title": "E2E 예시 지구단위계획 결정 고시 (실제 고시 아님)", "notice_number": "E2E-EXAMPLE-1",
        "notice_date": "2026-01-01", "notice_url": "https://example.invalid/e2e-gosi", "jurisdiction_code": "27260",
        "geometry": json.loads(json.dumps(poly))})
    rep.check("L4 특화구역 수동 등록 201", r.status_code == 201, r.text[:200])
    rid = c.post("/v1/reviews", json={"pnu": s02["pnu"]}).json()["review_id"]
    res = None
    for _ in range(300):
        g = c.get(f"/v1/reviews/{rid}").json()
        if "schema_version" in g:
            res = g
            break
        time.sleep(0.3)
    bcr = next(i for i in res["items"] if i["category"] == "BUILDING_COVERAGE")
    sz = next(m for m in res["manual_checks"] if m["category"] == "SPECIAL_ZONE")
    rep.check("등록한 특화구역과 겹침 → 건폐율 ZONE_OVERLAY + 고시 근거", bcr["reason_code"] == "ZONE_OVERLAY" and sz["related_basis"]
              and sz["related_basis"][0]["layer"] == "L4_SPECIAL_ZONE", f"{bcr['reason_code']} {sz['triggered_by']}")


def run_upstream_failure(env: dict, rep: Report, logs: Path, parcels: list[dict]) -> None:
    print("\n▶ 외부 API 장애 (잘못된 인증키)")
    port = free_port()
    e2 = dict(env, JUSO_API_KEY="invalid", VWORLD_API_KEY="invalid", REVIEW_EXECUTOR="thread")
    p = start([sys.executable, "-m", "uvicorn", "app.main:app", "--port", str(port), "--host", "127.0.0.1"], e2, logs / "api_invalid.log")
    try:
        wait_http(f"http://127.0.0.1:{port}/healthz")
        c = httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=30)
        r = c.get("/v1/address/search", params={"q": "수성구"})
        rep.check("시군구만 입력 → 404 + 도로명주소 API 안내(details.reason)", r.status_code == 404 and (r.json()["error"].get("details") or {}).get("reason"),
                  r.text[:200])
        r = c.get("/v1/address/search", params={"q": parcels[1]["road_address"]})
        rep.check("주소 API 인증 실패 → 503 UPSTREAM_UNAVAILABLE", r.status_code == 503 and r.json()["error"]["code"] == "UPSTREAM_UNAVAILABLE", r.text[:200])
        r = c.get("/v1/parcels/2726010100199990000")
        rep.check("지적 API 장애 + 캐시 없음 → 503", r.status_code == 503, r.text[:200])
        cached = next(p0 for p0 in parcels if p0["id"] == "S02")
        r = c.get(f"/v1/parcels/{cached['pnu']}")
        rep.check("외부 장애여도 캐시된 필지는 조회됨", r.status_code == 200, r.text[:200])
    finally:
        stop(p)


def main() -> int:
    ensure_utf8_stdio()
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", default=str(ROOT / "var" / "e2e_report.json"))
    ap.add_argument("--executor", choices=["thread", "worker"], default="thread")
    ap.add_argument("--keep", action="store_true", help="임시 폴더를 지우지 않음")
    args = ap.parse_args()
    rep = Report()
    work = Path(tempfile.mkdtemp(prefix="app-e2e-"))
    logs = work / "logs"
    logs.mkdir()
    db_url = os.environ.get("E2E_DATABASE_URL") or f"sqlite:///{(work / 'e2e.sqlite3').as_posix()}"
    mock_port, api_port = free_port(), free_port()
    mock = f"http://127.0.0.1:{mock_port}"
    env = dict(os.environ, PYTHONUNBUFFERED="1", APP_ENV="test", DATABASE_URL=db_url,
               BLOB_STORE="local", BLOB_LOCAL_DIR=str(work / "blobs"),
               JUSO_API_KEY="mock-key", JUSO_BASE_URL=mock, VWORLD_API_KEY="mock-key", VWORLD_DATA_URL=mock, VWORLD_NED_URL=mock,
               BUILDING_HUB_KEY="mock-key", BUILDING_HUB_URL=mock, LLM_PROVIDER="fake", ADMIN_TOKEN=ADMIN,
               REVIEW_EXECUTOR=args.executor, EXTERNAL_TIMEOUT_S="3.0", LOG_LEVEL="WARNING")
    procs: list[subprocess.Popen] = []
    t_start = time.time()
    try:
        print(f"작업 폴더: {work}\nDB: {db_url.split('@')[-1]}")
        if db_url.startswith("postgresql"):
            from sqlalchemy import create_engine, text  # noqa: PLC0415

            eng = create_engine(db_url)
            with eng.begin() as conn:
                conn.execute(text("DROP SCHEMA public CASCADE; CREATE SCHEMA public;"))
            eng.dispose()
        procs.append(start([sys.executable, "-m", "mockserver", "--port", str(mock_port), "--host", "127.0.0.1"], env, logs / "mock.log"))
        wait_http(f"{mock}/")
        rep.check("외부 API 모의 서버 기동", True)
        boot = subprocess.run([sys.executable, "-m", "app.cli", "bootstrap", "--approve-as", REVIEWER, "--ack-warnings",
                               "--confirm-transcriptions"], cwd=ROOT, env=env,
                              capture_output=True, text=True, encoding="utf-8", timeout=600)
        (logs / "bootstrap.log").write_text(boot.stdout + boot.stderr, encoding="utf-8", newline="\n")
        rep.check("bootstrap (원문 적재·추출 검증·승인)", boot.returncode == 0, boot.stderr[-500:])
        lines = [ln for ln in boot.stdout.splitlines() if ln.startswith(("0", "1"))]
        rep.check("기록된 추출 결과 전부 기계 검증 통과", lines and all(": ok " in ln for ln in lines), "\n".join(lines))
        rep.check("위임 관계 연결", any("위임 관계" in ln and not ln.startswith("위임 관계 0") for ln in boot.stdout.splitlines()))
        procs.append(start([sys.executable, "-m", "uvicorn", "app.main:app", "--port", str(api_port), "--host", "127.0.0.1"], env,
                           logs / "api.log"))
        if args.executor == "worker":
            procs.append(start([sys.executable, "-m", "app.cli", "worker", "--poll", "0.2"], env, logs / "worker.log"))
        api = f"http://127.0.0.1:{api_port}"
        wait_http(f"{api}/healthz")
        rep.check("API 서버 기동 /healthz", httpx.get(f"{api}/healthz").json()["status"] == "ok")
        from mockserver import load_parcels  # noqa: PLC0415

        parcels = load_parcels()
        exp = expected_ratios()
        run_scenarios(api, rep, parcels, exp)
        run_errors_and_admin(api, rep, parcels)
        run_upstream_failure(env, rep, logs, parcels)
    except Exception:  # noqa: BLE001
        rep.check("E2E 실행 중 예외 없음", False, traceback.format_exc())
    finally:
        for p in reversed(procs):
            stop(p)
    supported = [s for s in rep.scenarios if s["audience"] in ("owner", "architect") and s["zone"] and s["result"] == "pass"]
    summary = {
        "ran_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "platform": sys.platform, "python": sys.version.split()[0],
        "database": "postgresql" if db_url.startswith("postgresql") else "sqlite", "executor": args.executor,
        "elapsed_s": round(time.time() - t_start, 1), "checks_total": len(rep.checks), "checks_failed": len(rep.failed),
        "scenarios_total": len(rep.scenarios), "scenarios_passed": len(supported),
        "failed": rep.failed, "scenarios": rep.scenarios, "checks": rep.checks, "logs": str(logs),
        "note": "외부 공공 API 는 모의 서버(응답 형식 재현), LLM 은 결정적 fake, 필지는 예시 데이터. 법규 원문은 law.go.kr 에서 수집한 실제 원문.",
    }
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    print(f"\n결과: 점검 {len(rep.checks)}건 중 실패 {len(rep.failed)}건, 시나리오 {len(rep.scenarios)}건 → {out}")
    if not args.keep and not rep.failed:
        shutil.rmtree(work, ignore_errors=True)
    elif rep.failed:
        print(f"로그: {logs}")
    return 0 if not rep.failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
