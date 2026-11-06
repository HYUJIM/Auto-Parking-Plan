"""
사후 검증기 환각 시나리오 테스트.  실행: python -m unittest test_post_verifier -v

예시 조문은 구조 시험용으로 만든 가짜 문구다(실제 법령 원문 아님).
1년 기간, 연면적 조건, 너비 기준, 단서(예외), 설치 의무를 한 조문에 담았다.
"""
import copy
import json
import unittest
from pathlib import Path

from app.verification.fact_ledger import check_completeness, extract_candidates
from jsonschema import Draft202012Validator
from app.verification.post_verifier import finalize, render_fallback, verify

ROOT = Path(__file__).resolve().parents[2] / "app" / "resources"
PID = "prov-example-1"
PROVISION = ("[예시 조문 — 실제 법령 원문 아님] ① 제2종일반주거지역에서 연면적 200제곱미터 이상인 건축물을 건축하는 자는 "
             "사용승인일부터 1년 이내에 부설주차장을 설치하여야 한다. ② 제1항의 주차구획은 너비 2.5미터 이상으로 한다. "
             "다만, 연면적 200제곱미터 미만인 건축물은 그러하지 아니하다.")
PROVISIONS = {PID: PROVISION}


def build_ledger():
    cands = extract_candidates(PID, PROVISION)

    def cid(kind, pred):
        return [c["candidate_id"] for c in cands if c["kind"] == kind and pred(c)]

    q = lambda dim, val, comp: lambda c: c["canonical"]["dim"] == dim and c["canonical"]["value"] == val and c["canonical"]["comparator"] == comp
    facts = [
        {"fact_id": "F_SUBJ", "kind": "SUBJECT", "origin": "provision", "provision_id": PID,
         "quote": "제2종일반주거지역", "name": "제2종일반주거지역", "must_mention": True,
         "candidate_ids": cid("SUBJECT", lambda c: True)},
        {"fact_id": "F_AREA", "kind": "QUANTITY", "origin": "provision", "provision_id": PID,
         "quote": "연면적 200제곱미터 이상인 건축물", "must_mention": True, "key_terms": ["연면적"],
         "canonical": {"dim": "area", "value": 200, "unit": "㎡", "comparator": "GE"},
         "candidate_ids": cid("QUANTITY", q("area", 200.0, "GE"))},
        {"fact_id": "F_PERIOD", "kind": "PERIOD", "origin": "provision", "provision_id": PID,
         "quote": "사용승인일부터 1년 이내에", "must_mention": True, "key_terms": [["사용승인", "준공"]],
         "canonical": {"dim": "period_month", "value": 12, "unit": "개월", "comparator": "LE"},
         "candidate_ids": cid("PERIOD", lambda c: True)},
        {"fact_id": "F_OBL", "kind": "MODALITY", "origin": "provision", "provision_id": PID,
         "quote": "부설주차장을 설치하여야 한다", "modality": "OBLIGE", "must_mention": True,
         "key_terms": ["주차장", "설치"], "candidate_ids": cid("MODALITY", lambda c: True)},
        {"fact_id": "F_WIDTH", "kind": "QUANTITY", "origin": "provision", "provision_id": PID,
         "quote": "주차구획은 너비 2.5미터 이상", "must_mention": True, "key_terms": ["너비"],
         "canonical": {"dim": "length", "value": 2.5, "unit": "m", "comparator": "GE"},
         "candidate_ids": cid("QUANTITY", q("length", 2.5, "GE"))},
        {"fact_id": "F_EXC", "kind": "EXCEPTION", "origin": "provision", "provision_id": PID,
         "quote": "다만, 연면적 200제곱미터 미만인 건축물은 그러하지 아니하다", "modality": "EXEMPT",
         "must_mention": True, "key_terms": ["연면적"],
         "canonical": {"dim": "area", "value": 200, "unit": "㎡", "comparator": "LT"},
         "candidate_ids": cid("EXCEPTION", lambda c: True) + cid("QUANTITY", q("area", 200.0, "LT"))},
        {"fact_id": "F_STATUS", "kind": "STATUS", "origin": "engine", "provision_id": None,
         "quote": "엔진 판정: 조건부 검토", "value": "CONDITIONAL", "must_mention": False},
    ]
    return {"schema_version": "fact_ledger@1.0", "ledger_id": "ledger-example-1", "rule_ids": ["rule-example-1"],
            "provision_ids": [PID], "facts": facts, "dismissed": [], "review_status": "approved"}


