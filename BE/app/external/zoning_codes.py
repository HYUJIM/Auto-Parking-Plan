"""
용도지역·지구·구역 코드 ↔ 법정 명칭 사전과 지정 종류(kind) 분류.

설계서 11장 리스크 "토지이용계획 API 응답의 용도지역 명칭이 규칙 조건과 다름 → 명칭 정규화 사전(UQA122 ↔
법정 명칭)" 대응용. DB 의 정규화 테이블을 채울 때 이 사전을 시드로 쓸 수 있다.

수록 원칙: 공식 출처에서 확인한 코드만 넣는다.
  [S1] 행정표준코드관리시스템(행정안전부) 코드명 '용도지역지구구분'(코드종 00316), 2026-11-06 조회
       https://www.code.go.kr/stdcode/normalCodeL.do  (codeseId=00316)
       검색 화면: https://www.code.go.kr/stdcodesrch/codeNMSearchL.do  (예: 코드값의미 '제2종일반주거지역' → UQA122)
       브이월드 국가중점데이터 컬럼정의서도 용도지역지구코드는 "행정표준코드관리시스템에서 확인"하라고 안내한다.
  [S2] 브이월드 국가중점데이터 컬럼정의서(배포용 xlsx) 토지이용계획정보 샘플: 용도지역지구코드 'UQA01X' = '도시지역'
       https://www.vworld.kr/contents/국가중점데이터_컬럼정의서_배포용.xlsx
       (실제 getLandUseAttr 응답도 도시지역을 UQA01X 로 준다. [S1]의 UQA001 과 다르므로 둘 다 수록)
  [S3] 브이월드 토지이용계획속성조회(getLandUseAttr) 레퍼런스 출력 샘플: 'UQP110' = '아파트지구'
       https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=51

[S1]의 1,467개 코드 가운데 국토계획법 용도지역(UQA~UQE)·용도지구(UQF~UQP)·용도구역 등(UQQ) 전부와,
실제 응답에 자주 나오는 타 법령 지정(개발제한구역, 가축사육제한구역, 교육환경보호구역 등) 일부만 수록했다.
사전에 없는 코드는 명칭 기준으로 분류하고, 그래도 모르면 'other' 로 둔다.

kind 분류 규칙 (LandUseDesignation.kind)
  * 'zoning'  : 국토계획법 시행령 제30조의 세분 용도지역 21개(도시지역 16 + 보전·생산·계획관리 + 농림 + 자연환경보전)와
                세분 전 '관리지역'. 상위 묶음 명칭(도시지역·주거지역·일반주거지역·상업지역 등)은 건폐율·용적률을
                정하지 않으므로 'zoning' 으로 보지 않고 'other' 로 둔다(한 필지에 'zoning' 이 둘 이상이면 실제 걸침).
  * 'district': 국토계획법 용도지구(경관·고도·방화·방재·보호·취락·개발진흥·특정용도제한·복합용도 등과
                과거 미관·시설보호지구, 조례 지구). [S1] UQF~UQP 코드 전체.
  * 'zone'    : 용도구역(개발제한구역, 도시자연공원구역, 시가화조정구역, 수산자원보호구역, 입지규제최소구역,
                2024년 신설 도시혁신구역·복합용도구역·도시·군계획시설입체복합구역)과 지구단위계획구역.
  * 'other'   : 그 밖의 모든 것(타 법령 지역·지구, 도시·군계획시설, 토지거래허가구역 등).
"""

from __future__ import annotations

import re

from app.external.types import Conflict, DesignationKind

__all__ = [
    "LAND_USE_CODES",
    "ZONING_NAMES",
    "DISTRICT_NAMES",
    "ZONE_NAMES",
    "normalize_designation_name",
    "canonical_designation_name",
    "classify_designation",
    "conflict_from_source",
    "name_for_code",
]

