"""실제 외부 API 연기(smoke) 시험. 환경변수에 키가 있을 때만 실행된다.

  JUSO_API_KEY            도로명주소 검색 API 승인키
  VWORLD_API_KEY          브이월드 인증키 (VWORLD_DOMAIN: 키 발급 시 등록한 서비스 URL, 선택)
  BUILDING_HUB_SERVICE_KEY 공공데이터포털 서비스키 (선택)

예: JUSO_API_KEY=... VWORLD_API_KEY=... python -m pytest tests/external -m real_api
"""

import os

import pytest

from app.external import BuildingHubClient, JusoClient, VWorldClient
from app.external.pnu import is_valid_pnu

pytestmark = pytest.mark.real_api

JUSO_KEY = os.environ.get("JUSO_API_KEY")
VWORLD_KEY = os.environ.get("VWORLD_API_KEY")
HUB_KEY = os.environ.get("BUILDING_HUB_SERVICE_KEY")
# 수성구 범어동 일대(범어네거리 근처) 좌표. 어떤 필지가 나오든 응답 모양만 확인한다.
SUSEONG_POINT = (128.6237, 35.8590)


@pytest.mark.skipif(not JUSO_KEY, reason="JUSO_API_KEY 미설정")
def test_juso_real_search():
    with JusoClient(JUSO_KEY, timeout=5.0) as juso:
        result = juso.search("대구광역시 수성구 달구벌대로")
    assert result, "검색 결과가 비어 있음"
    for cand in result:
        assert is_valid_pnu(cand.pnu) and cand.pnu.startswith("27260")
        assert cand.fetched_at is not None


@pytest.mark.skipif(not VWORLD_KEY, reason="VWORLD_API_KEY 미설정")
def test_vworld_real_lookup():
    with VWorldClient(VWORLD_KEY, domain=os.environ.get("VWORLD_DOMAIN"), timeout=5.0) as vw:
        parcel = vw.get_parcel_at(*SUSEONG_POINT)
        assert parcel is not None and is_valid_pnu(parcel.pnu)
        assert parcel.geometry and parcel.geometry["type"] == "MultiPolygon"
        again = vw.get_parcel(parcel.pnu)
        assert again is not None and again.pnu == parcel.pnu
        designations = vw.get_land_use(parcel.pnu)
        assert any(d.kind == "zoning" for d in designations) or designations == []
        lc = vw.get_land_characteristics(parcel.pnu)
        assert lc is None or lc.pnu == parcel.pnu


@pytest.mark.skipif(not (HUB_KEY and VWORLD_KEY), reason="BUILDING_HUB_SERVICE_KEY/VWORLD_API_KEY 미설정")
def test_building_hub_real():
    with VWorldClient(VWORLD_KEY, domain=os.environ.get("VWORLD_DOMAIN"), timeout=5.0) as vw:
        parcel = vw.get_parcel_at(*SUSEONG_POINT)
    assert parcel is not None
    with BuildingHubClient(HUB_KEY, timeout=5.0) as hub:
        buildings = hub.get_buildings(parcel.pnu)
    assert isinstance(buildings, list)
