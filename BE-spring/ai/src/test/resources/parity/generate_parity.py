"""
파이썬판(BE/app)으로 기대값을 만들어 parity.json 에 저장한다 — 자바판(ai 모듈)이 같은 입력에 같은 결과를 내는지 대조용.

실행 (BE 의 가상환경 파이썬으로):
    BE/.venv/bin/python BE-spring/ai/src/test/resources/parity/generate_parity.py [BE 경로]

BE 경로를 생략하면 이 파일 위치에서 ../../../../../../BE 를 쓴다.
결과(parity.json.gz)는 결정적이다 (난수·시각 없음, checked_at 은 비교에서 뺀다).

담는 것
  * ko_quantity : parse / modality / split_sentences / zone_names / law_names / normalize_ref (법령 원문 전체 + 까다로운 문장)
  * patterns    : 정규식 원문 (자바가 같은 파이썬 패턴에서 출발했는지)
  * candidates  : fact_ledger.extract_candidates
  * numbers     : ai_output._numbers, locate
  * api_schema  : llm.base.api_schema (스키마 파일마다, json.dumps 문자열 그대로)
  * schema      : Draft202012Validator(+registry, FORMAT_CHECKER) 오류 문구·순서
  * extraction  : ai_output.validate_extraction (시드 추출 + 변형)
  * ledgers     : 원장 해시, 완전성 검사, render_fallback
  * verify      : post_verifier.verify 보고서 (checked_at 제외) — 설명 변형 × 판정기 종류
  * fake        : FakeLLM 작성·판정 출력
  * repr/float  : 파이썬 repr()·float() 대조
  * llm_http    : Anthropic·OpenAI 클라이언트를 로컬 HTTP 서버에 붙여 보낸 요청 본문·헤더와, 정해 둔 응답(성공·거절·잘림·
                  형식 오류·HTTP 오류)에 대한 결과·오류 문구 (실제 API 호출 없음)
"""
from __future__ import annotations

import copy
import gzip
import json
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
BE = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else HERE.parents[5] / "BE"
sys.path.insert(0, str(BE))

from jsonschema import Draft202012Validator  # noqa: E402

from app.legal.ingest import load_bundle_file  # noqa: E402
from app.legal.structure import flatten, parse_article  # noqa: E402
from app.legal.supplement import row_text  # noqa: E402
from app.llm.base import api_schema  # noqa: E402
from app.llm.fake import FakeLLM  # noqa: E402
from app.verification import ai_output, ko_quantity  # noqa: E402
from app.verification.fact_ledger import check_completeness, extract_candidates, ledger_hash  # noqa: E402
from app.verification.post_verifier import render_fallback, verify  # noqa: E402

NS = uuid.UUID("0b9d6c3a-1f2e-4d5c-8b7a-6e5f4d3c2b1a")
SCHEMA_DIR = BE / "app" / "resources" / "schemas"
SCHEMA_FILES = sorted(p.name for p in SCHEMA_DIR.glob("*.schema.json"))


def dumps(x) -> str:
    return json.dumps(x, ensure_ascii=False)


def q_dict(q):
    return {"kind": q.kind, "dim": q.dim, "value": q.value, "unit": q.unit, "comparator": q.comparator,
            "raw": q.raw, "start": q.start, "end": q.end}


# ── 말뭉치 ─────────────────────────────────────────────────
def provisions_by_doc():
    """{(document_key, effective_from): {path: text}} — 적재기와 같은 방식으로 조문을 나눈다."""
    out: dict[tuple[str, str], dict[str, str]] = {}
    for p in sorted((BE / "data" / "legal_sources").glob("*.json")):
        b = load_bundle_file(p)
        rows = out.setdefault((b.document.key, b.version.effective_from), {})
        for art in b.articles:
            kind = "annex" if art.kind == "annex" else ("addendum" if art.kind == "addendum" else "article")
            tree = parse_article(art.label, art.path, art.heading, art.text, kind=kind)
            for row in flatten(tree, art.text):
                rows[row["path"]] = row["text"]
    for p in sorted((BE / "data" / "legal_sources" / "supplements").glob("*.json")):
        supp = json.loads(p.read_text(encoding="utf-8"))
        rows = out.setdefault((supp["document_key"], supp["effective_from"]), {})
        for it in supp["items"]:
            if it["kind"] == "pdf_annex":
                tree = parse_article(it["label"], it["path"], it.get("heading"), it["text"], kind="annex")
                for row in flatten(tree, it["text"]):
                    rows.setdefault(row["path"], row["text"])
            elif it["kind"] == "image_table":
                r = it["rows"]
                rows.setdefault(it["path"], "\n".join(row_text(x) for x in r))
                for i, x in enumerate(r[1:], start=1):
                    rows.setdefault(f"{it['path']}/row{i}", row_text(x))
    return out