GOOD = {
    "schema_version": "explanation@2.0", "category": "PARKING_REQUIREMENT", "audience": "owner",
    "sentences": [
        {"sid": "s1", "role": "headline", "text": "사용승인일부터 1년 이내에 부설주차장을 설치해야 해요.", "fact_ids": ["F_PERIOD", "F_OBL"]},
        {"sid": "s2", "role": "summary", "text": "제2종일반주거지역에서 연면적 200㎡ 이상인 건물을 지을 때 적용돼요.", "fact_ids": ["F_SUBJ", "F_AREA"]},
        {"sid": "s3", "role": "meaning", "text": "주차 칸의 너비는 2.5m 이상이어야 해요.", "fact_ids": ["F_WIDTH"]},
        {"sid": "s4", "role": "exception", "text": "다만 연면적 200㎡ 미만인 건물은 이 의무에서 제외돼요.", "fact_ids": ["F_EXC"]},
        {"sid": "s5", "role": "caution", "text": "건물 용도와 규모 조건에 따라 달라질 수 있어 확인이 필요해요.", "fact_ids": ["F_STATUS"]},
    ],
    "glossary": [{"term": "부설주차장", "plain": "건물에 딸려 함께 만들어야 하는 주차장"}],
}


def variant(**edits):
    """edits: s1='새 문장' 은 문장 교체, drop=['s4'] 은 삭제, add=[{...}] 은 추가, fids={'s3': [...]} 은 인용 교체"""
    e = copy.deepcopy(GOOD)
    for s in e["sentences"]:
        if s["sid"] in edits:
            s["text"] = edits[s["sid"]]
        if s["sid"] in edits.get("fids", {}):
            s["fact_ids"] = edits["fids"][s["sid"]]
    e["sentences"] = [s for s in e["sentences"] if s["sid"] not in edits.get("drop", [])] + edits.get("add", [])
    return e


def codes(rep):
    return {e["code"] for e in rep["errors"]}


