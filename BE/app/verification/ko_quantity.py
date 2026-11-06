"""
한국어 법규 문장의 '세부 사실' 파서 — 원문과 AI 답변에 똑같이 적용한다.

같은 파서로 양쪽을 읽어 정규화된 값(canonical)끼리 비교하므로
'1년' ↔ '12개월'은 같다고 보고, '1년' ↔ '8개월', '이하' ↔ '미만'은 다르다고 판단한다.

추출 대상
  - 기간(PERIOD): 1년, 1년 6개월, 6개월, 한 달, 반년, 30일, 2주  → 개월 또는 일 단위
  - 수량(QUANTITY): 60%, 60퍼센트, 300㎡, 300제곱미터, 6m, 2.5미터, 12대, 5층, 30세대
  - 날짜(DATE): 2026-11-12, 2026년 11월 12일, 2026. 11. 12.
  - 비교어(COMPARATOR): 숫자 바로 뒤의 이하/미만/이상/초과/이내/까지/넘지 않는
  - 법령 참조(REFERENCE): 「…」, 제77조제1항제2호, 별표 1
  - 단위 없는 숫자(BARE): 위 어디에도 해당하지 않는 숫자 → 근거 없으면 날조로 간주
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# ── 숫자 표기 ────────────────────────────────────────────────
SINO = {"일": 1, "이": 2, "삼": 3, "사": 4, "오": 5, "육": 6, "칠": 7, "팔": 8, "구": 9, "십": 10}
NATIVE = {"한": 1, "두": 2, "세": 3, "네": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9, "열": 10,
          "열한": 11, "열두": 12}
# 법령 표기 "1천500퍼센트"(=1,500%) 같은 천 단위 혼용 숫자를 먼저 잡는다 (용적률 별표 등)
# "1천500", "1천 300"(=1,300), "1천"(=1,000), "2천제곱미터" 를 하나의 수로 읽는다
NUM = r"\d+천\s?\d{3}(?!\d)|\d+천(?!\s?\d)|\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"
NATIVE_ALT = "|".join(sorted(NATIVE, key=len, reverse=True))
SINO_ALT = "|".join(SINO)

COMPARATORS = [
    (r"이하", "LE"), (r"이내", "LE"), (r"까지", "LE"), (r"안에", "LE"), (r"안으로", "LE"), (r"내에", "LE"), (r"내로", "LE"), (r"넘지\s*않", "LE"), (r"초과하지\s*않", "LE"),
    (r"미만", "LT"), (r"이상", "GE"), (r"초과", "GT"), (r"넘는", "GT"), (r"넘으", "GT"),
]
COMPARATOR_RE = re.compile(r"^\s*(?:을|를|의|로|으로)?\s*(" + "|".join(p for p, _ in COMPARATORS) + ")")

UNIT_MAP = {  # 표기 → (차원, 정규 단위)
    "%": ("ratio", "%"), "퍼센트": ("ratio", "%"), "프로": ("ratio", "%"),
    "㎡": ("area", "㎡"), "제곱미터": ("area", "㎡"), "m²": ("area", "㎡"), "m2": ("area", "㎡"),
    "평": ("area_pyeong", "평"),  # 원문에 없으면 단위 날조로 걸림 (환산 금지)
    "m": ("length", "m"), "미터": ("length", "m"), "cm": ("length_cm", "cm"), "센티미터": ("length_cm", "cm"),
    "대": ("count_vehicle", "대"), "층": ("floors", "층"), "세대": ("households", "세대"),
    "명": ("persons", "명"), "배": ("multiple", "배"), "개소": ("places", "개소"),
    "개": ("count", "개"),  # 출입구가 2개 이상 (개월은 기간 패턴이 먼저 잡는다)
    "도": ("angle", "도"),  # 60도 대향주차 (도로·도시 등은 아래 UNIT_RE 에서 제외)
}
UNIT_ALT = "|".join(sorted((re.escape(u) for u in UNIT_MAP), key=len, reverse=True))


@dataclass
class Q:
    kind: str                 # PERIOD | QUANTITY | DATE | REFERENCE | BARE
    dim: str                  # period_month, period_day, ratio, area, date, ref ...
    value: object             # 정규화된 값 (개월 수, 일 수, 숫자, 'YYYY-MM-DD', 정규화 참조 문자열)
    unit: str | None
    comparator: str | None
    raw: str
    start: int
    end: int
    extra: dict = field(default_factory=dict)

    def key(self):
        return (self.kind, self.dim, self.value)


def _num(s: str) -> float:
    s = s.strip()
    if s in NATIVE:
        return float(NATIVE[s])
    if s in SINO:
        return float(SINO[s])
    if "천" in s:
        a, b = s.split("천", 1)
        return float(int(a) * 1000 + int(b.strip() or 0))
    return float(s.replace(",", ""))


def _comparator_after(text: str, end: int):
    m = COMPARATOR_RE.match(text[end:end + 12])
    if not m:
        return None, end
    word = m.group(1)
    for pat, code in COMPARATORS:
        if re.fullmatch(pat, word):
            return code, end + m.end()
    return None, end


def normalize_ref(s: str) -> str:
    return re.sub(r"\s+", "", s)


# 패턴 순서가 중요: 날짜 → 참조 → 기간 → 단위 수량 → 단위 없는 숫자
DATE_RE = re.compile(rf"(\d{{4}})\s*(?:년|[-.])\s*(\d{{1,2}})\s*(?:월|[-.])\s*(\d{{1,2}})\s*(?:일|\.)?")
REF_RE = re.compile(r"「[^」]{1,60}」|제\s*\d+\s*조(?:의\s*\d+)?(?:\s*제\s*\d+\s*항)?(?:\s*제\s*\d+\s*호)?|별표\s*\d+(?:의\s*\d+)?")
PERIOD_RE = re.compile(
    rf"(?P<half>반\s*년)"
    rf"|(?:(?P<y>{NUM}|{SINO_ALT}|{NATIVE_ALT})\s*(?:년|해)(?!\s*\d{{1,2}}\s*월\s*\d))"
    rf"(?:\s*(?P<ym>{NUM}|{NATIVE_ALT})\s*(?:개월|달))?"
    rf"|(?P<m>{NUM}|{NATIVE_ALT})\s*(?:개월|달)"
    rf"|(?P<w>{NUM}|{NATIVE_ALT})\s*주(?:일|간)?(?![가-힣]{{0,0}}택)"
    rf"|(?P<d>{NUM})\s*일(?!\s*반|부)"
)
MONEY_RE = re.compile(rf"(?P<n>{NUM})\s*(?P<mul>억|천만|백만|만|천)?\s*원")
MONEY_MUL = {None: 1, "천": 1e3, "만": 1e4, "백만": 1e6, "천만": 1e7, "억": 1e8}
UNIT_RE = re.compile(rf"(?P<n>{NUM})\s*(?P<u>{UNIT_ALT})(?![a-zA-Z²])(?!(?<=대)(?:구|학|상|로|한))(?!(?<=도)(?:로|시|록|면|지|서|의))|(?P<n2>{NUM})\s*(?P<u2>㎡|m²|%)")
# 단위 없는 숫자: '제2종'의 2처럼 '제' 뒤 숫자는 명칭의 일부로 보고 제외
BARE_RE = re.compile(rf"(?<![\d.,제])(?P<n>{NUM})(?![\d.,])")


def parse(text: str) -> list[Q]:
    """문장에서 세부 사실 후보를 모두 뽑는다. 겹치는 구간은 먼저 잡힌 패턴이 이긴다."""
    taken: list[tuple[int, int]] = []
    out: list[Q] = []

    def free(a, b):
        return all(b <= s or a >= e for s, e in taken)

    def add(q: Q):
        taken.append((q.start, q.end))
        out.append(q)

    for m in DATE_RE.finditer(text):
        y, mo, d = (int(g) for g in m.groups())
        if 1 <= mo <= 12 and 1 <= d <= 31:
            add(Q("DATE", "date", f"{y:04d}-{mo:02d}-{d:02d}", None, None, m.group(0), m.start(), m.end()))

    for m in REF_RE.finditer(text):
        if free(m.start(), m.end()):
            add(Q("REFERENCE", "ref", normalize_ref(m.group(0)), None, None, m.group(0), m.start(), m.end()))

    for m in PERIOD_RE.finditer(text):
        if not m.group(0).strip() or not free(m.start(), m.end()):
            continue
        g = m.groupdict()
        if g["half"]:
            dim, val = "period_month", 6.0
        elif g["y"]:
            dim, val = "period_month", _num(g["y"]) * 12 + (_num(g["ym"]) if g["ym"] else 0)
        elif g["m"]:
            dim, val = "period_month", _num(g["m"])
        elif g["w"]:
            dim, val = "period_day", _num(g["w"]) * 7
        else:
            dim, val = "period_day", _num(g["d"])
        comp, end = _comparator_after(text, m.end())
        add(Q("PERIOD", dim, val, "개월" if dim == "period_month" else "일", comp, text[m.start():end], m.start(), end))

    for m in MONEY_RE.finditer(text):
        if free(m.start(), m.end()):
            val = _num(m.group("n")) * MONEY_MUL[m.group("mul")]
            comp, end = _comparator_after(text, m.end())
            add(Q("QUANTITY", "money", val, "원", comp, text[m.start():end], m.start(), end))

    for m in UNIT_RE.finditer(text):
        if not free(m.start(), m.end()):
            continue
        n = m.group("n") or m.group("n2")
        u = m.group("u") or m.group("u2")
        dim, unit = UNIT_MAP[u]
        comp, end = _comparator_after(text, m.end())
        add(Q("QUANTITY", dim, _num(n), unit, comp, text[m.start():end], m.start(), end))

    for m in BARE_RE.finditer(text):
        if free(m.start(), m.end()):
            comp, end = _comparator_after(text, m.end())
            add(Q("BARE", "bare", _num(m.group("n")), None, comp, text[m.start():end], m.start(), end))

    return sorted(out, key=lambda q: q.start)


# ── 서술 성격(허용/금지/의무)과 예외 표지 ────────────────────
EXEMPT_RE = re.compile(r"(하지\s*않아도|안\s*해도|필요\s*(?:가\s*)?없|의무가\s*없|제외|그러하지\s*아니하|적용(?:되지|하지)\s*않|면제)")
NEG_RE = re.compile(r"(할\s*수\s*없|못\s*하|못한|금지|불가|안\s*됩|아니\s*된|허용되지\s*않|건축할\s*수\s*없|제한됩)")
POS_RE = re.compile(r"(할\s*수\s*있|가능|허용|건축할\s*수\s*있|지을\s*수\s*있)")
OBL_RE = re.compile(r"(하여야\s*한|해야\s*하|해야\s*합|해야\s*해|의무|설치하여야|갖추어야|확보하여야|확보해야)")
EXCEPTION_RE = re.compile(r"(다만[,，]?|단서|제외한다|제외하고|그러하지\s*아니하다|예외)")
CONDITION_RE = re.compile(r"([가-힣A-Za-z0-9·\s]{2,40}?(?:경우|때)(?:에는|에|는)?)")


def modality(text: str) -> str | None:
    """문장의 서술 성격: EXEMPT(의무 면제) > PROHIBIT(금지) > OBLIGE(의무) > ALLOW(허용)."""
    if EXEMPT_RE.search(text):
        return "EXEMPT"
    if NEG_RE.search(text):
        return "PROHIBIT"
    if OBL_RE.search(text):
        return "OBLIGE"
    if POS_RE.search(text):
        return "ALLOW"
    return None


def split_sentences(text: str) -> list[tuple[int, str]]:
    out, start = [], 0
    for m in re.finditer(r"(?<=[.。!?])\s+|(?<=다\.)|(?<=\n)", text):
        seg = text[start:m.start()].strip()
        if seg:
            out.append((start, seg))
        start = m.end()
    tail = text[start:].strip()
    if tail:
        out.append((start, tail))
    return out


# ── 명칭: 용도지역·지구·구역, 법령명 ─────────────────────────
ZONE_NAME_RE = re.compile(
    r"(?:제\s*\d\s*종\s*)?(?:전용|일반|준|중심|근린|유통|보전|생산|자연|계획)?\s*"
    r"(?:주거|상업|공업|녹지|관리|농림|자연환경보전)\s*지역|지구단위계획\s*구역|개발제한\s*구역")
LAW_NAME_RE = re.compile(r"[가-힣·]{2,30}(?:법률|시행령|시행규칙|조례|법)(?![가-힣])")
NOT_LAW = {"방법", "불법", "위법", "적법", "편법", "합법", "문법", "수법", "용법", "기법", "어법", "입법"}


def zone_names(text: str) -> list[str]:
    return [normalize_ref(m.group(0)) for m in ZONE_NAME_RE.finditer(text)]


def law_names(text: str) -> list[str]:
    out = []
    for m in LAW_NAME_RE.finditer(re.sub(r"「[^」]*」", " ", text)):
        w = m.group(0)
        if w not in NOT_LAW and not any(w.endswith(x) for x in NOT_LAW):
            out.append(normalize_ref(w))
    return out
