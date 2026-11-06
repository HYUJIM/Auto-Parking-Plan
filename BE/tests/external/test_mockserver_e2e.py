"""실제 클라이언트 ↔ mock 서버 종단 시험 (네트워크 없음: TestClient, 시간 초과만 로컬 uvicorn 스레드)."""

import time

import pytest
import shapely
from pyproj import Transformer
from shapely.geometry import shape

import mockserver
from app.external import BuildingHubClient, JusoClient, JusoQueryError, VWorldClient
from app.external.pnu import build_pnu, parse_pnu
from app.external.types import UpstreamError

from .conftest import MOCK_BASE

PARCELS = mockserver.load_parcels()
BY_ID = {p["id"]: p for p in PARCELS}
_TR = Transformer.from_crs("EPSG:4326", "EPSG:5179", always_xy=True)


def _to_metric(geom):
    return shapely.transform(geom, _TR.transform, interleaved=False)


def _by_tag(tag):
    return [p for p in PARCELS if tag in p["tags"]]


@pytest.fixture
def juso(test_client):
    return JusoClient("test-key", base_url=MOCK_BASE, client=test_client)


@pytest.fixture
def vworld(test_client):
    return VWorldClient("test-key", base_url_data=MOCK_BASE, base_url_ned=MOCK_BASE, client=test_client)


@pytest.fixture
def hub(test_client):
    return BuildingHubClient("test-key", base_url=MOCK_BASE, client=test_client)


# ---------------------------------------------------------------- 데이터셋 자체
def test_dataset_shape():
    assert mockserver.fixtures_path().is_file()
    assert len(PARCELS) == 12
    assert all(p["is_example"] for p in PARCELS)
    assert len({p["pnu"] for p in PARCELS}) == 12
    suseong = [p for p in PARCELS if p["supported_region"]]
    assert len(suseong) == 10 and all(p["pnu"].startswith("27260") for p in suseong)
    assert {p["sigungu"] for p in PARCELS if not p["supported_region"]} == {"중구", "종로구"}
    zones = [p["zone"] for p in suseong]
    assert zones.count("제2종일반주거지역") >= 3
    for z in ("제1종일반주거지역", "제3종일반주거지역", "준주거지역", "일반상업지역", "근린상업지역", "자연녹지지역"):
        assert z in zones
    for tag in (
        "district_unit_plan_zone",
        "landscape_district",
        "narrow_lot",
        "l_shaped",
        "large_rectangular",
        "land_use_server_error",
        "land_characteristics_slow",
    ):
        assert len(_by_tag(tag)) == 1, tag
    assert len(_by_tag("unsupported_region")) == 2


@pytest.mark.parametrize("parcel", PARCELS, ids=[p["id"] for p in PARCELS])
def test_dataset_record_consistency(parcel):
    ds = mockserver.load_dataset()
    rec = ds.by_pnu(parcel["pnu"]).record
    assert "예시" in rec["note"] and "예시" in rec["building_name"]
    assert build_pnu(rec["adm_cd"], rec["mt_yn"], rec["bonbun"], rec["bubun"]) == rec["pnu"]
    geom = shape(rec["geometry"])
    assert geom.is_valid and geom.geom_type == "MultiPolygon"
    area = _to_metric(geom).area
    assert 250 <= area <= 4000
    assert abs(area - rec["land"]["area_m2"]) < 0.5
    if parcel["supported_region"]:
        minx, miny, maxx, maxy = geom.bounds
        assert 128.58 <= minx and maxx <= 128.72 and 35.80 <= miny and maxy <= 35.88


def _metric_geom(parcel):
    return _to_metric(mockserver.load_dataset().by_pnu(parcel["pnu"]).geom)


def test_special_shapes():
    (narrow,) = _by_tag("narrow_lot")
    rect = _metric_geom(narrow).minimum_rotated_rectangle
    c = list(rect.exterior.coords)
    sides = sorted(((c[i][0] - c[i + 1][0]) ** 2 + (c[i][1] - c[i + 1][1]) ** 2) ** 0.5 for i in range(4))
    w, length = sides[0], sides[-1]
    assert 7.5 <= w <= 8.5 and length > 40
    (large,) = _by_tag("large_rectangular")
    assert 2900 <= large["area_m2"] <= 3100
    (l_lot,) = _by_tag("l_shaped")
    geom = _metric_geom(l_lot)
    assert geom.area / geom.minimum_rotated_rectangle.area < 0.85  # 볼록하지 않은 ㄱ자