TRICKY = [
    "7. 중심상업지역 : 200퍼센트 이상 1천500퍼센트 이하",
    "과태료 5천만원", "출입구가 2개 이상인 경우 6.0미터", "6개월 이내",
    "7. 중심상업지역 : 1천 300퍼센트 이하", "8. 일반상업지역 : 1천 퍼센트 이하", "바닥면적의 합계가 2천제곱미터 미만인 것",
    "60도 대향주차 | 4.5미터 | 5.5미터", "너비 8도로", "대구광역시 수성구 2대구간",
    "사용승인일부터 1년 이내에 부설주차장을 설치하여야 한다.", "사용승인일부터 12개월 이내에 설치해야 해요.",
    "1년 6개월 이내", "반년 안에", "반 년", "한 달 이내", "열두 달", "열한 달 동안", "다섯 해", "세 해 이상", "십 년",
    "2주 이내", "3주일", "4주간", "2주택", "30일 이내", "5일반", "3일부터", "365일", "일 년", "이 년",
    "2026-11-12", "2026년 11월 12일", "2026. 11. 12.", "2026.1.2", "2026년 13월 1일", "2026년 1월 32일", "1년 3월 2일",
    "2027년 1월 1일부터 적용돼요.", "제2종일반주거지역", "제 2 종 일반 주거 지역", "제77조제1항제2호", "제 77 조 의 2 제 1 항",
    "별표 1", "별표 2의3", "「국토의 계획 및 이용에 관한 법률」 제77조", "「주차장법」", "주차장법 시행령 제6조",
    "건축법 시행규칙과 대구광역시 주차장 조례", "방법과 불법, 위법, 적법 행위", "입법 예고", "「 」",
    "60%", "60퍼센트", "60프로", "300㎡", "300제곱미터", "300m²", "300m2", "30평", "6m", "2.5미터", "250cm", "25센티미터",
    "12대", "5층", "30세대", "10명", "2배", "3개소", "2개", "1,000㎡", "1,234.5㎡", "12,345,678원", "1.5", "0.5배",
    "50만원", "3억원", "1천만원", "100원 이하", "200백만원", "7천원", "6mm", "6ma", "6m²의", "대학", "대상", "대로", "대한",
    "60도로", "60도시", "60도록", "60도면", "60도지", "60도서", "60도의", "60도 이상",
    "2.5m를 초과해야", "2.5m을 넘지 않는", "2.5m를 초과하지 않는", "200㎡ 미만", "200㎡로 이하", "200㎡으로 이내",
    "200㎡ 까지", "200㎡ 안에", "200㎡ 안으로", "200㎡ 내에", "200㎡ 내로", "200㎡ 넘는", "200㎡ 넘으면", "200㎡의 이상",
    "200 이상", "제2종", "제3조", "1.2.3", "1..2", "12.", ".5", "１２대", "١٢대", "１,０００㎡", "２０２６년 １월 ２일",
    "𝟏𝟐대", "😀12대 😀2026-01-02 😀제3조 😀1년",
    "12 대", "12　대", "12\x1c대", "1천 500퍼센트", "1년 6개월",
    "다만, 연면적 200제곱미터 미만인 건축물은 그러하지 아니하다.",
    "건축할 수 없다. 다만 시장이 인정하는 경우에는 그러하지 아니하다.",
    "설치하지 않아도 됩니다", "할 수 있다", "할 수 없다", "해야 한다", "가능합니다", "의무가 없다", "적용되지 않는다", "면제한다",
    "허용되지 않는다", "제한됩니다", "건축할 수 있다", "지을 수 있다", "금지한다", "불가", "안 됩니다", "아니 된다",
    "첫 문장이다. 둘째 문장이다.\n셋째 줄\n\n  넷째 줄  ", "물음표? 느낌표! 마침표. 끝", "다.다.다.", "   ",
    "", "a", "지구단위계획 구역 안의 개발제한구역", "준주거지역과 자연녹지지역, 계획관리지역", "일반상업지역",
    "연면적 200㎡ 이상인 건물을 지을 때 적용돼요.", "주차 칸의 너비는 2.5m 이상이어야 해요.",
    "위반하면 과태료 50만원이 부과돼요.", "주차장법 제19조에 따른 기준이에요.", "허가를 받을 수 있어요.",
    "무조건 됩니다", "반드시 가능", "문제 없어요", "확정입니다", "검토 필요", "검토가 필요", "달라질 수 있어요",
    "1년 이내라면 부설주차장을 설치하지 않아도 돼요.", "사용승인일부터 8개월 이내에", "사용승인일부터 1년 6개월 이내에",
    "사용승인일부터 365일 이내에", "주차 칸의 너비는 250cm 이상", "연면적 200 이상인 건물",
    "1천2", "1천2345", "1천 2", "99천500", "1천500원", "2천제곱미터", "1천500", "1,000,000", "1,00", "12,3456",
]


def corpus():
    texts, seen = [], set()
    docs = provisions_by_doc()
    for key in sorted(docs):
        for path, text in docs[key].items():
            # 조 전체(긴 원문)는 항·호·줄 단위로 이미 들어가므로 1,000자 이하만 통째로 넣는다
            for t in ([text] if len(text) <= 1000 else []) + text.split("\n"):
                if t not in seen:
                    seen.add(t)
                    texts.append(t)
    for t in TRICKY:
        if t not in seen:
            seen.add(t)
            texts.append(t)
    return docs, texts


# ── 원장·설명 만들기 ─────────────────────────────────────────
def build_ledger(pid: str, text: str, quote: str, status: str, *, key_terms=None, name_tag="x"):
    """근거 인용 구간 안의 후보는 사실로, 밖의 후보는 사유와 함께 제외한 원장 (app/explain/ledger 의 초안과 비슷한 모양)."""
    cands = extract_candidates(pid, text)
    start = text.find(quote)
    end = start + len(quote)
    facts, dismissed = [], []
    n = 0
    for c in cands:
        inside = start >= 0 and c["char_start"] >= start and c["char_end"] <= end
        if not inside:
            dismissed.append({"candidate_id": c["candidate_id"], "reason": "근거 인용 밖", "reviewer": "auto-draft"})
            continue
        n += 1
        numeric = c["kind"] in ("QUANTITY", "PERIOD", "DATE")
        # 앱의 초안처럼: 수치 사실의 인용문은 근거 구간 전체, 참조는 canonical 없이 name 만 (must_mention 아님)
        f = {"fact_id": f"F{n}_{c['kind'][:3]}", "kind": c["kind"], "origin": "provision", "provision_id": pid,
             "quote": text[start:end] if numeric and n % 2 else c["quote"], "candidate_ids": [c["candidate_id"]],
             "must_mention": c["kind"] != "REFERENCE"}
        if numeric:
            f["canonical"] = c["canonical"]
        if c["kind"] == "REFERENCE":
            f["name"] = c["quote"].strip("「」 ")
        if c.get("modality"):
            f["modality"] = c["modality"]
        if "name" in c:
            f["name"] = c["name"]
        if key_terms and c["kind"] in ("QUANTITY", "PERIOD"):
            f["key_terms"] = key_terms
        facts.append(f)
    facts.append({"fact_id": "F_STATUS", "kind": "STATUS", "origin": "engine", "provision_id": None,
                  "quote": "엔진 판정: " + status, "value": status, "must_mention": False})
    return {"schema_version": "fact_ledger@1.0", "ledger_id": f"ledger-{name_tag}", "rule_ids": [f"rule-{name_tag}"],
            "provision_ids": [pid], "facts": facts, "dismissed": dismissed, "review_status": "approved"}


