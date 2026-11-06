import pytest

from app.external.pnu import build_pnu, format_jibun, is_valid_pnu, parse_pnu


@pytest.mark.parametrize(
    ("args", "expected"),
    [
        (("2726010100", "0", "123", "4"), "2726010100101230004"),
        (("2726010100", "1", "12", "0"), "2726010100200120000"),  # 산 → 필지구분 2
        (("2726010100", 0, 9001, 0), "2726010100190010000"),
        (("2726010100", True, 1, 1), "2726010100200010001"),
        (("1111010100", "0", "0001"), "1111010100100010000"),  # 부번 생략 = 0
        (("1111010100", "0", "9999", "9999"), "1111010100199999999"),
        ((" 2726010100 ", " 0 ", " 12 ", ""), "2726010100100120000"),
    ],
)
def test_build_pnu(args, expected):
    assert build_pnu(*args) == expected
    assert len(expected) == 19


@pytest.mark.parametrize(
    "args",
    [
        ("272601010", "0", "1", "0"),  # 9자리
        ("27260101AA", "0", "1", "0"),
        ("2726010100", "2", "1", "0"),  # mtYn 은 0/1 만
        ("2726010100", "", "1", "0"),
        ("2726010100", "0", "10000", "0"),
        ("2726010100", "0", "-1", "0"),
        ("2726010100", "0", "12a", "0"),
        ("2726010100", "0", True, "0"),
    ],
)
def test_build_pnu_rejects_bad_input(args):
    with pytest.raises(ValueError):
        build_pnu(*args)


def test_parse_pnu_fields():
    p = parse_pnu("2726010100101230004")
    assert p["adm_code"] == "2726010100"
    assert p["sido_code"] == "27"
    assert p["sigungu_code"] == "27260"
    assert p["bjdong_code"] == "10100"
    assert p["emd_code"] == "101"
    assert p["ri_code"] == "00"
    assert p["land_type"] == "1"
    assert p["is_mountain"] is False
    assert (p["main_no"], p["sub_no"]) == (123, 4)
    assert p["jibun"] == "123-4"
    assert (p["mt_yn"], p["plat_gb_cd"]) == ("0", "0")
    assert (p["bun"], p["ji"]) == ("0123", "0004")


def test_parse_pnu_mountain_and_roundtrip():
    pnu = build_pnu("2726011400", "1", 12, 0)
    p = parse_pnu(pnu)
    assert p["is_mountain"] is True
    assert p["jibun"] == "산 12"
    assert p["plat_gb_cd"] == "1"
    assert build_pnu(p["adm_code"], p["mt_yn"], p["main_no"], p["sub_no"]) == pnu


@pytest.mark.parametrize("bad", ["", "123", "27260101001012300041", "2726010100301230004", "27260101001O1230004", None])
def test_parse_pnu_rejects(bad):
    with pytest.raises(ValueError):
        parse_pnu(bad)
    assert not is_valid_pnu(bad)


def test_is_valid_pnu_and_format():
    assert is_valid_pnu("2726010100190010000")
    assert format_jibun(5, 0) == "5"
    assert format_jibun(5, 2, is_mountain=True) == "산 5-2"
