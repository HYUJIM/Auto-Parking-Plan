"""
PNU(필지고유번호, 19자리) 생성·해석.

구조 (브이월드 연속지적도 레퍼런스 pnu 속성 설명과 동일):
    시도(2) + 시군구(3) + 읍면동(3) + 리(2) + 필지구분(1) + 본번(4) + 부번(4) = 19자리
    - 앞 10자리 = 법정동코드 (도로명주소 검색 API 의 admCd)
    - 필지구분: '1' = 일반(대지), '2' = 산
출처: https://www.vworld.kr/dev/v4dv_2ddataguide2_s002.do?svcIde=cadastral (연속지적도 속성정보 pnu)

도로명주소 검색 API 의 산여부(mtYn)는 '0'=대지, '1'=산 이므로 PNU 의 필지구분으로 바꿀 때
'0' -> '1', '1' -> '2' 로 변환한다.
출처: business.juso.go.kr 개발자센터 > 도로명주소 검색 API 출력결과 (mtYn: 산여부(0:대지, 1:산))

참고: 국가중점데이터 컬럼정의서는 "특수지구분(가지번, 블럭지번 등)에 따라 19자리를 초과할 수 있음"이라고
적고 있다. 이 모듈은 MVP 대상인 일반·산 지번(19자리)만 다루며, 그 외는 ValueError 로 거절한다.
"""

from __future__ import annotations

from typing import Any

__all__ = ["build_pnu", "parse_pnu", "is_valid_pnu", "format_jibun"]

_MT_YN_TO_LAND_TYPE = {"0": "1", "1": "2"}


def _to_lot_number(value: str | int, field: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{field}: 숫자가 아닙니다: {value!r}")
    if isinstance(value, int):
        n = value
    else:
        s = str(value).strip()
        if s == "":
            n = 0
        elif not s.isdigit():
            raise ValueError(f"{field}: 숫자가 아닙니다: {value!r}")
        else:
            n = int(s)
    if not 0 <= n <= 9999:
        raise ValueError(f"{field}: 0~9999 범위를 벗어났습니다: {n}")
    return n


def build_pnu(
    adm_cd: str,
    mt_yn: str | int | bool,
    lnbr_mnnm: str | int,
    lnbr_slno: str | int = 0,
) -> str:
    """도로명주소 API 의 admCd·mtYn·lnbrMnnm·lnbrSlno 로 19자리 PNU 를 만든다.

    Args:
        adm_cd: 법정동코드 10자리 (juso admCd).
        mt_yn: juso 산여부. '0'/0/False = 대지(→'1'), '1'/1/True = 산(→'2').
        lnbr_mnnm: 지번 본번 (0~9999).
        lnbr_slno: 지번 부번 (0~9999, 없으면 0).

    Raises:
        ValueError: 형식이 맞지 않을 때.
    """
    adm = str(adm_cd).strip()
    if len(adm) != 10 or not adm.isdigit():
        raise ValueError(f"법정동코드는 10자리 숫자여야 합니다: {adm_cd!r}")
    if isinstance(mt_yn, bool):
        mt_key = "1" if mt_yn else "0"
    else:
        mt_key = str(mt_yn).strip()
    if mt_key not in _MT_YN_TO_LAND_TYPE:
        raise ValueError(f"산여부(mtYn)는 '0' 또는 '1' 이어야 합니다: {mt_yn!r}")
    main = _to_lot_number(lnbr_mnnm, "본번")
    sub = _to_lot_number(lnbr_slno, "부번")
    return f"{adm}{_MT_YN_TO_LAND_TYPE[mt_key]}{main:04d}{sub:04d}"


def is_valid_pnu(pnu: str) -> bool:
    """19자리 숫자이고 필지구분이 1(일반) 또는 2(산)인지."""
    return isinstance(pnu, str) and len(pnu) == 19 and pnu.isdigit() and pnu[10] in ("1", "2")


def format_jibun(main: int, sub: int, *, is_mountain: bool = False) -> str:
    """'123-4', '123', '산 12-3' 형태의 지번 문자열."""
    body = f"{main}-{sub}" if sub else f"{main}"
    return f"산 {body}" if is_mountain else body


def parse_pnu(pnu: str) -> dict[str, Any]:
    """19자리 PNU 를 구성 요소로 나눈다.

    Returns:
        {
          "pnu": 원문,
          "sido_code": 2자리, "sigungu_code": 5자리(시도+시군구), "emd_code": 3자리, "ri_code": 2자리,
          "adm_code": 법정동코드 10자리, "land_type": '1'|'2', "is_mountain": bool,
          "main_no": int, "sub_no": int, "jibun": '123-4' / '산 12-3',
          "mt_yn": juso 형식 산여부 '0'|'1',
          "plat_gb_cd": 건축물대장 대지구분코드 '0'(대지)|'1'(산),
          "bun": 본번 4자리 문자열, "ji": 부번 4자리 문자열,
        }

    Raises:
        ValueError: 19자리 숫자가 아니거나 필지구분이 1·2 가 아닐 때.
    """
    if not isinstance(pnu, str):
        raise ValueError(f"PNU 는 문자열이어야 합니다: {pnu!r}")
    p = pnu.strip()
    if len(p) != 19 or not p.isdigit():
        raise ValueError(f"PNU 는 19자리 숫자여야 합니다: {pnu!r}")
    land_type = p[10]
    if land_type not in ("1", "2"):
        raise ValueError(f"PNU 필지구분은 1(일반) 또는 2(산)이어야 합니다: {pnu!r}")
    main = int(p[11:15])
    sub = int(p[15:19])
    is_mountain = land_type == "2"
    return {
        "pnu": p,
        "sido_code": p[0:2],
        "sigungu_code": p[0:5],
        "emd_code": p[5:8],
        "ri_code": p[8:10],
        "adm_code": p[0:10],
        "bjdong_code": p[5:10],
        "land_type": land_type,
        "is_mountain": is_mountain,
        "main_no": main,
        "sub_no": sub,
        "jibun": format_jibun(main, sub, is_mountain=is_mountain),
        "mt_yn": "1" if is_mountain else "0",
        "plat_gb_cd": "1" if is_mountain else "0",
        "bun": p[11:15],
        "ji": p[15:19],
    }