# ---------------------------------------------------------------- 클라이언트 ↔ mock
@pytest.mark.parametrize("parcel", PARCELS, ids=[p["id"] for p in PARCELS])
def test_full_flow_per_parcel(parcel, juso, vworld, hub):
    # 1) 주소 검색 → PNU
    (cand,) = juso.search(parcel["road_address"])
    assert cand.pnu == parcel["pnu"]
    assert cand.jibun_address == parcel["jibun_address"]
    assert (cand.sido, cand.sigungu, cand.emd) == (parcel["sido"], parcel["sigungu"], parcel["emd"])
    assert [c.pnu for c in juso.search(parcel["jibun_address"])] == [parcel["pnu"]]

    # 2) 연속지적도: PNU 조회, 좌표 조회
    p = vworld.get_parcel(cand.pnu)
    assert p is not None and p.pnu == parcel["pnu"]
    assert p.address == parcel["jibun_address"]
    assert p.geometry["type"] == "MultiPolygon"
    lng, lat = parcel["centroid"]
    assert vworld.get_parcel_at(lng, lat).pnu == parcel["pnu"]

    # 3) 토지특성 + 토지임야
    if "land_characteristics_slow" not in parcel["tags"]:
        lc = vworld.get_land_characteristics(cand.pnu)
        assert lc.land_area_m2 == pytest.approx(parcel["area_m2"])
        assert lc.land_category == parcel["land_category"]
        assert lc.official_land_price and lc.road_side
        assert lc.raw["getLandCharacteristics"]["stdrYear"] == "2026"

    # 4) 토지이용계획
    if "land_use_server_error" in parcel["tags"]:
        with pytest.raises(UpstreamError) as ei:
            vworld.get_land_use(cand.pnu)
        assert ei.value.status_code == 500 and ei.value.retriable
    else:
        items = vworld.get_land_use(cand.pnu)
        assert [d.name for d in items if d.kind == "zoning"] == [parcel["zone"]]
        assert all(d.source == "vworld:getLandUseAttr" for d in items)

    # 5) 건축물대장
    buildings = hub.get_buildings(cand.pnu)
    assert bool(buildings) == parcel["has_buildings"]


def test_designation_specials(vworld):
    (dup,) = _by_tag("district_unit_plan_zone")
    items = vworld.get_land_use(dup["pnu"])
    assert [(d.name, d.conflict) for d in items if d.kind == "zone"] == [("지구단위계획구역", "included")]
    assert {d.code: d.conflict for d in items}["UOA120"] == "partially_included"  # 저촉+접함 → 저촉
    (lsd,) = _by_tag("landscape_district")
    assert [d.name for d in vworld.get_land_use(lsd["pnu"]) if d.kind == "district"] == ["자연경관지구"]
    (adj,) = _by_tag("adjacent_zone")
    assert [(d.name, d.conflict) for d in vworld.get_land_use(adj["pnu"]) if d.kind == "zone"] == [
        ("개발제한구역", "adjacent")
    ]


def test_search_behaviour(juso):
    assert juso.search("수성구 존재하지않는예시로 999") == []
    # 숫자 토큰은 통째로 비교: '범어예시로 12' 는 '범어예시로 120' 에 걸리지 않음
    assert [c.pnu for c in juso.search("범어예시로 12")] == [BY_ID["S01"]["pnu"]]
    assert {c.pnu for c in juso.search("범어예시로")} == {BY_ID["S01"]["pnu"], BY_ID["S07"]["pnu"]}
    assert [c.pnu for c in juso.search("만촌동 9002")] == [BY_ID["S02"]["pnu"]]  # 본번만으로 부번 있는 지번 검색
    with pytest.raises(JusoQueryError) as ei:
        juso.search("대구광역시 수성구")
    assert ei.value.code == "E0006"
    with pytest.raises(JusoQueryError) as ei:
        juso.search("1234")
    assert ei.value.code == "E0009"


