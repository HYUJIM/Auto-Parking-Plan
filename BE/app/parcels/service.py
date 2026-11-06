"""
필지 확정 (설계서 7장 1단계): 주소 → PNU → 지적·토지이용계획·토지특성 조회 → parcel 캐시(30일).

  * 실패한 항목은 null 로 두고 data_notices 에 PARCEL_PARTIAL / DESIGNATION_UNAVAILABLE 을 넣는다.
  * 외부 API 장애 시 만료된 캐시라도 있으면 그것을 돌려준다 (조회 중단 방지, 안내 문구 포함).
  * API 키는 서버에서만 쓴다.
"""
from __future__ import annotations

import logging
import re
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict
from datetime import date, timedelta
from typing import Any

from shapely.geometry import shape
from sqlalchemy.orm import Session

from app.db.models import Jurisdiction, Parcel, ParcelBuilding, ParcelDesignation
from app.db.types import utcnow
from app.external.pnu import is_valid_pnu
from app.external.types import UpstreamError
from app.legal.ingest import ensure_jurisdiction
from app.parcels.snapshot import ParcelSnapshot

log = logging.getLogger(__name__)
PNU_RE = re.compile(r"^\d{19}$")


class AddressNotFound(Exception):
    pass


class ParcelNotFound(Exception):
    pass


class UpstreamUnavailable(Exception):
    pass


def split_region(address: str | None) -> tuple[str | None, str | None]:
    """'대구광역시 수성구 범어동 1-1' → ('대구광역시', '수성구'). 세종시처럼 시·군·구가 없으면 (시도, None)."""
    if not address:
        return None, None
    toks = address.split()
    sido = toks[0] if toks else None
    sigungu = toks[1] if len(toks) > 1 and re.search(r"(시|군|구)$", toks[1]) else None
    return sido, sigungu


def _centroid(geom: dict | None) -> list[float] | None:
    if not geom:
        return None
    try:
        g = shape(geom)
        p = g.centroid if g.contains(g.centroid) else g.representative_point()
        return [round(p.x, 7), round(p.y, 7)]
    except Exception:  # noqa: BLE001
        return None


