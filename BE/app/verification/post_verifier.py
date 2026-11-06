"""
사후 검증기 (Post-Verifier) — 화면에 나가기 직전, AI 쉬운 설명의 환각을 최종 차단한다.

여기서 '환각'은 세 가지를 모두 뜻한다.
  A. 날조     : 원문·원장에 없는 숫자, 기간, 금액, 법령명, 조문, 용도지역, 사실
  B. 세부 왜곡 : 값·단위·비교어·서술 성격이 원문과 다름
                (1년→8개월, 60% 이하→60% 미만, 2.5m→250cm, 설치하여야→설치할 수 있다)
  C. 누락     : 원장에서 '반드시 설명'으로 지정된 사실(조건, 기간, 예외, 의무 등)이 빠지거나
                언급은 했지만 값·비교어·핵심어가 빠짐

검사 순서 (결정적 검사 1~7은 AI를 쓰지 않으며, 의미 검증 8은 실패만 추가할 수 있고 통과시킬 수는 없다)
  1. 형식        SCHEMA, UNKNOWN_FACT_ID, UNGROUNDED_SENTENCE
  2. 수치·기간    FABRICATED_VALUE, DETAIL_MISMATCH, UNIT_MISMATCH,
                 COMPARATOR_MISMATCH, COMPARATOR_MISSING, VALUE_WITHOUT_UNIT, UNCITED_FACT_USE
  3. 명칭        REFERENCE_FABRICATED, LAW_NAME_FABRICATED, SUBJECT_MISMATCH
  4. 서술 성격    POLARITY_MISMATCH  (허용/금지/의무/면제 뒤집힘)
  5. 누락        OMISSION, OMISSION_DETAIL
  6. 상태 과장    OVERSTATEMENT, CAUTION_MISSING
  7. 원장 완전성  LEDGER_INCOMPLETE  (원장 자체가 원문을 빠뜨렸는지 — fact_ledger.check_completeness)
  8. 의미 검증    SEMANTIC_CONTRADICTED, SEMANTIC_NOT_IN_SOURCE, SEMANTIC_COVERAGE_PARTIAL
                 JUDGE_UNAVAILABLE (판정 실패·형식 오류·미판정 문장 → 통과 아님)
                 (별도 모델 judge 가 원장 인용문만 보고 문장별 함의/누락 판정)

finalize() 가 전체 흐름을 묶는다: 생성 → 검증 → (실패 시 오류 피드백 재생성, 최대 N회)
→ 그래도 실패하면 원장 인용문으로 만든 템플릿 설명(이것도 검증 통과해야 함) → 화면.
"""
from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

from jsonschema import Draft202012Validator

from app.verification.fact_ledger import check_completeness, ledger_hash
from app.verification.ko_quantity import law_names, modality, normalize_ref, parse, zone_names

SCHEMA_DIR = Path(__file__).resolve().parent.parent / "resources" / "schemas"
CAUTION_WORDS = re.compile(r"확인|달라질|조건|검토가?\s*필요")
BANNED = re.compile(r"허가.{0,6}(?:받을\s*수\s*있|가능|됩니다|나요|난다)|무조건|반드시\s*가능|문제\s*없|확정(?:입니다|됩니다|돼요)")
ASSERTIVE = re.compile(r"(?:가능합니다|가능해요|할\s*수\s*있습니다|지을\s*수\s*있습니다)")


def _family(dim: str) -> str:
    return "period" if dim.startswith("period") else dim


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s)


def _terms_ok(text: str, key_terms) -> bool:
    """key_terms: ["연면적", ["사용승인", "준공"]] → 각 항목(대안 목록이면 그중 하나)이 모두 문장에 있어야 함."""
    t = _norm(text)
    for term in key_terms or []:
        alts = term if isinstance(term, list) else [term]
        if not any(_norm(a) in t for a in alts):
            return False
    return True


def _q_matches(q, canon) -> bool:
    return canon and q.dim == canon["dim"] and float(q.value) == float(canon["value"]) if q.kind != "DATE" \
        else canon and canon["dim"] == "date" and q.value == canon["value"]


# 단위 환산은 '허용'하지 않는다. 환산하면 같은 값이 되는 경우를 찾아 UNIT_MISMATCH 로 정확히 알려주기 위한 표.
CONVERSIONS = {("length_cm", "length"): 0.01, ("area_pyeong", "area"): 3.305785}


def _converted_match(q, numeric_facts) -> bool:
    for f in numeric_facts:
        k = CONVERSIONS.get((q.dim, f["canonical"]["dim"]))
        if k and abs(float(q.value) * k - float(f["canonical"]["value"])) <= 0.01 * float(f["canonical"]["value"]):
            return True
    return False