class LedgerTests(unittest.TestCase):
    def test_ledger_schema_and_complete(self):
        led = build_ledger()
        schema = json.loads((ROOT / "schemas/fact_ledger.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(list(Draft202012Validator(schema).iter_errors(led)), [])
        self.assertEqual(check_completeness(PROVISIONS, led), [])

    def test_ledger_missing_law_fact_is_caught(self):
        led = build_ledger()
        led["facts"] = [f for f in led["facts"] if f["fact_id"] != "F_WIDTH"]
        gaps = check_completeness(PROVISIONS, led)
        self.assertTrue(any("2.5" in g["quote"] for g in gaps))
        rep = verify(variant(drop=["s3"]), led, provisions=PROVISIONS)
        self.assertIn("LEDGER_INCOMPLETE", codes(rep))

    def test_dismissal_with_reason_is_accepted(self):
        led = build_ledger()
        width = next(f for f in led["facts"] if f["fact_id"] == "F_WIDTH")
        led["facts"].remove(width)
        led["dismissed"] = [{"candidate_id": c, "reason": "주차구획 기준은 별도 규칙에서 설명", "reviewer": "검수자"}
                            for c in width["candidate_ids"]]
        self.assertEqual(check_completeness(PROVISIONS, led), [])


class VerifierTests(unittest.TestCase):
    def setUp(self):
        self.led = build_ledger()

    def check(self, exp, *expected_codes):
        rep = verify(exp, self.led, provisions=PROVISIONS)
        self.assertEqual(rep["verdict"], "FAIL" if expected_codes else "PASS", rep["errors"])
        for c in expected_codes:
            self.assertIn(c, codes(rep), rep["errors"])
        return rep

    # ── 통과해야 하는 경우
    def test_good_passes(self):
        rep = self.check(GOOD)
        self.assertTrue(all(c["status"] == "COVERED" for c in rep["coverage"]))

    def test_equivalent_period_12_months_passes(self):
        self.check(variant(s1="사용승인일부터 12개월 이내에 부설주차장을 설치해야 해요."))

    def test_equivalent_unit_spelling_passes(self):
        self.check(variant(s3="주차 칸의 너비는 2.5미터 이상이어야 해요."))

    # ── B. 세부 왜곡
    def test_period_1year_to_8months(self):
        self.check(variant(s1="사용승인일부터 8개월 이내에 부설주차장을 설치해야 해요."), "DETAIL_MISMATCH", "OMISSION_DETAIL")

    def test_period_1year_to_1year6months(self):
        self.check(variant(s1="사용승인일부터 1년 6개월 이내에 부설주차장을 설치해야 해요."), "DETAIL_MISMATCH")

    def test_period_in_days_not_converted(self):
        self.check(variant(s1="사용승인일부터 365일 이내에 부설주차장을 설치해야 해요."), "DETAIL_MISMATCH")

    def test_comparator_flip(self):
        self.check(variant(s3="주차 칸의 너비는 2.5m를 초과해야 해요."), "COMPARATOR_MISMATCH")

    def test_comparator_dropped(self):
        self.check(variant(s2="제2종일반주거지역에서 연면적 200㎡인 건물을 지을 때 적용돼요."), "COMPARATOR_MISSING")

    def test_unit_conversion_flagged(self):
        self.check(variant(s3="주차 칸의 너비는 250cm 이상이어야 해요."), "UNIT_MISMATCH")

    def test_value_without_unit(self):
        self.check(variant(s2="제2종일반주거지역에서 연면적 200 이상인 건물을 지을 때 적용돼요."), "VALUE_WITHOUT_UNIT")

    def test_obligation_flipped_to_exempt(self):
        self.check(variant(s1="사용승인일부터 1년 이내라면 부설주차장을 설치하지 않아도 돼요."), "POLARITY_MISMATCH")

    def test_subject_changed(self):
        self.check(variant(s2="제3종일반주거지역에서 연면적 200㎡ 이상인 건물을 지을 때 적용돼요."), "SUBJECT_MISMATCH", "OMISSION_DETAIL")

    def test_uncited_value_use(self):
        self.check(variant(s3="연면적 200㎡ 이상 건물의 주차 칸 너비는 2.5m 이상이어야 해요."), "UNCITED_FACT_USE")

    # ── A. 날조
    def test_fabricated_fine(self):
        self.check(variant(add=[{"sid": "s6", "role": "meaning", "text": "위반하면 과태료 50만원이 부과돼요.", "fact_ids": ["F_OBL"]}]),
                   "FABRICATED_VALUE")

    def test_fabricated_date(self):
        self.check(variant(add=[{"sid": "s6", "role": "meaning", "text": "2027년 1월 1일부터 적용돼요.", "fact_ids": ["F_OBL"]}]),
                   "FABRICATED_VALUE")

    def test_fabricated_reference_and_law_name(self):
        self.check(variant(add=[{"sid": "s6", "role": "meaning", "text": "주차장법 제19조에 따른 기준이에요.", "fact_ids": ["F_OBL"]}]),
                   "REFERENCE_FABRICATED", "LAW_NAME_FABRICATED")

    def test_fabricated_permission_without_numbers(self):
        self.check(variant(add=[{"sid": "s6", "role": "meaning", "text": "주차장은 지하에만 설치할 수 있어요.", "fact_ids": ["F_OBL"]}]),
                   "POLARITY_MISMATCH")

    def test_ungrounded_sentence(self):
        self.check(variant(add=[{"sid": "s6", "role": "meaning", "text": "요즘 많이들 이렇게 지어요.", "fact_ids": []}]),
                   "UNGROUNDED_SENTENCE")

    def test_unknown_fact_id(self):
        self.check(variant(fids={"s3": ["F_WIDTH", "F_NOPE"]}), "UNKNOWN_FACT_ID")

    # ── C. 누락
    def test_exception_omitted(self):
        rep = self.check(variant(drop=["s4"]), "OMISSION")
        self.assertIn({"fact_id": "F_EXC", "status": "MISSING"}, rep["coverage"])

    def test_condition_omitted(self):
        self.check(variant(s2="제2종일반주거지역에서 건물을 지을 때 적용돼요.", fids={"s2": ["F_SUBJ"]}), "OMISSION")

    def test_exception_mentioned_but_meaning_lost(self):
        self.check(variant(s4="연면적 200㎡ 미만인 건물도 있어요."), "OMISSION_DETAIL")

    def test_key_term_missing(self):
        self.check(variant(s1="1년 이내에 부설주차장을 설치해야 해요."), "OMISSION_DETAIL")

    # ── 상태 과장
    def test_overstatement(self):
        self.check(variant(add=[{"sid": "s6", "role": "meaning", "text": "주차장만 갖추면 허가를 받을 수 있어요.", "fact_ids": ["F_OBL"]}]),
                   "OVERSTATEMENT")

    def test_caution_missing_when_conditional(self):
        self.check(variant(drop=["s5"]), "CAUTION_MISSING")

    def test_glossary_number_blocked_by_schema(self):
        e = copy.deepcopy(GOOD)
        e["glossary"] = [{"term": "부설주차장", "plain": "200㎡ 이상 건물에 필요한 주차장"}]
        self.check(e, "SCHEMA")


class JudgeAndFinalizeTests(unittest.TestCase):
    def setUp(self):
        self.led = build_ledger()

    def test_judge_adds_failures(self):
        judge = lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                      "sentences": [{"sid": s["sid"], "verdict": "NOT_IN_SOURCE" if s["sid"] == "s2" else "ENTAILED"} for s in sents],
                                      "coverage": []}
        rep = verify(GOOD, self.led, judge=judge)
        self.assertEqual(rep["verdict"], "FAIL")
        self.assertIn("SEMANTIC_NOT_IN_SOURCE", codes(rep))

    def test_judge_cannot_override_deterministic_failure(self):
        judge = lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                      "sentences": [{"sid": s["sid"], "verdict": "ENTAILED"} for s in sents],
                                      "coverage": [{"fact_id": "F_PERIOD", "verdict": "COVERED"}]}
        bad = variant(s1="사용승인일부터 8개월 이내에 부설주차장을 설치해야 해요.")
        self.assertEqual(verify(bad, self.led, judge=judge)["verdict"], "FAIL")

    def test_judge_failure_is_not_a_pass(self):
        def broken(facts, sents):
            raise TimeoutError("judge timeout")
        partial = lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                        "sentences": [{"sid": "s1", "verdict": "ENTAILED"}], "coverage": []}
        for j in (broken, partial):
            rep = verify(GOOD, self.led, judge=j)
            self.assertEqual(rep["verdict"], "FAIL")
            self.assertIn("JUDGE_UNAVAILABLE", codes(rep))

    def test_good_passes_with_agreeing_judge(self):
        ok = lambda facts, sents: {"schema_version": "judge_verdict@1.0",
                                   "sentences": [{"sid": s["sid"], "verdict": "ENTAILED"} for s in sents],
                                   "coverage": [{"fact_id": f["fact_id"], "verdict": "COVERED"} for f in facts if f["kind"] != "STATUS"]}
        self.assertEqual(verify(GOOD, self.led, judge=ok)["verdict"], "PASS")

    def test_finalize_regenerates_with_feedback(self):
        calls = []

        def gen(feedback):
            calls.append(feedback)
            return variant(s1="사용승인일부터 8개월 이내에 부설주차장을 설치해야 해요.") if len(calls) == 1 else GOOD
        out = finalize(self.led, gen, category="PARKING_REQUIREMENT", audience="owner", provisions=PROVISIONS)
        self.assertEqual(out["display_source"], "ai_verified")
        self.assertEqual(len(out["attempts"]), 2)
        self.assertTrue(any("DETAIL_MISMATCH" in f for f in calls[1]))

    def test_finalize_falls_back_to_verified_template(self):
        out = finalize(self.led, lambda fb: variant(drop=["s4"]), category="PARKING_REQUIREMENT",
                       audience="owner", provisions=PROVISIONS)
        self.assertEqual(out["display_source"], "template_fallback")
        self.assertEqual(out["attempts"][-1]["verdict"], "PASS")
        self.assertEqual(len(out["attempts"]), 4)

    def test_fallback_alone_passes(self):
        fb = render_fallback(self.led, "PARKING_REQUIREMENT", "owner")
        self.assertEqual(verify(fb, self.led, provisions=PROVISIONS)["verdict"], "PASS")

    def test_report_matches_schema(self):
        schema = json.loads((ROOT / "schemas/verification_report.schema.json").read_text(encoding="utf-8"))
        for exp in (GOOD, variant(drop=["s4"])):
            rep = verify(exp, self.led, provisions=PROVISIONS)
            self.assertEqual(list(Draft202012Validator(schema).iter_errors(rep)), [])


if __name__ == "__main__":
    unittest.main()