# 코드 → 법정(공식) 명칭. 출처는 모듈 docstring [S1]~[S3].
LAND_USE_CODES: dict[str, str] = {
    # --- [S1] 타 법령 지정 일부 (실제 응답에 자주 나오는 것) ---
    "UBA100": "과밀억제권역",
    "UBA200": "성장관리권역",
    "UBA300": "자연보전권역",
    "UDV000": "개발제한미분류",
    "UDV100": "개발제한구역",
    "UDV999": "개발제한구역기타",
    "UMZ100": "가축사육제한구역",
    "UNE200": "대공방어협조구역",
    "UNE400": "비행안전구역",
    "UOA100": "교육환경보호구역",
    "UOA110": "절대보호구역",
    "UOA120": "상대보호구역",
    # --- [S1] 용도지역 ---
    "UQA000": "도시지역미분류",
    "UQA001": "도시지역",
    "UQA002": "도시관리계획 입안중",
    "UQA100": "주거지역",
    "UQA110": "전용주거지역",
    "UQA111": "제1종전용주거지역",
    "UQA112": "제2종전용주거지역",
    "UQA120": "일반주거지역",
    "UQA121": "제1종일반주거지역",
    "UQA122": "제2종일반주거지역",
    "UQA123": "제3종일반주거지역",
    "UQA130": "준주거지역",
    "UQA200": "상업지역",
    "UQA210": "중심상업지역",
    "UQA220": "일반상업지역",
    "UQA230": "근린상업지역",
    "UQA240": "유통상업지역",
    "UQA300": "공업지역",
    "UQA310": "전용공업지역",
    "UQA320": "일반공업지역",
    "UQA330": "준공업지역",
    "UQA400": "녹지지역",
    "UQA410": "보전녹지지역",
    "UQA420": "생산녹지지역",
    "UQA430": "자연녹지지역",
    "UQA500": "도시지역미지정",
    "UQA999": "도시지역기타",
    "UQB000": "관리지역미분류",
    "UQB001": "관리지역",
    "UQB100": "계획관리지역",
    "UQB200": "생산관리지역",
    "UQB300": "보전관리지역",
    "UQB999": "관리지역기타",
    "UQC000": "농림지역미분류",
    "UQC001": "농림지역",
    "UQC999": "농림지역기타",
    "UQD000": "자연환경보전지역미분류",
    "UQD001": "자연환경보전지역",
    "UQD999": "자연환경보전지역기타",
    "UQE000": "국토이용용도지역미분류",
    "UQE999": "국토이용용도지역기타",
    # --- [S1] 용도지구 ---
    "UQF000": "경관지구미분류",
    "UQF100": "경관지구",
    "UQF110": "자연경관지구",
    "UQF120": "특화경관지구",
    "UQF130": "시가지경관지구",
    "UQF131": "시가지경관지구(중심)",
    "UQF132": "시가지경관지구(일반)",
    "UQF999": "경관지구기타",
    "UQG000": "미관지구미분류",
    "UQG100": "미관지구",
    "UQG110": "중심지미관지구",
    "UQG120": "역사문화미관지구",
    "UQG130": "일반미관지구",
    "UQG999": "미관지구기타",
    "UQH000": "고도지구미분류",
    "UQH100": "고도지구",
    "UQH110": "최고고도지구",
    "UQH120": "최저고도지구",
    "UQH999": "고도지구기타",
    "UQI000": "방화지구미분류",
    "UQI100": "방화지구",
    "UQI999": "방화지구기타",
    "UQJ000": "방재지구미분류",
    "UQJ100": "방재지구",
    "UQJ200": "시가지방재지구",
    "UQJ300": "자연방재지구",
    "UQJ999": "방재지구기타",
    "UQK000": "보존지구미분류",
    "UQK100": "보존지구",
    "UQK110": "역사문화환경보호지구",
    "UQK120": "중요시설물보존지구",
    "UQK121": "중요시설물보호지구(공항)",
    "UQK122": "중요시설물보호지구(항만)",
    "UQK123": "중요시설물보호지구(공용)",
    "UQK130": "생태계보호지구",
    "UQK999": "보존지구기타",
    "UQL000": "시설보호지구미분류",
    "UQL100": "시설보호지구",
    "UQL110": "공항시설보호지구",
    "UQL120": "학교시설보호지구",
    "UQL130": "공용시설보호지구",
    "UQL140": "항만시설보호지구",
    "UQL999": "시설보호지구기타",
    "UQM000": "용도지구취락미분류",
    "UQM100": "용도지구취락지구",
    "UQM110": "자연취락지구",
    "UQM120": "집단취락지구",
    "UQM999": "용도지구취락기타",
    "UQN000": "개발진흥미분류",
    "UQN100": "개발진흥지구",
    "UQN110": "주거개발진흥지구",
    "UQN120": "산업개발진흥지구",
    "UQN130": "유통개발진흥지구",
    "UQN140": "관광ㆍ휴양개발진흥지구",
    "UQN150": "복합개발진흥지구",
    "UQN160": "특정개발진흥지구",
    "UQN170": "산업ㆍ유통개발진흥지구",
    "UQN999": "개발진흥기타",
    "UQO000": "특정용도미분류",
    "UQO100": "특정용도제한지구",
    "UQO999": "특정용도기타",
    "UQP000": "국토이용용도지구미분류",
    "UQP100": "국토이용기타용도지구",
    "UQP120": "위락지구",
    "UQP130": "리모델링지구",
    "UQP500": "복합용도지구미분류",
    "UQP501": "복합용도지구",
    "UQP599": "복합용도지구기타",
    "UQP999": "국토이용용도지구기타",
    # --- [S1] 용도구역·지구단위계획구역 등 ---
    "UQQ000": "용도구역미분류",
    "UQQ100": "도시계획구역",
    "UQQ200": "시가화조정구역",
    "UQQ300": "지구단위계획구역",
    "UQQ310": "제1종지구단위계획구역",
    "UQQ320": "제2종지구단위계획구역",
    "UQQ321": "주거용지",
    "UQQ322": "상업용지",
    "UQQ323": "공업용지",
    "UQQ324": "녹지용지",
    "UQQ325": "공공시설용지",
    "UQQ329": "기타용지",
    "UQQ400": "광역계획구역",
    "UQQ500": "수산자원보호구역",
    "UQQ600": "토지거래계약에관한허가구역",
    "UQQ700": "개발밀도관리구역",
    "UQQ800": "기반시설부담구역",
    "UQQ810": "도시자연공원구역",
    "UQQ900": "개발행위허가제한지역",
    "UQQ901": "입지규제최소구역",
    "UQQ902": "성장관리방안 수립지역",
    "UQQ999": "용도구역기타",
    # --- [S1] 도시·군계획시설 일부 ---
    "UQS100": "도로",
    "UQS520": "도시철도",
    "UQT200": "공원",
    "UQT220": "근린공원",
    "UQV200": "공공청사",
    "UQV300": "학교",
    # --- [S2] 브이월드 컬럼정의서·실응답 ---
    "UQA01X": "도시지역",
    # --- [S3] 브이월드 getLandUseAttr 레퍼런스 샘플 ---
    "UQP110": "아파트지구",
}