def writer_payload(ledger, category, audience, feedback=None):
    facts = [{k: v for k, v in f.items() if k != "candidate_ids"} for f in ledger["facts"]]
    payload = {"category": category, "audience": audience,
               "review_item": {"category": category, "title": "제목", "status": "REVIEWABLE", "reason_code": None,
                               "criteria_text": "60% 이하"},
               "facts": facts}
    if feedback:
        payload["rejection_feedback"] = "[사후 검증 반려 사유]\n" + "\n".join(f"- {x}" for x in feedback)
    return dumps(payload)


def judge_fn(kind):
    """자바 테스트도 같은 이름으로 같은 판정기를 만든다."""
    if kind is None:
        return None
    if kind == "fake":
        llm = FakeLLM()
        return lambda facts, sents: llm.generate_json(
            task="semantic_judge", system="", schema={},
            user=dumps({"facts": facts, "sentences": [{k: s[k] for k in ("sid", "text", "fact_ids")} for s in sents]})).data
    if kind == "not_in_source_first":
        return lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                     "sentences": [{"sid": s["sid"], "verdict": "NOT_IN_SOURCE" if i == 0 else "ENTAILED"}
                                                   for i, s in enumerate(sents)], "coverage": []}
    if kind == "contradicted_last_with_note":
        return lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                     "sentences": [{"sid": s["sid"], "verdict": "CONTRADICTED" if i == len(sents) - 1 else "ENTAILED",
                                                    "note": "원문은 이하인데 미만으로 씀"} for i, s in enumerate(sents)],
                                     "coverage": [{"fact_id": f["fact_id"], "verdict": "PARTIAL"} for f in facts[:1]]
                                     + [{"fact_id": f["fact_id"], "verdict": "MISSING", "note": "빠짐"} for f in facts[1:2]]}
    if kind == "raise":
        def broken(facts, sents):
            raise TimeoutError("judge timeout")
        return broken
    if kind == "partial":
        return lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                     "sentences": [{"sid": sents[0]["sid"], "verdict": "ENTAILED"}], "coverage": []}
    if kind == "invalid":
        return lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                     "sentences": [{"sid": s["sid"], "verdict": "MAYBE"} for s in sents], "coverage": []}
    raise ValueError(kind)


def mutations(exp):
    """설명 변형들 (이름, 설명)."""
    out = [("as_is", exp)]
    sents = exp["sentences"]

    def edit(fn):
        e = copy.deepcopy(exp)
        fn(e)
        return e

    def repl_all(old, new):
        def f(e):
            for s in e["sentences"]:
                s["text"] = s["text"].replace(old, new)
        return f

    for old, new in [("이하", "미만"), ("이상", "초과"), ("미만", "이하"), ("이내", "이후"), ("퍼센트", "%"), ("미터", "m"),
                     ("제곱미터", "㎡"), ("1", "2"), ("0", "5"), ("년", "개월")]:
        if any(old in s["text"] for s in sents):
            out.append((f"replace:{old}->{new}", edit(repl_all(old, new))))
    if len(sents) > 1:
        out.append(("drop_first", edit(lambda e: e["sentences"].pop(0))))
        out.append(("drop_last", edit(lambda e: e["sentences"].pop())))
    out.append(("empty_fact_ids", edit(lambda e: e["sentences"][0].update(fact_ids=[]))))
    out.append(("unknown_fact_id", edit(lambda e: e["sentences"][0]["fact_ids"].append("F_NOPE"))))
    for i, text in enumerate(["위반하면 과태료 50만원이 부과돼요.", "주차장법 제19조에 따른 기준이에요.", "제3종일반주거지역에도 적용돼요.",
                              "허가를 받을 수 있어요.", "건축이 가능합니다.", "2027년 1월 1일부터 적용돼요.", "높이는 12층까지예요.",
                              "주차장은 지하에만 설치할 수 있어요.", "「건축법」에 따라 300평 이상이면 적용돼요.", "너비는 250cm 이상이에요."]):
        out.append((f"add:{i}", edit(lambda e, t=text: e["sentences"].append(
            {"sid": f"x{i}", "role": "meaning", "text": t, "fact_ids": [e["sentences"][0]["fact_ids"][0]] if e["sentences"][0]["fact_ids"] else []}))))
    out.append(("schema:long_text", edit(lambda e: e["sentences"][0].update(text="가" * 201))))
    out.append(("schema:bad_sid", edit(lambda e: e["sentences"][0].update(sid="S-1"))))
    out.append(("schema:glossary_digit", edit(lambda e: e.update(glossary=[{"term": "부설주차장", "plain": "200㎡ 이상 건물"}]))))
    out.append(("schema:extra_prop", edit(lambda e: e.update(extra=1, another={"a": [1, 2.5, None, True]}))))
    out.append(("schema:missing", edit(lambda e: e.pop("sentences"))))
    out.append(("schema:types", edit(lambda e: e.update(category="NOPE", audience=3, source=None))))
    out.append(("schema:sentence_types", edit(lambda e: e["sentences"].append({"sid": 5, "role": "x", "text": "", "fact_ids": ["a", "a"], "z": 1}))))
    out.append(("schema:too_many", edit(lambda e: e.update(sentences=e["sentences"] * 13))))
    out.append(("schema:empty", edit(lambda e: e.update(sentences=[]))))
    out.append(("schema:not_object", ["list", 1]))
    return out