def _schema_errors(obj, name):
    schema = json.loads((SCHEMA_DIR / name).read_text(encoding="utf-8"))
    v = Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)
    return [{"code": "SCHEMA", "message": f"{'/'.join(map(str, e.absolute_path)) or '(root)'}: {e.message}"}
            for e in v.iter_errors(obj)]


def verify(explanation: dict, ledger: dict, *, provisions: dict[str, str] | None = None, judge=None) -> dict:
    """설명 1건을 원장과 대조해 검증 보고서(verification_report@1.0)를 돌려준다."""
    errors: list[dict] = []

    def err(code, message, sid=None, fact_id=None, expected=None, found=None):
        errors.append({k: v for k, v in dict(code=code, message=message, sid=sid, fact_id=fact_id,
                                              expected=expected, found=found).items() if v is not None})

    errors += _schema_errors(explanation, "explanation.schema.json")
    if errors:
        return _report("FAIL", errors, [], None, explanation, ledger)

    facts = {f["fact_id"]: f for f in ledger["facts"]}
    numeric_facts = [f for f in facts.values() if f.get("canonical")]
    refs = {normalize_ref(f["quote"]) for f in facts.values() if f["kind"] == "REFERENCE"}
    ref_names = {normalize_ref(f.get("name", "")) for f in facts.values() if f["kind"] == "REFERENCE"}
    subjects = {normalize_ref(f["name"]) for f in facts.values() if f["kind"] == "SUBJECT"}
    status_fact = next((f for f in facts.values() if f["kind"] == "STATUS"), None)
    sentences = explanation["sentences"]

    # 7. 원장 완전성 (원장이 원문을 빠뜨리지 않았는지)
    if provisions is not None:
        for gap in check_completeness(provisions, ledger):
            err("LEDGER_INCOMPLETE", f"원장 누락: {gap['quote']!r} ({gap['kind']})", found=gap["quote"])

    parsed_by_sid = {}
    for s in sentences:
        sid, text, cited_ids = s["sid"], s["text"], s["fact_ids"]
        # 1. 근거 연결
        unknown = [i for i in cited_ids if i not in facts]
        for i in unknown:
            err("UNKNOWN_FACT_ID", "원장에 없는 fact_id 인용", sid=sid, fact_id=i)
        cited = [facts[i] for i in cited_ids if i in facts]
        if not cited:
            err("UNGROUNDED_SENTENCE", "근거 사실을 하나도 인용하지 않은 문장", sid=sid, found=text)

        # 3. 명칭: 조문·법령명·용도지역
        qs = parse(text)
        parsed_by_sid[sid] = qs
        for q in qs:
            if q.kind == "REFERENCE" and q.value not in refs:
                err("REFERENCE_FABRICATED", "원장에 없는 조문·법령 참조", sid=sid, found=q.raw)
        for name in law_names(text):
            if name not in ref_names and not any(name in r for r in refs):
                err("LAW_NAME_FABRICATED", "원장에 없는 법령명", sid=sid, found=name)
        for z in zone_names(text):
            if z not in subjects:
                err("SUBJECT_MISMATCH", "원장과 다른 용도지역·구역 명칭", sid=sid, found=z, expected=sorted(subjects))

        # 2. 수치·기간·날짜
        cited_canon = [f for f in cited if f.get("canonical")]
        for q in qs:
            if q.kind == "REFERENCE":
                continue
            if q.kind == "BARE":
                if any(float(q.value) == float(f["canonical"]["value"]) for f in cited_canon
                       if f["canonical"]["dim"] != "date"):
                    err("VALUE_WITHOUT_UNIT", "단위·기간 표시 없이 숫자만 씀", sid=sid, found=q.raw)
                else:
                    err("FABRICATED_VALUE", "원장에 없는 숫자", sid=sid, found=q.raw)
                continue
            exact = [f for f in cited_canon if _q_matches(q, f["canonical"])]
            if exact:
                comps = {f["canonical"].get("comparator") for f in exact}
                if q.comparator in comps:
                    continue
                if q.comparator is None:
                    err("COMPARATOR_MISSING", "원문의 이하/미만/이상/초과/이내 표현이 빠짐", sid=sid,
                        fact_id=exact[0]["fact_id"], expected=exact[0]["canonical"]["comparator"], found=q.raw)
                else:
                    err("COMPARATOR_MISMATCH", "비교어가 원문과 다름", sid=sid, fact_id=exact[0]["fact_id"],
                        expected=sorted(c or "없음" for c in comps), found=q.raw)
                continue
            if any(_q_matches(q, f["canonical"]) for f in numeric_facts):
                other = next(f for f in numeric_facts if _q_matches(q, f["canonical"]))
                err("UNCITED_FACT_USE", "다른 사실의 값을 인용 표시 없이 사용", sid=sid,
                    fact_id=other["fact_id"], found=q.raw)
                continue
            same_family = [f for f in cited_canon if _family(f["canonical"]["dim"]) == _family(q.dim)]
            if same_family:
                err("DETAIL_MISMATCH", "값이 원문과 다름", sid=sid, fact_id=same_family[0]["fact_id"],
                    expected=[f["quote"] for f in same_family], found=q.raw)
            elif q.kind != "DATE" and (_converted_match(q, numeric_facts) or any(
                    float(q.value) == float(f["canonical"]["value"]) for f in numeric_facts
                    if f["canonical"]["dim"] != "date")):
                err("UNIT_MISMATCH", "원문에 없는 단위로 표기(환산 금지)", sid=sid, found=q.raw)
            else:
                err("FABRICATED_VALUE", "원장에 없는 값", sid=sid, found=q.raw)

        # 4. 서술 성격
        smod = modality(text)
        for f in cited:
            fmod = f.get("modality")
            if fmod and smod and smod != fmod:
                err("POLARITY_MISMATCH", "허용/금지/의무/면제 성격이 원문과 다름", sid=sid,
                    fact_id=f["fact_id"], expected=fmod, found=smod)

        # 6. 단정 표현
        if BANNED.search(text):
            err("OVERSTATEMENT", "인허가를 단정하는 표현", sid=sid, found=BANNED.search(text).group(0))

    # 5. 누락 (must_mention)
    coverage = []
    for f in facts.values():
        if not f.get("must_mention") or f["kind"] == "STATUS":
            continue
        citing = [s for s in sentences if f["fact_id"] in s["fact_ids"]]
        if not citing:
            err("OMISSION", "반드시 설명해야 할 원문 사실이 빠짐", fact_id=f["fact_id"], expected=f["quote"])
            coverage.append({"fact_id": f["fact_id"], "status": "MISSING"})
            continue
        ok = False
        for s in citing:
            conds = [_terms_ok(s["text"], f.get("key_terms"))]
            if f.get("canonical"):
                conds.append(any(_q_matches(q, f["canonical"]) and q.comparator == f["canonical"].get("comparator")
                                 for q in parsed_by_sid[s["sid"]]))
            if f.get("modality"):
                conds.append(modality(s["text"]) == f["modality"])
            if f["kind"] == "SUBJECT":
                conds.append(normalize_ref(f["name"]) in zone_names(s["text"]))
            if all(conds):
                ok = True
                break
        if not ok:
            err("OMISSION_DETAIL", "언급은 했지만 값·비교어·핵심어·성격 중 일부가 빠짐",
                fact_id=f["fact_id"], expected=f["quote"])
        coverage.append({"fact_id": f["fact_id"], "status": "COVERED" if ok else "PARTIAL"})

    # 6. 상태 과장 / 유의 문구
    if status_fact and status_fact["value"] != "REVIEWABLE":
        cautions = [s for s in sentences if status_fact["fact_id"] in s["fact_ids"] and CAUTION_WORDS.search(s["text"])]
        if not cautions:
            err("CAUTION_MISSING", "조건부/별도 확인 상태인데 유의 문장이 없음", fact_id=status_fact["fact_id"])
        for s in sentences:
            if ASSERTIVE.search(s["text"]):
                err("OVERSTATEMENT", "조건부/별도 확인 상태에서 단정 표현", sid=s["sid"],
                    found=ASSERTIVE.search(s["text"]).group(0))

    # 8. 의미 검증 (별도 모델). 결정적 오류를 지우지 못하고, 오류를 추가만 한다.
    judge_result = None
    if judge is not None:
        try:
            judge_result = judge([{k: f[k] for k in ("fact_id", "kind", "quote") if k in f}
                                  for f in facts.values()], sentences)
            bad = _schema_errors(judge_result, "judge_verdict.schema.json")
            judged = {j["sid"] for j in judge_result["sentences"]} if not bad else set()
            if bad or judged != {s["sid"] for s in sentences}:
                raise ValueError("판정 형식 오류 또는 일부 문장 미판정")
        except Exception as ex:  # 판정 실패는 통과가 아니라 실패
            err("JUDGE_UNAVAILABLE", f"의미 검증 실패로 통과 불가: {ex}")
            judge_result = {"sentences": [], "coverage": []}
        for j in judge_result.get("sentences", []):
            if j["verdict"] == "CONTRADICTED":
                err("SEMANTIC_CONTRADICTED", j.get("note", "원문과 모순"), sid=j["sid"])
            elif j["verdict"] == "NOT_IN_SOURCE":
                err("SEMANTIC_NOT_IN_SOURCE", j.get("note", "원문에 없는 내용"), sid=j["sid"])
        for c in judge_result.get("coverage", []):
            if c["verdict"] in ("MISSING", "PARTIAL"):
                err("SEMANTIC_COVERAGE_PARTIAL", c.get("note", "의미상 설명이 불충분"), fact_id=c["fact_id"])

    return _report("PASS" if not errors else "FAIL", errors, coverage, judge_result, explanation, ledger)