# 국토의 계획 및 이용에 관한 법률 제36조, 같은 법 시행령 제30조의 세분 용도지역 21개 + 세분 전 관리지역.
ZONING_NAMES: frozenset[str] = frozenset(
    {
        "제1종전용주거지역",
        "제2종전용주거지역",
        "제1종일반주거지역",
        "제2종일반주거지역",
        "제3종일반주거지역",
        "준주거지역",
        "중심상업지역",
        "일반상업지역",
        "근린상업지역",
        "유통상업지역",
        "전용공업지역",
        "일반공업지역",
        "준공업지역",
        "보전녹지지역",
        "생산녹지지역",
        "자연녹지지역",
        "보전관리지역",
        "생산관리지역",
        "계획관리지역",
        "농림지역",
        "자연환경보전지역",
        "관리지역",
    }
)

# 용도구역(국토계획법 제38조~제40조의5)과 지구단위계획구역(제49조~).
# 도시혁신구역·복합용도구역·도시·군계획시설입체복합구역은 2024.8.7 시행 개정법의 법정 명칭이며 [S1] 코드는 미확인.
ZONE_NAMES: frozenset[str] = frozenset(
    {
        "개발제한구역",
        "도시자연공원구역",
        "시가화조정구역",
        "수산자원보호구역",
        "입지규제최소구역",
        "도시혁신구역",
        "복합용도구역",
        "도시·군계획시설입체복합구역",
        "지구단위계획구역",
        "제1종지구단위계획구역",
        "제2종지구단위계획구역",
    }
)

_DISTRICT_CODE_PREFIXES = tuple(f"UQ{c}" for c in "FGHIJKLMNOP")
_ZONING_CODES = frozenset(
    {
        "UQA111", "UQA112", "UQA121", "UQA122", "UQA123", "UQA130",
        "UQA210", "UQA220", "UQA230", "UQA240",
        "UQA310", "UQA320", "UQA330",
        "UQA410", "UQA420", "UQA430",
        "UQB001", "UQB100", "UQB200", "UQB300",
        "UQC001", "UQD001",
    }
)  # fmt: skip
_ZONE_CODES = frozenset({"UDV100", "UQQ200", "UQQ300", "UQQ310", "UQQ320", "UQQ500", "UQQ810", "UQQ901"})

# 용도지구 명칭: [S1] UQF~UQP 코드 명칭 + 현행 법률상 상위 명칭.
DISTRICT_NAMES: frozenset[str] = frozenset(
    {name for code, name in LAND_USE_CODES.items() if code.startswith(_DISTRICT_CODE_PREFIXES)}
    | {"보호지구", "중요시설물보호지구", "취락지구", "산업·유통개발진흥지구", "관광·휴양개발진흥지구"}
)

_WS_RE = re.compile(r"\s+")
_PAREN_RE = re.compile(r"\s*[(\[（][^)\]）]*[)\]）]")
_DOTS = str.maketrans({"ㆍ": "·", "‧": "·", "・": "·", "･": "·", "∙": "·", "•": "·", "．": "·"})


def _key(name: str) -> str:
    """비교용 키: 공백 제거 + 가운뎃점 통일."""
    return _WS_RE.sub("", name).translate(_DOTS)


def _base(name: str) -> str:
    """괄호 부기 제거: '제2종일반주거지역(7층이하)' → '제2종일반주거지역'."""
    return _PAREN_RE.sub("", name).strip()


