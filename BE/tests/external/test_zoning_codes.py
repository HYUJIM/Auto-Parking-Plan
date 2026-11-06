import pytest

from app.external.zoning_codes import (
    LAND_USE_CODES,
    ZONING_NAMES,
    canonical_designation_name,
    classify_designation,
    conflict_from_source,
    name_for_code,
    normalize_designation_name,
)


def test_verified_codes_present():
    # 행정표준코드관리시스템 '용도지역지구구분' 에서 확인한 값
    assert LAND_USE_CODES["UQA122"] == "제2종일반주거지역"
    assert LAND_USE_CODES["UQA430"] == "자연녹지지역"
    assert LAND_USE_CODES["UQQ300"] == "지구단위계획구역"
    assert LAND_USE_CODES["UDV100"] == "개발제한구역"
    assert LAND_USE_CODES["UQA01X"] == "도시지역"  # 브이월드 컬럼정의서
    assert name_for_code(" uqa122 ") == "제2종일반주거지역"
    assert name_for_code("XXX999") is None


def test_twenty_one_statutory_zonings_plus_management():
    assert len(ZONING_NAMES) == 22  # 21 + 세분 전 관리지역


@pytest.mark.parametrize("name", sorted(ZONING_NAMES))
def test_zoning_names_classified_as_zoning(name):
    assert classify_designation(name) == "zoning"


@pytest.mark.parametrize(
    ("name", "code", "kind"),
    [
        ("제2종일반주거지역", "UQA122", "zoning"),
        ("제2종 일반주거지역(7층이하)", None, "zoning"),
        ("계획관리지역", "UQB100", "zoning"),
        ("관리지역", "UQB001", "zoning"),
        # 상위 묶음 명칭은 zoning 이 아님
        ("도시지역", "UQA01X", "other"),
        ("도시지역", "UQA001", "other"),
        ("일반주거지역", None, "other"),
        # 용도지구
        ("자연경관지구", "UQF110", "district"),
        ("자연경관지구", None, "district"),
        ("방화지구", "UQI100", "district"),
        ("최고고도지구", None, "district"),
        ("관광·휴양개발진흥지구", None, "district"),
        ("복합용도지구", "UQP501", "district"),
        ("재해위험지구", None, "other"),  # 타 법령 지구
        ("화재경계지구", None, "other"),
        # 용도구역·지구단위계획구역
        ("개발제한구역", "UDV100", "zone"),
        ("지구단위계획구역", "UQQ300", "zone"),
        ("제1종지구단위계획구역", None, "zone"),
        ("도시자연공원구역", "UQQ810", "zone"),
        ("시가화조정구역", None, "zone"),
        ("수산자원보호구역", "UQQ500", "zone"),
        ("입지규제최소구역", None, "zone"),
        ("도시혁신구역", None, "zone"),
        ("도시ㆍ군계획시설입체복합구역", None, "zone"),
        # 그 밖
        ("가축사육제한구역", "UMZ100", "other"),
        ("상대보호구역", "UOA120", "other"),
        ("토지거래계약에관한허가구역", "UQQ600", "other"),
        ("비오톱1등급", "ZA0011", "other"),
        ("소로2류(폭 8M~10M)", "UQS121", "other"),
        ("", None, "other"),
        (None, None, "other"),
    ],
)
def test_classify_designation(name, code, kind):
    assert classify_designation(name, code) == kind


def test_code_wins_over_name():
    assert classify_designation("이상한 명칭", "UQA122") == "zoning"
    assert classify_designation("제2종일반주거지역", "UMZ100") == "other"


def test_normalize_and_canonical_names():
    assert normalize_designation_name("  제2종   일반주거지역 ") == "제2종일반주거지역"
    assert normalize_designation_name("성장관리방안  수립지역") == "성장관리방안 수립지역"
    assert normalize_designation_name("처음 보는   지정") == "처음 보는 지정"
    assert normalize_designation_name(None) == ""
    assert canonical_designation_name("제2종일반주거지역(7층이하)", "UQA122") == "제2종일반주거지역"
    assert canonical_designation_name("", "UQQ300") == "지구단위계획구역"
    # 코드와 다른 것을 가리키는 원천 명칭은 덮어쓰지 않는다
    assert canonical_designation_name("시가지경관지구", "UQF100") == "시가지경관지구"


@pytest.mark.parametrize(
    ("code", "name", "expected"),
    [
        ("1", None, "included"),
        ("2", None, "partially_included"),
        ("3", None, "adjacent"),
        (3, None, "adjacent"),
        (None, "포함", "included"),
        (None, "저촉", "partially_included"),
        (None, "접합", "adjacent"),
        (None, "접함", "adjacent"),
        ("", " 접함 ", "adjacent"),
        ("9", "모름", "unknown"),
        (None, None, "unknown"),
    ],
)
def test_conflict_from_source(code, name, expected):
    assert conflict_from_source(code, name) == expected