# ── 스키마 검증 대상 ────────────────────────────────────────
def review_result_instances():
    item = {"category": "BUILDING_COVERAGE", "title": "건폐율", "status": "REVIEWABLE", "reason_code": None,
            "applied": {"criteria_text": "60% 이하", "value_type": "max", "value": 60, "national_range": {"min": 50, "max": 60},
                        "unit": "%", "reference_value": {"label": "참고", "value": 720.0, "unit": "㎡", "formula": "1200 × 0.6"},
                        "uses": None, "table": [{"label": "a", "value": "x", "unit": None}]},
            "basis": [{"layer": "L1_NATIONAL", "document_title": "시행령", "article_label": "제84조", "effective_from": "2026-09-18",
                       "quote": "60퍼센트 이하", "url": "https://www.law.go.kr/x", "rule_id": "123e4567-e89b-12d3-a456-426614174000"}],
            "explanation": {"display_source": "basis_only", "content": None,
                            "verification": {"verdict": "NOT_AVAILABLE", "report_id": None, "checked_at": None,
                                             "must_mention_total": 0, "must_mention_covered": 0}}}
    good = {"schema_version": "review_result@1.0", "review_id": "123e4567-e89b-12d3-a456-426614174000", "as_of_date": "2026-11-08",
            "engine_version": "x", "ruleset_hash": "a" * 64,
            "parcel": {"pnu": "2726010100100010000", "road_address": None, "jibun_address": "대구", "land_area_m2": 1200.5,
                       "land_category": "대", "designations": [{"kind": "zoning", "name": "제2종일반주거지역", "conflict": "included"}],
                       "jurisdiction": {"sido": "대구", "sigungu": "수성구", "supported": True},
                       "location": {"centroid": [128.6, 35.8], "geometry": None},
                       "sources": [{"field": "x", "source": "y", "fetched_at": "not-a-date-time"}]},
            "items": [item], "manual_checks": [], "data_notices": [], "disclaimer": "d"}
    out = [("good", good)]

    def edit(name, fn):
        e = copy.deepcopy(good)
        fn(e)
        out.append((name, e))

    edit("bad_uuid", lambda e: e.update(review_id="123e4567e89b12d3a456426614174000"))
    edit("uuid_braces", lambda e: e.update(review_id="{123e4567-e89b-12d3-a456-426614174000}"))
    edit("uuid_upper", lambda e: e.update(review_id="123E4567-E89B-12D3-A456-426614174000"))
    edit("uuid_urn", lambda e: e.update(review_id="urn:uuid:123e4567-e89b-12d3-a456-426614174000"))
    edit("uuid_trailing_brace", lambda e: e.update(review_id="123e4567-e89b-12d3-a456-426614174000}"))
    edit("uuid_extra_hyphen", lambda e: e.update(review_id="123e4567-e89b-12d3-a456-4266-14174000"))
    edit("uuid_nonhex", lambda e: e.update(review_id="123e4567-e89b-12d3-a456-42661417400g"))
    edit("bad_date", lambda e: e.update(as_of_date="2026-02-30"))
    edit("date_year0", lambda e: e.update(as_of_date="0000-01-01"))
    edit("date_compact", lambda e: e.update(as_of_date="20260101"))
    edit("date_fullwidth", lambda e: e.update(as_of_date="２０２６-01-01"))
    edit("bad_hash", lambda e: e.update(ruleset_hash="A" * 64))
    edit("bad_pnu", lambda e: e["parcel"].update(pnu="12"))
    edit("extra_parcel", lambda e: e["parcel"].update(extra="x", another=1))
    edit("missing_many", lambda e: [e.pop(k) for k in ("items", "disclaimer", "parcel")])
    edit("centroid_short", lambda e: e["parcel"]["location"].update(centroid=[1]))
    edit("centroid_types", lambda e: e["parcel"]["location"].update(centroid=["a", True, None]))
    edit("land_area_str", lambda e: e["parcel"].update(land_area_m2="12"))
    edit("designations_bad", lambda e: e["parcel"].update(designations=[{"kind": "x", "name": 1, "conflict": "unknown", "z": 0}]))
    edit("explanation_mismatch", lambda e: e["items"][0]["explanation"].update(display_source="ai_verified"))
    edit("explanation_content_bad", lambda e: e["items"][0]["explanation"].update(
        display_source="ai_verified", content={"schema_version": "explanation@2.0", "category": "BUILDING_COVERAGE", "audience": "owner",
                                               "sentences": [{"sid": "s1", "role": "headline", "text": "x", "fact_ids": []}]},
        verification={"verdict": "PASS", "report_id": "r", "checked_at": "2026", "must_mention_total": -1,
                      "must_mention_covered": 1.0}))
    edit("explanation_content_ok", lambda e: e["items"][0]["explanation"].update(
        display_source="template_fallback", content={"schema_version": "explanation@2.0", "category": "BUILDING_COVERAGE",
                                                     "audience": "owner", "sentences": [{"sid": "s1", "role": "headline",
                                                                                        "text": "건폐율 60%", "fact_ids": ["a"]}]},
        verification={"verdict": "PASS", "report_id": "r", "checked_at": "x", "must_mention_total": 1, "must_mention_covered": 1}))
    edit("basis_bad", lambda e: e["items"][0]["basis"][0].update(url="ftp://x", quote="가" * 401, rule_id="nope", effective_from="2026-13-01"))
    edit("reason_bad", lambda e: e["items"][0].update(reason_code="NOPE", status="X"))
    edit("integer_float", lambda e: e["items"][0]["explanation"]["verification"].update(must_mention_total=2.0, must_mention_covered=True))
    edit("root_types", lambda e: e.update(items={}, manual_checks="x", data_notices=[{"code": "NOPE"}]))
    out.append(("not_object", [1, "a", None, 1.5, {"k": "v"}]))
    out.append(("null", None))
    return out