def _report(verdict, errors, coverage, judge_result, explanation, ledger):
    return {
        "schema_version": "verification_report@1.0",
        "verdict": verdict,
        "checked_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "ledger_hash": ledger_hash(ledger),
        "explanation_hash": hashlib.sha256(json.dumps(explanation, ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
        "errors": errors,
        "coverage": coverage,
        "judge": judge_result,
        "feedback_for_regeneration": [
            f"[{e['code']}] {e['message']}" + (f" / 문장 {e['sid']}" if e.get("sid") else "")
            + (f" / 원문: {e['expected']}" if e.get("expected") else "") + (f" / 작성: {e['found']}" if e.get("found") else "")
            for e in errors],
    }


KIND_LABEL = {"PERIOD": "기간", "QUANTITY": "기준", "DATE": "날짜", "EXCEPTION": "예외",
              "MODALITY": "의무·제한", "SUBJECT": "적용 대상", "REFERENCE": "근거"}


def render_fallback(ledger: dict, category: str, audience: str) -> dict:
    """AI 설명이 끝내 검증을 통과하지 못할 때 쓰는 템플릿 설명. 원장의 원문 인용만 사용한다."""
    facts = ledger["facts"]
    sentences, n = [], 0
    for f in facts:
        if not f.get("must_mention") or f["kind"] == "STATUS":
            continue
        n += 1
        # 인용문 안에 다른 사실의 값이 있으면 함께 인용 (UNCITED_FACT_USE 방지)
        qs = parse(f["quote"])
        also = [g["fact_id"] for g in facts if g is not f and g.get("canonical")
                and any(_q_matches(q, g["canonical"]) for q in qs)]
        also += [g["fact_id"] for g in facts if g is not f and g["kind"] == "SUBJECT"
                 and normalize_ref(g["name"]) in zone_names(f["quote"])]
        sentences.append({"sid": f"t{n}", "role": "meaning",
                          "text": f"{KIND_LABEL.get(f['kind'], '원문')}: “{f['quote']}”",
                          "fact_ids": [f["fact_id"], *dict.fromkeys(also)]})
    st = next((f for f in facts if f["kind"] == "STATUS"), None)
    if st and st["value"] != "REVIEWABLE":
        sentences.append({"sid": "t_caution", "role": "caution",
                          "text": "추가 조건에 따라 결과가 달라질 수 있어 확인이 필요합니다.",
                          "fact_ids": [st["fact_id"]]})
    return {"schema_version": "explanation@2.0", "category": category, "audience": audience,
            "source": "template_fallback", "sentences": sentences, "glossary": []}


def finalize(ledger, generate, *, category, audience, provisions=None, judge=None, max_attempts=3):
    """
    화면에 나갈 설명을 확정한다.
      generate(feedback: list[str]) -> explanation dict   (AI 호출 래퍼, feedback 은 직전 실패 사유)
    반환: {"explanation": dict|None, "display_source": "ai_verified"|"template_fallback"|"basis_only",
           "attempts": [report, ...]}
    화면에는 verdict == PASS 인 설명만 나간다.
    """
    attempts, feedback = [], []
    for _ in range(max_attempts):
        exp = generate(feedback)
        rep = verify(exp, ledger, provisions=provisions, judge=judge)
        attempts.append(rep)
        if rep["verdict"] == "PASS":
            return {"explanation": exp, "display_source": "ai_verified", "attempts": attempts}
        feedback = rep["feedback_for_regeneration"]
    fb = render_fallback(ledger, category, audience)
    rep = verify(fb, ledger, provisions=provisions)  # 템플릿도 같은 검증을 거친다 (judge 불필요: 원문 인용)
    attempts.append(rep)
    if rep["verdict"] == "PASS":
        return {"explanation": fb, "display_source": "template_fallback", "attempts": attempts}
    return {"explanation": None, "display_source": "basis_only", "attempts": attempts}