_CANONICAL_BY_KEY: dict[str, str] = {}
for _n in (*LAND_USE_CODES.values(), *ZONING_NAMES, *ZONE_NAMES, *DISTRICT_NAMES):
    _CANONICAL_BY_KEY.setdefault(_key(_n), _n)
_ZONING_KEYS = frozenset(_key(n) for n in ZONING_NAMES)
_ZONE_KEYS = frozenset(_key(n) for n in ZONE_NAMES)
_DISTRICT_KEYS = frozenset(_key(n) for n in DISTRICT_NAMES)


def name_for_code(code: str | None) -> str | None:
    """사전에 있는 코드면 공식 명칭, 없으면 None."""
    if not code:
        return None
    return LAND_USE_CODES.get(code.strip().upper())


def normalize_designation_name(name: str | None) -> str:
    """원천 명칭 정리: 앞뒤 공백 제거, 연속 공백 1칸. 공백·가운뎃점만 다른 공식 명칭이 있으면 그 표기로 바꾼다.

    '제2종 일반주거지역' → '제2종일반주거지역'. 괄호 부기('(7층이하)' 등)는 정보이므로 남긴다.
    """
    if not name:
        return ""
    s = _WS_RE.sub(" ", str(name)).strip()
    return _CANONICAL_BY_KEY.get(_key(s), s)


def canonical_designation_name(name: str | None, code: str | None = None) -> str:
    """LandUseDesignation.name 에 넣을 명칭.

    코드가 사전에 있고 원천 명칭이 (공백·가운뎃점·괄호 부기 차이 외에) 같은 것을 가리키면 공식 명칭으로 통일한다.
    원천 명칭이 비어 있으면 코드의 공식 명칭을 쓴다. 다르면 원천 명칭을 그대로(정리만 해서) 둔다.
    """
    n = normalize_designation_name(name)
    official = name_for_code(code)
    if official is None:
        return n
    if not n or _key(_base(n)) == _key(_base(official)) or _key(n) == _key(official):
        return official
    return n


def _kind_from_code(code: str | None) -> DesignationKind | None:
    if not code:
        return None
    c = code.strip().upper()
    if c in _ZONING_CODES:
        return "zoning"
    if c in _ZONE_CODES:
        return "zone"
    if c.startswith(_DISTRICT_CODE_PREFIXES):  # [S1] UQF~UQP = 국토계획법 계열 용도지구 코드군
        return "district"
    if c in LAND_USE_CODES:
        return "other"
    return None


def _kind_from_name(name: str | None) -> DesignationKind:
    if not name:
        return "other"
    k = _key(_base(_WS_RE.sub(" ", str(name)).strip()))
    if k in _ZONING_KEYS:
        return "zoning"
    if k in _ZONE_KEYS:
        return "zone"
    if k in _DISTRICT_KEYS:
        return "district"
    return "other"


def classify_designation(name: str | None, code: str | None = None) -> DesignationKind:
    """지정 종류 분류. 사전에 있는 코드가 있으면 코드 기준, 없으면 명칭 기준(정확 일치), 모르면 'other'.

    >>> classify_designation("제2종일반주거지역", "UQA122")
    'zoning'
    >>> classify_designation("지구단위계획구역")
    'zone'
    >>> classify_designation("가축사육제한구역", "UMZ100")
    'other'
    """
    by_code = _kind_from_code(code)
    if by_code is not None:
        return by_code
    return _kind_from_name(name)


_CONFLICT_BY_CODE: dict[str, Conflict] = {"1": "included", "2": "partially_included", "3": "adjacent"}
_CONFLICT_BY_NAME: dict[str, Conflict] = {
    "포함": "included",
    "저촉": "partially_included",
    "접합": "adjacent",  # getLandUseAttr 레퍼런스 표기
    "접함": "adjacent",  # 국가중점데이터 컬럼정의서·실응답 표기
}


def conflict_from_source(code: object = None, name: object = None) -> Conflict:
    """저촉여부코드(1:포함, 2:저촉, 3:접함) 또는 저촉여부명 → Conflict. 모르면 'unknown'.

    출처: getLandUseAttr 레퍼런스 cnflcAt 설명 "저촉여부코드(1:포함,2:저촉,3:접합)",
          국가중점데이터 컬럼정의서 참조코드 40 "저촉여부코드 1:포함, 2:저촉, 3:접함".
    """
    if code is not None:
        c = str(code).strip()
        if c in _CONFLICT_BY_CODE:
            return _CONFLICT_BY_CODE[c]
    if name is not None:
        n = _WS_RE.sub("", str(name))
        if n in _CONFLICT_BY_NAME:
            return _CONFLICT_BY_NAME[n]
    return "unknown"