def schema_cases():
    cases = []
    for name, inst in review_result_instances():
        cases.append({"schema": "review_result.schema.json", "name": name, "instance": inst})
    # 다른 스키마들: 시드 원장·판정·보고서·추출
    cases.append({"schema": "judge_verdict.schema.json", "name": "judge_bad",
                  "instance": {"schema_version": "judge_verdict@1.0", "sentences": [{"sid": 1, "verdict": "MAYBE", "note": "x" * 201}],
                               "coverage": [{"fact_id": "a"}], "x": None}})
    cases.append({"schema": "fact_ledger.schema.json", "name": "ledger_bad",
                  "instance": {"schema_version": "fact_ledger@1.0", "ledger_id": 1, "rule_ids": [], "provision_ids": [],
                               "facts": [{"fact_id": "bad id!", "kind": "PERIOD", "origin": "provision", "quote": "q", "must_mention": True,
                                          "canonical": {"dim": "x", "value": [], "unit": 1, "comparator": "EQ"},
                                          "key_terms": ["a", ["b"], [], 3], "modality": "NOPE", "value": "X"}],
                               "dismissed": [{"candidate_id": "c", "reason": "x", "reviewer": "r"}], "review_status": "draft"}})
    cases.append({"schema": "rule_extraction.schema.json", "name": "extraction_bad",
                  "instance": {"schema_version": "rule_extraction@1.0", "document_version_id": "not-uuid",
                               "rules": [{"rule_key": "bad", "category": "SETBACK", "title": "t", "automation_level": "auto",
                                          "conditions": [{"field": "zoning", "operator": "eq", "value": [1, "a", None]},
                                                         {"field": "x", "operator": "in", "value": {}}],
                                          "values": [{"value_key": "ratio", "value_type": "max", "evidence": {"provision_id": "p", "quote": "q"}},
                                                     {"value_key": "Bad Key", "value_type": "enum_conditional", "enum_value": None,
                                                      "formula": "ABC", "unit": "km",
                                                      "evidence": {"provision_id": "123e4567-e89b-12d3-a456-426614174000", "quote": "qq"}}],
                                          "confidence": "high"}],
                               "delegations": [], "unresolved": [{"provision_id": "123e4567-e89b-12d3-a456-426614174000", "reason": "other",
                                                                  "note": "n"}]}})
    return cases


def schema_errors(schema_name, instance, *, registry=True):
    if registry:
        v = Draft202012Validator(json.loads((SCHEMA_DIR / schema_name).read_text(encoding="utf-8")), registry=ai_output._registry(),
                                 format_checker=Draft202012Validator.FORMAT_CHECKER)
    else:
        v = Draft202012Validator(json.loads((SCHEMA_DIR / schema_name).read_text(encoding="utf-8")))
    errs = list(v.iter_errors(instance))
    fmt = lambda e: f"{'/'.join(map(str, e.absolute_path)) or '(root)'}: {e.message}"  # noqa: E731
    return {"unsorted": [fmt(e) for e in errs], "sorted": [fmt(e) for e in sorted(errs, key=lambda e: list(e.absolute_path))],
            "keywords": [e.validator for e in errs]}


# ── LLM 클라이언트: 로컬 HTTP 서버로 요청 본문과 응답 처리 대조 ─────────────
ANTHROPIC_OK = {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-fallback-x",
                "content": [{"type": "text", "text": "{\"a\": 1, \"b\": [1.5, \"한\"], \"c\": null}"}],
                "stop_reason": "end_turn", "stop_sequence": None, "usage": {"input_tokens": 10, "output_tokens": 20}}


def _anthropic_body(**over):
    b = copy.deepcopy(ANTHROPIC_OK)
    b.update(over)
    return b