class ParcelService:
    def __init__(self, *, juso=None, vworld=None, building=None, cache_days: int = 30, coord_lookup_limit: int = 5,  # noqa: ANN001
                 example_data: bool = False, refresh_min_interval_s: int = 600):
        self.example_data = example_data  # 공식 API 가 아닌 곳(모의 서버 등)에서 받은 필지 → 예시 데이터로 표시
        # 공개 API 의 ?refresh=true 는 외부 공공 API(일일 할당량)를 다시 부른다. 같은 필지는 이 간격 안에 다시 부르지 않는다
        # → 반복 호출로 인증키 할당량을 소진시켜 서비스 전체를 멈추게 하는 공격 차단
        self.refresh_min_interval_s = refresh_min_interval_s
        self.juso = juso
        self.vworld = vworld
        self.building = building
        self.cache_days = cache_days
        self.coord_lookup_limit = coord_lookup_limit
        self._pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="parcel")

    # ── 주소 검색 ──────────────────────────────────────────
    def search(self, s: Session, q: str) -> list[dict[str, Any]]:
        if self.juso is None:
            raise UpstreamUnavailable("주소 검색 API 키가 설정되지 않았습니다")
        try:
            cands = self.juso.search(q, per_page=10)
        except UpstreamError as e:
            # 검색어 오류(JusoQueryError: E0005 등)는 장애가 아니라 '주소를 찾을 수 없음'
            if getattr(e, "code", None) is not None and not e.retriable and hasattr(e, "user_message"):
                raise AddressNotFound(e.user_message) from e
            raise UpstreamUnavailable(e.message) from e
        if not cands:
            raise AddressNotFound(q)
        coords: dict[str, list[float] | None] = {}
        to_lookup = []
        for c in cands:
            cached = s.get(Parcel, c.pnu)
            if cached is not None:
                coords[c.pnu] = cached.centroid
            elif len(to_lookup) < self.coord_lookup_limit and self.vworld is not None:
                to_lookup.append(c.pnu)
        if to_lookup:
            futs = {pnu: self._pool.submit(self.vworld.get_parcel, pnu) for pnu in to_lookup}
            for pnu, f in futs.items():
                try:
                    cp = f.result()
                    coords[pnu] = _centroid(cp.geometry) if cp else None
                except Exception:  # noqa: BLE001  좌표는 선택 정보
                    coords[pnu] = None
        out = []
        for c in cands:
            j = s.get(Jurisdiction, c.pnu[:5])
            out.append({
                "pnu": c.pnu, "road_address": c.road_address, "jibun_address": c.jibun_address,
                "sido": c.sido, "sigungu": c.sigungu, "region_code": c.pnu[:5],
                "location": ({"lng": coords[c.pnu][0], "lat": coords[c.pnu][1]} if coords.get(c.pnu) else None),
                "supported": bool(j and j.is_supported),
                "building_name": c.building_name,
            })
        return out

    # ── 필지 조회 + 캐시 ───────────────────────────────────
    def get_parcel(self, s: Session, pnu: str, *, refresh: bool = False, address_hint: dict | None = None) -> Parcel:
        if not PNU_RE.match(pnu or "") or not is_valid_pnu(pnu):
            raise ParcelNotFound(pnu)
        row = s.get(Parcel, pnu)
        now = utcnow()
        if refresh and row is not None and (now - row.fetched_at).total_seconds() < self.refresh_min_interval_s:
            refresh = False  # 방금 받은 정보 → 외부 API 를 다시 부르지 않고 저장된 정보를 쓴다
        if row is not None and not refresh and row.expires_at > now:
            return row
        try:
            fetched = self._fetch(pnu)
        except UpstreamUnavailable:
            if row is not None:
                meta = dict(row.source_meta or {})
                meta["stale"] = {"ok": False, "error": "외부 API 장애로 저장된 정보를 표시", "fetched_at": row.fetched_at.isoformat()}
                row.source_meta = meta
                return row
            raise
        return self._upsert(s, pnu, fetched, row, address_hint)

    def get_parcel_at(self, s: Session, lng: float, lat: float) -> Parcel:
        if self.vworld is None:
            raise UpstreamUnavailable("VWorld API 키가 설정되지 않았습니다")
        try:
            cp = self.vworld.get_parcel_at(lng, lat)
        except UpstreamError as e:
            raise UpstreamUnavailable(e.message) from e
        if cp is None:
            raise ParcelNotFound(f"{lng},{lat}")
        return self.get_parcel(s, cp.pnu)

    def _fetch(self, pnu: str) -> dict[str, Any]:
        if self.vworld is None:
            raise UpstreamUnavailable("VWorld API 키가 설정되지 않았습니다")
        try:
            cad = self.vworld.get_parcel(pnu)
        except UpstreamError as e:
            raise UpstreamUnavailable(e.message) from e
        if cad is None:
            raise ParcelNotFound(pnu)
        jobs = {"land_use": self._pool.submit(self.vworld.get_land_use, pnu),
                "land_characteristics": self._pool.submit(self.vworld.get_land_characteristics, pnu)}
        if self.building is not None:
            jobs["buildings"] = self._pool.submit(self.building.get_buildings, pnu)
        out: dict[str, Any] = {"cadastral": cad}
        for k, f in jobs.items():
            try:
                out[k] = f.result()
            except UpstreamError as e:
                out[k] = e
            except Exception as e:  # noqa: BLE001
                log.exception("%s 조회 중 예외", k)
                out[k] = UpstreamError(k, type(e).__name__)
        return out

    def _upsert(self, s: Session, pnu: str, f: dict[str, Any], row: Parcel | None, hint: dict | None) -> Parcel:
        now = utcnow()
        cad = f["cadastral"]
        lu, lc, bl = f.get("land_use"), f.get("land_characteristics"), f.get("buildings")
        address = (hint or {}).get("jibun_address") or cad.address or (row.jibun_address if row else None) or pnu
        road = (hint or {}).get("road_address") or (row.road_address if row else None)
        sido, sigungu = split_region(address)
        ensure_jurisdiction(s, pnu[:2], sido)
        ensure_jurisdiction(s, pnu[:5], f"{sido} {sigungu}" if sido and sigungu else sido)
        meta: dict[str, Any] = {"cadastral": {"source": cad.source, "fetched_at": cad.fetched_at.isoformat(), "ok": True}}

        def note(k, v):
            if isinstance(v, UpstreamError):
                meta[k] = {"source": v.service, "ok": False, "error": v.message, "fetched_at": now.isoformat()}
            elif v is not None:
                src = v[0].source if isinstance(v, list) and v else getattr(v, "source", k)
                fa = v[0].fetched_at if isinstance(v, list) and v else getattr(v, "fetched_at", now)
                meta[k] = {"source": src, "fetched_at": fa.isoformat(), "ok": True}
            else:
                meta[k] = {"source": k, "ok": True, "empty": True, "fetched_at": now.isoformat()}

        note("land_use", lu)
        note("land_characteristics", lc)
        lc_errors = (getattr(lc, "raw", None) or {}).get("_errors") if lc is not None and not isinstance(lc, UpstreamError) else None
        if lc_errors:  # 토지대장/토지특성 중 하나만 실패한 부분 결과
            meta["land_characteristics"].update(ok=False, partial=True, error="; ".join(map(str, lc_errors))[:300])
        if self.building is not None:
            note("buildings", bl)

        if row is None:
            row = Parcel(pnu=pnu, jurisdiction_code=pnu[:5], jibun_address=address, source_meta={}, fetched_at=now, expires_at=now)
            s.add(row)
        row.jurisdiction_code, row.jibun_address, row.road_address = pnu[:5], address, road
        row.geom = cad.geometry
        row.centroid = _centroid(cad.geometry)
        lc_ok = lc is not None and not isinstance(lc, UpstreamError)
        row.land_area_m2 = lc.land_area_m2 if lc_ok else (row.land_area_m2 if row.land_area_m2 is not None else None)
        row.land_category = lc.land_category if lc_ok else row.land_category
        row.road_side = lc.road_side if lc_ok else row.road_side
        row.official_land_price = (lc.official_land_price if lc_ok and lc.official_land_price else cad.official_land_price)
        if not isinstance(lu, UpstreamError) and lu is not None:
            row.designations.clear()
            s.flush()
            for d in lu:
                row.designations.append(ParcelDesignation(kind=d.kind, name=d.name, code=d.code, conflict=d.conflict,
                                                          basis_law=d.basis_law, fetched_at=d.fetched_at))
            row.designations_ok = True
        elif not row.designations_ok:
            row.designations_ok = False  # 조회 실패 + 이전 값 없음 → designations = null
        else:
            meta["land_use"]["kept_previous"] = True
        if self.building is not None and isinstance(bl, list):
            row.buildings.clear()
            for b in bl:
                row.buildings.append(ParcelBuilding(main_use=b.main_use, gross_floor_area=b.gross_floor_area,
                                                    building_area=b.building_area,
                                                    approval_date=date.fromisoformat(b.approval_date) if b.approval_date else None,
                                                    raw=b.raw, fetched_at=b.fetched_at))
        row.is_example = self.example_data or bool((cad.raw or {}).get("is_example"))
        row.source_meta = meta
        row.fetched_at = now
        partial = any(isinstance(x, UpstreamError) for x in (lu, lc, bl)) or bool(lc_errors)
        # 일부 실패한 결과는 오래 캐시하지 않는다 (1시간 뒤 다시 시도)
        row.expires_at = now + (timedelta(hours=1) if partial else timedelta(days=self.cache_days))
        s.flush()
        return row

    # ── 검토 엔진 입력 ─────────────────────────────────────
    def snapshot(self, s: Session, row: Parcel) -> ParcelSnapshot:
        sido, sigungu = split_region(row.jibun_address)
        j = s.get(Jurisdiction, row.jurisdiction_code)
        meta = row.source_meta or {}
        notices = []
        failed = [k for k, v in meta.items() if isinstance(v, dict) and v.get("ok") is False and k != "stale"]
        if failed or row.land_area_m2 is None:
            notices.append({"code": "PARCEL_PARTIAL", "message": "해당 토지의 일부 정보를 불러오지 못했습니다. 누락 항목은 '정보 확인 필요'로 표시합니다."})
        if "stale" in meta:
            notices.append({"code": "PARCEL_PARTIAL", "message": f"공공데이터 조회가 지연되어 저장된 정보(조회 {meta['stale']['fetched_at'][:10]})를 표시합니다."})
        if not row.designations_ok:
            notices.append({"code": "DESIGNATION_UNAVAILABLE", "message": "토지이용계획(용도지역·지구·구역) 정보를 불러오지 못했습니다. 정보 확인 필요."})
        field_map = {"cadastral": ["geometry"], "land_use": ["designations"],
                     "land_characteristics": ["land_area_m2", "land_category"], "buildings": ["buildings"]}
        sources = []
        for k, fields in field_map.items():
            v = meta.get(k)
            if isinstance(v, dict) and v.get("ok"):
                for fld in fields:
                    sources.append({"field": fld, "source": v.get("source", k), "fetched_at": v["fetched_at"]})
        return ParcelSnapshot(
            pnu=row.pnu, road_address=row.road_address, jibun_address=row.jibun_address,
            jurisdiction_code=row.jurisdiction_code, sido=sido or (j.name if j else ""), sigungu=sigungu,
            supported=bool(j and j.is_supported), land_area_m2=row.land_area_m2, land_category=row.land_category,
            designations=[{"kind": d.kind, "name": d.name, "conflict": d.conflict or "unknown", "code": d.code}
                          for d in row.designations] if row.designations_ok else None,
            geometry=row.geom, centroid=row.centroid, road_side=row.road_side, official_land_price=row.official_land_price,
            sources=sources, notices=notices,
            buildings=[{"main_use": b.main_use, "gross_floor_area": b.gross_floor_area,
                        "approval_date": b.approval_date.isoformat() if b.approval_date else None} for b in row.buildings],
            is_example=row.is_example)


def parcel_public_view(snap: ParcelSnapshot) -> dict[str, Any]:
    """GET /v1/parcels/{pnu} 응답: 대지면적, 지목, 용도지역·지구·구역, GeoJSON 경계, 항목별 출처·조회시각."""
    d = asdict(snap)
    return {
        "pnu": snap.pnu, "road_address": snap.road_address, "jibun_address": snap.jibun_address,
        "jurisdiction": {"code": snap.jurisdiction_code, "sido": snap.sido, "sigungu": snap.sigungu, "supported": snap.supported},
        "land_area_m2": snap.land_area_m2,
        "land_area_pyeong": round(snap.land_area_m2 * 0.3025, 2) if snap.land_area_m2 else None,
        "land_category": snap.land_category, "official_land_price": snap.official_land_price, "road_side": snap.road_side,
        "designations": d["designations"],
        "location": {"centroid": snap.centroid, "geometry": snap.geometry},
        "buildings": snap.buildings, "sources": snap.sources, "data_notices": snap.notices, "is_example": snap.is_example,
    }