def test_search_paging(juso):
    page1, total = juso.search_page("예시로", page=1, per_page=4)
    page3, _ = juso.search_page("예시로", page=3, per_page=4)
    assert total == 11 and len(page1) == 4 and len(page3) == 3  # 예시길(서울) 제외 11건


def test_not_found_paths(vworld, hub):
    unknown = "2726010100199990000"
    assert vworld.get_parcel(unknown) is None
    assert vworld.get_parcel_at(128.0, 35.0) is None
    assert vworld.get_land_use(unknown) == []
    assert vworld.get_land_characteristics(unknown) is None
    assert hub.get_buildings(unknown) == []


def test_invalid_key_on_every_endpoint(test_client):
    pnu = BY_ID["S01"]["pnu"]
    juso = JusoClient("invalid", base_url=MOCK_BASE, client=test_client)
    vw = VWorldClient("invalid", base_url_data=MOCK_BASE, base_url_ned=MOCK_BASE, client=test_client)
    hub = BuildingHubClient("invalid", base_url=MOCK_BASE, client=test_client)
    calls = [
        lambda: juso.search("범어예시로 12"),
        lambda: vw.get_parcel(pnu),
        lambda: vw.get_parcel_at(128.6262, 35.8605),
        lambda: vw.get_land_use(pnu),
        lambda: vw.get_land_char_attr(pnu),
        lambda: vw.get_land_register(pnu),
        lambda: vw.get_land_characteristics(pnu),
        lambda: hub.get_buildings(pnu),
    ]
    for call in calls:
        with pytest.raises(UpstreamError) as ei:
            call()
        assert ei.value.retriable is False, ei.value
        assert not isinstance(ei.value, JusoQueryError)


def test_mock_index_and_parcel_listing(test_client):
    assert test_client.get("/").json()["is_example"] is True
    listing = test_client.get("/__mock__/parcels").json()["parcels"]
    assert [p["pnu"] for p in listing] == [p["pnu"] for p in PARCELS]


def test_unsupported_region_parcels_are_plain_data(vworld):
    for p in _by_tag("unsupported_region"):
        assert parse_pnu(p["pnu"])["sigungu_code"] != "27260"
        assert vworld.get_parcel(p["pnu"]).address == p["jibun_address"]


# ---------------------------------------------------------------- 시간 초과(실제 HTTP)
def test_slow_land_characteristics_times_out_over_real_http(live_mock_url):
    (slow,) = _by_tag("land_characteristics_slow")
    with VWorldClient("test-key", base_url_data=live_mock_url, base_url_ned=live_mock_url, timeout=0.3) as vw:
        started = time.monotonic()
        with pytest.raises(UpstreamError) as ei:
            vw.get_land_char_attr(slow["pnu"])
        elapsed = time.monotonic() - started
        assert ei.value.retriable is True and "시간 초과" in ei.value.message
        assert elapsed < 0.95  # 0.3초 × 2회(1회 재시도), 서버 지연(1.0초)을 기다리지 않음
        # 합친 조회는 토지임야(면적·지목)로 부분 결과를 돌려준다
        lc = vw.get_land_characteristics(slow["pnu"])
        assert lc.land_area_m2 == pytest.approx(slow["area_m2"]) and lc.land_category == "대"
        assert lc.official_land_price is None and lc.road_side is None
        assert "getLandCharacteristics" in lc.raw["_errors"]
        # 다른 필지는 같은 서버에서 정상
        normal = BY_ID["S01"]
        assert vw.get_land_char_attr(normal["pnu"]).official_land_price is not None


def test_live_server_full_stack(live_mock_url):
    with (
        JusoClient("test-key", base_url=live_mock_url) as juso,
        VWorldClient("test-key", base_url_data=live_mock_url, base_url_ned=live_mock_url) as vw,
    ):
        (cand,) = juso.search("대구광역시 수성구 범어예시로 12")
        assert vw.get_parcel(cand.pnu).geometry is not None


def test_cli_help():
    from mockserver.__main__ import main

    with pytest.raises(SystemExit) as ei:
        main(["--help"])
    assert ei.value.code == 0