ERR_HEADERS = {"x-should-retry": "false"}
ANTHROPIC_SCENARIOS = [
    ("ok_default", "default", 200, {"request-id": "req_123"}, _anthropic_body()),
    ("ok_off", "off", 200, {"request-id": "req_456"}, _anthropic_body(model="claude-direct")),
    ("ok_no_request_id", "default", 200, {}, _anthropic_body()),
    ("thinking_then_text", "default", 200, {}, _anthropic_body(content=[
        {"type": "thinking", "thinking": "생각", "signature": "sig"}, {"type": "text", "text": "{\"x\": true}"},
        {"type": "text", "text": "{\"y\": 2}"}])),
    ("refusal", "default", 200, {}, _anthropic_body(stop_reason="refusal", content=[])),
    ("max_tokens", "off", 200, {}, _anthropic_body(stop_reason="max_tokens")),
    ("no_text", "default", 200, {}, _anthropic_body(content=[])),
    ("bad_json", "default", 200, {}, _anthropic_body(content=[{"type": "text", "text": "JSON 아님"}])),
    ("not_object", "default", 200, {}, _anthropic_body(content=[{"type": "text", "text": "[1, 2]"}])),
    ("trailing", "default", 200, {}, _anthropic_body(content=[{"type": "text", "text": "{} x"}])),
    ("empty_text", "default", 200, {}, _anthropic_body(content=[{"type": "text", "text": ""}])),
    ("http_401", "default", 401, ERR_HEADERS, {"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}),
    ("http_429", "default", 429, ERR_HEADERS, {"type": "error", "error": {"type": "rate_limit_error", "message": "slow down"}}),
    ("http_400_json", "default", 400, ERR_HEADERS, {"type": "error", "error": {"type": "invalid_request_error", "message": "bad 'schema'"},
                                                     "request_id": "req_9"}),
    ("http_400_text", "off", 400, ERR_HEADERS, "  Bad request text\n"),
    ("http_400_empty", "off", 400, ERR_HEADERS, ""),
    ("http_403", "default", 403, ERR_HEADERS, {"type": "error", "error": {"type": "permission_error", "message": "no"}}),
    ("http_404", "default", 404, ERR_HEADERS, {"type": "error", "error": {"type": "not_found_error", "message": "no model"}}),
    ("http_500", "default", 500, ERR_HEADERS, {"type": "error", "error": {"type": "api_error", "message": "boom"}}),
    ("http_529", "off", 529, ERR_HEADERS, {"type": "error", "error": {"type": "overloaded_error", "message": "busy"}}),
]
OPENAI_OK = {"id": "c1", "object": "chat.completion", "created": 1, "model": "gpt-x-2026",
             "choices": [{"index": 0, "message": {"role": "assistant", "content": "{\"a\": 1, \"b\": \"한\"}"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}}


def _openai_body(content="{\"a\": 1}", finish="stop", **over):
    b = copy.deepcopy(OPENAI_OK)
    b["choices"][0]["message"]["content"] = content
    b["choices"][0]["finish_reason"] = finish
    b.update(over)
    return b


OPENAI_SCENARIOS = [
    ("ok", 200, {}, _openai_body("{\"a\": 1, \"b\": \"한\"}")),
    ("list_data", 200, {}, _openai_body("[1, 2]")),
    ("length", 200, {}, _openai_body("{\"a\"", finish="length")),
    ("bad_json", 200, {}, _openai_body("아님")),
    ("null_content", 200, {}, _openai_body(None)),
    ("no_usage", 200, {}, {k: v for k, v in _openai_body().items() if k != "usage"}),
] + [(f"http_{code}", code, ERR_HEADERS, {"error": {"message": "x", "type": "t", "param": None, "code": None}})
     for code in (400, 401, 403, 404, 409, 418, 422, 429, 500, 503)]


def llm_http_cases():
    import threading  # noqa: PLC0415
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer  # noqa: PLC0415

    from app.llm.anthropic_client import AnthropicLLM  # noqa: PLC0415
    from app.llm.base import LLMError  # noqa: PLC0415
    from app.llm.openai_client import OpenAILLM  # noqa: PLC0415

    state = {"queue": [], "requests": []}

    class H(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            n = int(self.headers.get("content-length", 0))
            body = self.rfile.read(n)
            state["requests"].append({"path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()},
                                      "body": json.loads(body)})
            status, headers, payload = state["queue"].pop(0)
            data = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("content-type", "application/json" if not isinstance(payload, str) else "text/plain")
            for k, v in headers.items():
                self.send_header(k, v)
            self.send_header("content-length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *a):
            pass

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    schema = json.loads((SCHEMA_DIR / "explanation.schema.json").read_text(encoding="utf-8"))
    user = dumps({"category": "BUILDING_COVERAGE", "facts": [{"fact_id": "F1", "quote": "60퍼센트 이하"}]})

    def run(call):
        try:
            r = call()
            return {"ok": True, "data": dumps(r.data), "model": r.model, "request_id": r.request_id,
                    "usage_tokens": [r.usage.get("input_tokens", r.usage.get("prompt_tokens")),
                                     r.usage.get("output_tokens", r.usage.get("completion_tokens"))] if r.usage else None}
        except LLMError as e:
            return {"ok": False, "error": type(e).__name__, "message": str(e)}

    anth = []
    for name, fallbacks, status, headers, payload in ANTHROPIC_SCENARIOS:
        state["queue"] = [(status, headers, payload)]
        state["requests"] = []
        llm = AnthropicLLM(api_key="test-key", model="claude-test-1", effort="high", timeout_s=30, base_url=base,
                           fallbacks=fallbacks, max_retries=0)
        res = run(lambda: llm.generate_json(task="explanation_writer", system="시스템 프롬프트", user=user, schema=schema,
                                            max_tokens=4000))
        req = state["requests"][0]
        anth.append({"name": name, "fallbacks": fallbacks, "status": status, "headers": headers,
                     "payload": payload if isinstance(payload, str) else dumps(payload),
                     "request": {"path": req["path"], "body": req["body"],
                                 "headers": {k: req["headers"].get(k) for k in ("anthropic-beta", "anthropic-version", "x-api-key",
                                                                                "authorization", "content-type")}},
                     "result": res})
    oa = []
    for name, status, headers, payload in OPENAI_SCENARIOS:
        state["queue"] = [(status, headers, payload)]
        state["requests"] = []
        llm = OpenAILLM(api_key="test-key", model="gpt-test-1", timeout_s=30, base_url=base + "/v1")
        res = run(lambda: llm.generate_json(task="rule_extraction", system="시스템", user=user, schema=schema, max_tokens=16000))
        req = state["requests"][0]
        oa.append({"name": name, "status": status, "headers": headers, "payload": dumps(payload),
                   "request": {"path": req["path"], "body": req["body"],
                               "headers": {k: req["headers"].get(k) for k in ("authorization", "content-type")}},
                   "result": res})
    srv.shutdown()
    # 연결 실패 (닫힌 포트)
    dead = AnthropicLLM(api_key="k", model="m", base_url="http://127.0.0.1:9", max_retries=0, timeout_s=5)
    anth.append({"name": "connection_refused", "result": run(lambda: dead.generate_json(
        task="semantic_judge", system="s", user="{}", schema={"type": "object"}))})
    return {"anthropic": anth, "openai": oa}


# ── 실행 ───────────────────────────────────────────────────
def main():
    docs, texts = corpus()
    out: dict = {"generator": "generate_parity.py", "python": sys.version.split()[0]}

    out["patterns"] = {
        "NUM": ko_quantity.NUM, "NATIVE_ALT": ko_quantity.NATIVE_ALT, "SINO_ALT": ko_quantity.SINO_ALT, "UNIT_ALT": ko_quantity.UNIT_ALT,
        "COMPARATOR_RE": ko_quantity.COMPARATOR_RE.pattern, "DATE_RE": ko_quantity.DATE_RE.pattern, "REF_RE": ko_quantity.REF_RE.pattern,
        "PERIOD_RE": ko_quantity.PERIOD_RE.pattern, "MONEY_RE": ko_quantity.MONEY_RE.pattern, "UNIT_RE": ko_quantity.UNIT_RE.pattern,
        "BARE_RE": ko_quantity.BARE_RE.pattern, "EXEMPT_RE": ko_quantity.EXEMPT_RE.pattern, "NEG_RE": ko_quantity.NEG_RE.pattern,
        "POS_RE": ko_quantity.POS_RE.pattern, "OBL_RE": ko_quantity.OBL_RE.pattern, "EXCEPTION_RE": ko_quantity.EXCEPTION_RE.pattern,
        "CONDITION_RE": ko_quantity.CONDITION_RE.pattern, "ZONE_NAME_RE": ko_quantity.ZONE_NAME_RE.pattern,
        "LAW_NAME_RE": ko_quantity.LAW_NAME_RE.pattern,
    }

    kq = []
    for t in texts:
        kq.append({"text": t, "parse": dumps([q_dict(q) for q in ko_quantity.parse(t)]), "modality": ko_quantity.modality(t),
                   "sentences": [[s, seg] for s, seg in ko_quantity.split_sentences(t)], "zones": ko_quantity.zone_names(t),
                   "laws": ko_quantity.law_names(t), "normalized": ko_quantity.normalize_ref(t),
                   "conditions": [m.group(0) for m in ko_quantity.CONDITION_RE.finditer(t)],
                   "numbers": sorted(ai_output._numbers(t))})
    out["ko_quantity"] = kq

    cands = []
    for i, t in enumerate(texts):
        pid = str(uuid.uuid5(NS, f"prov-{i}"))
        cands.append({"provision_id": pid, "text": t, "candidates": dumps(extract_candidates(pid, t))})
    out["candidates"] = cands

    out["locate"] = [{"quote": q, "text": t, "span": list(ai_output.locate(q, t)) if ai_output.locate(q, t) else None}
                     for q, t in [("1년", "사용승인일부터 1년 이내"), ("😀1", "a😀1년 😀1"), ("없음", "있음"), ("", "abc"), ("a", "")]]

    out["api_schema"] = {name: dumps(api_schema(json.loads((SCHEMA_DIR / name).read_text(encoding="utf-8")))) for name in SCHEMA_FILES}
    synthetic = {"$schema": "x", "type": "object", "description": 3, "title": "t",
                 "properties": {"description": {"type": "string", "description": "d"}, "title": {"type": ["string", "null"], "enum": ["a", None, 1]},
                                "n": {"type": ["integer", "number", "boolean", "null", "array", "object"], "enum": [1, 1.5, True, None, "s"],
                                      "items": {"type": "string", "minLength": 1}, "properties": {"a": {"type": "string"}}, "required": ["a"],
                                      "description": "multi", "minimum": 0},
                                "o": {"oneOf": [{"type": "string"}, {"$ref": "#/$defs/d"}], "anyOf": [{"type": "null"}]},
                                "c": {"allOf": [{"if": {}, "then": {}}]}, "c2": {"allOf": []}, "c3": {"allOf": [{"type": "string"}, {"if": {}}]},
                                "e": {"enum": [{"title": "kept?", "default": 1}], "const": {"pattern": "x"}}},
                 "$defs": {"d": {"type": "object", "title": "x"}, "unused": {"type": "string"}}}
    synthetic2 = {"type": "object", "properties": {"a": {"type": "string"}}, "$defs": {"unused": {"type": "string"}}}
    out["api_schema_synthetic"] = [{"schema": s, "expected": dumps(api_schema(s))} for s in (synthetic, synthetic2)]

    out["schema"] = []
    for c in schema_cases():
        out["schema"].append({**c, "registry": schema_errors(c["schema"], c["instance"]),
                              "plain": schema_errors(c["schema"], c["instance"], registry=False)
                              if c["schema"] != "review_result.schema.json" else None})

    # 시드 추출 검증
    ext, ext_provisions = [], {}
    for p in sorted((BE / "data" / "seed" / "extractions").glob("*.json")):
        seed = json.loads(p.read_text(encoding="utf-8"))
        rows = docs.get((seed["document_key"], seed["effective_from"]), {})
        ids = {path: str(uuid.uuid5(NS, f"{seed['document_key']}|{path}")) for path in rows}
        provisions = {ids[path]: text for path, text in rows.items()}
        vid = str(uuid.uuid5(NS, f"version|{seed['document_key']}|{seed['effective_from']}"))

        def resolve(x):
            if isinstance(x, dict):
                return {k: resolve(v) for k, v in x.items()}
            if isinstance(x, list):
                return [resolve(v) for v in x]
            if isinstance(x, str) and x.startswith("@"):
                return vid if x == "@version" else ids.get(x[1:], str(uuid.uuid5(NS, "missing|" + x)))
            return x

        output = resolve(seed["extraction"])
        variants = [("as_is", output)]
        v1 = copy.deepcopy(output)
        if v1["rules"] and v1["rules"][0]["values"]:
            val = v1["rules"][0]["values"][0]
            for k in ("num_min", "num_max", "num_exact"):
                if val.get(k) is not None:
                    val[k] = val[k] + 1
            val["evidence"]["quote"] = val["evidence"]["quote"] + " (없는 말)"
            v1["rules"].append(copy.deepcopy(v1["rules"][0]))
            variants.append(("fabricated", v1))
        v2 = copy.deepcopy(output)
        for r in v2["rules"]:
            for val in r["values"]:
                if val.get("enum_value"):
                    val["enum_value"] = val["enum_value"] + "없음"
                for k in ("num_min", "num_max", "num_exact"):
                    if isinstance(val.get(k), float) and val[k].is_integer():
                        val[k] = int(val[k])
        v2["delegations"] = [{"from_provision_id": next(iter(provisions), vid), "relation": "delegates", "target_hint": "조례",
                              "evidence": {"provision_id": str(uuid.uuid5(NS, "nowhere")), "quote": "없는 조문"}}]
        variants.append(("enum_and_delegation", v2))
        v3 = copy.deepcopy(output)
        v3["rules"][0]["rule_key"] = "lowercase"
        v3["document_version_id"] = "x"
        variants.append(("schema_error", v3))
        ext_provisions[p.name] = provisions
        for name, o in variants:
            res_errors, spans = ai_output.validate_extraction(o, provisions)
            ext.append({"seed": p.name, "variant": name, "output": o, "errors": res_errors, "spans": dumps(spans)})
    out["extraction"] = ext
    out["extraction_provisions"] = ext_provisions

    # 원장·검증
    ledger_cases, verify_cases, fake_cases = [], [], []
    picked = []
    for key in sorted(docs):
        for path, text in docs[key].items():
            if any(c["kind"] in ("QUANTITY", "PERIOD") for c in extract_candidates("p", text)) and len(text) < 400:
                picked.append((key, path, text))
    picked = picked[::max(1, len(picked) // 7)][:7]
    test_prov = ("[예시 조문 — 실제 법령 원문 아님] ① 제2종일반주거지역에서 연면적 200제곱미터 이상인 건축물을 건축하는 자는 "
                 "사용승인일부터 1년 이내에 부설주차장을 설치하여야 한다. ② 제1항의 주차구획은 너비 2.5미터 이상으로 한다. "
                 "다만, 연면적 200제곱미터 미만인 건축물은 그러하지 아니하다.")
    picked.append((("example", "x"), "example", test_prov))
    statuses = ["REVIEWABLE", "CONDITIONAL", "MANUAL_CHECK"]
    judges = [None, "fake", "not_in_source_first", "contradicted_last_with_note", "raise", "partial", "invalid"]
    for n, (key, path, text) in enumerate(picked):
        pid = str(uuid.uuid5(NS, f"{key[0]}|{path}"))
        lines = [ln for ln in text.split("\n") if ln.strip()]
        quote = lines[0] if lines else text
        status = statuses[n % 3]
        ledger = build_ledger(pid, text, quote if n % 2 else text, status,
                              key_terms=[["연면적", "면적"]] if n % 4 == 1 else None, name_tag=str(n))
        provisions = {pid: text}
        ledger_cases.append({"ledger": ledger, "provisions": provisions, "ledger_hash": ledger_hash(ledger),
                             "gaps": dumps(check_completeness(provisions, ledger)),
                             "gaps_without_dismissed": dumps(check_completeness(provisions, {**ledger, "dismissed": []})),
                             "fallback_owner": dumps(render_fallback(ledger, "PARKING_REQUIREMENT", "owner")),
                             "fallback_architect": dumps(render_fallback(ledger, "BUILDING_COVERAGE", "architect"))})
        for audience in ("owner", "architect"):
            user = writer_payload(ledger, "PARKING_REQUIREMENT", audience, feedback=["[X] y"] if audience == "architect" else None)
            fake = FakeLLM()
            w = fake.generate_json(task="explanation_writer", system="s", user=user, schema={}).data
            jpayload = dumps({"facts": [{k: f[k] for k in ("fact_id", "kind", "quote") if k in f} for f in ledger["facts"]],
                              "sentences": [{k: s[k] for k in ("sid", "text", "fact_ids")} for s in w["sentences"]]})
            j = fake.generate_json(task="semantic_judge", system="s", user=jpayload, schema={}).data
            fake_cases.append({"writer_user": user, "writer": dumps(w), "judge_user": jpayload, "judge": dumps(j)})
            if audience == "architect":
                continue
            for mname, exp in mutations(w) + [("template", render_fallback(ledger, "PARKING_REQUIREMENT", "owner"))]:
                runs = []
                for jk in judges if mname in ("as_is", "template") else [None]:
                    for with_prov in (True, False):
                        if not with_prov and jk is not None:
                            continue
                        rep = verify(copy.deepcopy(exp), ledger, provisions=provisions if with_prov else None, judge=judge_fn(jk))
                        rep.pop("checked_at")
                        runs.append({"judge": jk, "with_provisions": with_prov, "report": dumps(rep)})
                verify_cases.append({"ledger_index": n, "mutation": mname, "explanation": exp, "runs": runs})
    # 원장 자체 변형 (사실 누락·키 없음)
    led = copy.deepcopy(ledger_cases[-1]["ledger"])
    led["facts"] = [f for f in led["facts"] if f["kind"] != "EXCEPTION"]
    ledger_cases.append({"ledger": led, "provisions": ledger_cases[-1]["provisions"], "ledger_hash": ledger_hash(led),
                         "gaps": dumps(check_completeness(ledger_cases[-1]["provisions"], led)),
                         "gaps_without_dismissed": dumps(check_completeness(ledger_cases[-1]["provisions"], {**led, "dismissed": []})),
                         "fallback_owner": dumps(render_fallback(led, "PARKING_REQUIREMENT", "owner")),
                         "fallback_architect": dumps(render_fallback(led, "BUILDING_COVERAGE", "architect"))})
    out["ledgers"] = ledger_cases
    out["verify"] = verify_cases
    out["fake"] = fake_cases

    values = [None, True, False, 0, -1, 10 ** 30, 1.0, 1e-05, 1e16, 1e22, -0.0, 0.1, 2.5, 1.7976931348623157e308, 5e-324,
              "", "a", "it's", 'say "hi"', "both ' and \"", "back\\slash", "tab\tnew\nline\rcr", "\x00\x1f\x7f\x80\x9f\xa0\xad",
              "한글 ㎡ ·", "  ​﻿　", "😀", "", "\U000e0001", "힤",
              [], {}, [1, "a", [None]], {"k": [1.5, {"n": None}], "한": True}]
    out["repr"] = [{"value": v, "repr": repr(v), "str": str(v)} for v in values]
    floats = ["1", " 1.5 ", "1_000", "1__0", "_1", "1_", "1e5", "1E-5", ".5", "5.", "+.5e+3", "-0", "inf", "-Infinity", "nan", "NaN",
              "1.2.3", "", " ", "abc", "١٢", "１２.５", "1e", "e1", "0x10", "1 2", " 12 ", "12\x1c"]

    def py_float(s):
        try:
            v = float(s)
            return {"ok": True, "value": repr(v)}
        except ValueError:
            return {"ok": False}

    out["float"] = [{"text": s, **py_float(s)} for s in floats]
    out["llm_http"] = llm_http_cases()

    # 5MB 가 넘는 JSON 이라 gzip 으로 저장한다 (mtime=0: 같은 입력이면 같은 바이트)
    path = HERE / "parity.json.gz"
    data = (json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    with open(path, "wb") as fh, gzip.GzipFile(filename="parity.json", mode="wb", fileobj=fh, mtime=0) as gz:
        gz.write(data)
    print(f"wrote {path} ({path.stat().st_size} bytes): ko_quantity={len(kq)} candidates={len(cands)} schema={len(out['schema'])} "
          f"extraction={len(ext)} ledgers={len(ledger_cases)} verify={len(verify_cases)} fake={len(fake_cases)}")


if __name__ == "__main__":
    main()
