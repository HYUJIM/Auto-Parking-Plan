"""
검토 엔진 (설계서 7장) — AI 없이 SQL 과 코드만으로 동작한다.
같은 필지·기준일·규칙셋이면 언제나 같은 결과가 나오고, ruleset_hash 로 재현할 수 있다.

값 결정 규칙 (3장)
  * L3 값이 있고 L1 범위 안        → L3 값 채택 (검토 가능), 근거는 L3 + L1
  * L3 값이 없음                   → L1 범위만 표시 (조건부 검토, NO_LOCAL_VALUE)
  * L3 값이 L1 범위를 벗어남        → 값을 쓰지 않음 (별도 확인 필요, OUT_OF_RANGE) + 검수 알림
  * L4 중첩                        → 별도 확인 필요 (ZONE_OVERLAY)로 격상, 고시 링크 표시
"""
from __future__ import annotations

import logging
import re
import uuid
from dataclasses import dataclass, field
from datetime import date
from typing import Any

from shapely.geometry import shape
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Rule, ZoneOverlay
from app.db.repo import (
    active_rules_at,
    is_supported,
    jurisdiction_chain,
    pending_amendments,
    ruleset_hash,
    versions_in_force_without_rules,
)
from app.explain.ledger import EngineFact
from app.parcels.snapshot import ParcelSnapshot
from app.review.basis import basis_for_rules, node_for
from app.review.rules_logic import L4_ESCALATE_RE, base_use_name, eval_formula, fmt_num, match_rule, norm, use_group
from app.security.sanitize import safe_url

log = logging.getLogger(__name__)

ENGINE_VERSION = "0.1.0"
AUTO_CATEGORIES = ["ZONING_USE", "BUILDING_COVERAGE", "FLOOR_AREA_RATIO", "PARKING_REQUIREMENT", "PARKING_STALL_SIZE", "DRIVE_AISLE_WIDTH"]
TITLES = {"ZONING_USE": "검토 가능한 주요 용도", "BUILDING_COVERAGE": "건폐율", "FLOOR_AREA_RATIO": "용적률",
          "PARKING_REQUIREMENT": "주차대수 (부설주차장)", "PARKING_STALL_SIZE": "주차구획 크기", "DRIVE_AISLE_WIDTH": "차로 폭"}
STATUS_RANK = {"REVIEWABLE": 0, "CONDITIONAL": 1, "MANUAL_CHECK": 2}
OVERLAY_ESCALATED = {"ZONING_USE", "BUILDING_COVERAGE", "FLOOR_AREA_RATIO", "PARKING_REQUIREMENT"}
DISCLAIMER = "초기 검토용 참고 자료이며 인허가 가능 여부를 확정하지 않습니다. 최종 판단은 관계 기관과 전문가 확인이 필요합니다."
STALL_LABEL = {"compact": "경형", "general": "일반형", "extended": "확장형", "disabled": "장애인전용", "two_wheel": "이륜자동차전용",
               "residential_road": "보도·차도 구분 없는 주거지역 도로"}
FORM_LABEL = {"non_parallel": "평행주차 외", "parallel": "평행주차"}
AISLE_LABEL = {"parallel_0": "평행주차", "perpendicular_90": "직각주차", "angle_60": "60도 대향주차", "angle_45": "45도 대향주차",
               "cross": "교차주차"}
GATE_LABEL = {"one_gate": "출입구 1개", "two_plus_gates": "출입구 2개 이상"}


@dataclass
class ItemDraft:
    category: str
    title: str
    status: str
    reason_code: str | None
    applied: dict[str, Any]
    basis: list[dict[str, Any]]
    rule_parts: list[tuple[Rule, bool]] = field(default_factory=list)  # (규칙, 적용 값 규칙 여부)
    engine_facts: list[EngineFact] = field(default_factory=list)
    sort_order: int = 0

    @property
    def applied_rule_ids(self) -> list[str]:
        return [str(r.id) for r, _ in self.rule_parts]


@dataclass
class EngineOutput:
    items: list[ItemDraft]
    manual_checks: list[dict[str, Any]]
    data_notices: list[dict[str, str]]
    ruleset_hash: str
    overlay: list[dict[str, Any]]
    zoning: str | None


def _empty_applied() -> dict[str, Any]:
    return {"criteria_text": None, "value_type": None, "value": None, "national_range": None, "unit": None,
            "reference_value": None, "uses": None, "table": None}


def _exceptions(rules: list[Rule]) -> bool:
    return any(e.role == "exception" for r in rules for e in r.evidence)


def _pick(rules: list[Rule], chain: list[str]) -> Rule | None:
    """가장 구체적인 관할(시·군·구 > 시·도 > 전국)의 규칙, 같은 관할이면 최신 시행일."""
    if not rules:
        return None
    return sorted(rules, key=lambda r: (chain.index(r.jurisdiction_code) if r.jurisdiction_code in chain else 99,
                                        -(r.effective_from.toordinal() if r.effective_from else 0)))[0]


class ReviewEngine:
    def __init__(self, s: Session, *, as_of: date, inputs: dict[str, Any] | None = None):
        self.s = s
        self.as_of = as_of
        self.inputs = inputs or {}

    # ── 진입점 ─────────────────────────────────────────────
    def run(self, parcel: ParcelSnapshot) -> EngineOutput:
        chain = jurisdiction_chain(parcel.jurisdiction_code)
        notices = list(parcel.notices)
        supported = parcel.supported and is_supported(self.s, parcel.jurisdiction_code)
        rules = active_rules_at(self.s, self.as_of, jurisdiction_codes=chain)
        rhash = ruleset_hash(rules)
        if not supported:
            notices.append({"code": "REGION_NOT_SUPPORTED", "message": "현재 해당 지역의 법규 검토 데이터가 준비되지 않았습니다. (MVP 검증 지역: 대구 수성구)"})
            return EngineOutput([], [], _dedupe(notices), rhash, [], None)

        zoning_list = [d for d in (parcel.designations or []) if d["kind"] == "zoning" and d.get("conflict") != "adjacent"]
        zoning = norm(zoning_list[0]["name"]) if len(zoning_list) == 1 else None
        multi_zoning = len(zoning_list) > 1
        keys = {
            "zoning": zoning,
            "district": [d["name"] for d in (parcel.designations or []) if d["kind"] == "district"],
            "zone": [d["name"] for d in (parcel.designations or []) if d["kind"] == "zone"],
            "land_category": parcel.land_category,
            "site_area": parcel.land_area_m2,
            "building_use": self.inputs.get("building_use"),
            "gross_floor_area": self.inputs.get("gross_floor_area"),
            "households": self.inputs.get("households"),
            "road_width": self.inputs.get("road_width"),
            "special_zone_type": None,
        }
        overlay = self._overlays(parcel)
        keys["special_zone_type"] = [o["zone_type"] for o in overlay] or None

        by_cat: dict[str, list[Rule]] = {c: [] for c in AUTO_CATEGORIES}
        needs_input: dict[str, list[Rule]] = {c: [] for c in AUTO_CATEGORIES}
        for r in rules:
            if r.category not in by_cat:
                continue
            m = match_rule(r, keys)
            if m is True:
                by_cat[r.category].append(r)
            elif m is None:
                needs_input[r.category].append(r)

        items: list[ItemDraft] = [
            self._zoning_use(zoning, multi_zoning, parcel, by_cat["ZONING_USE"], chain),
            self._ratio("BUILDING_COVERAGE", zoning, multi_zoning, parcel, by_cat["BUILDING_COVERAGE"], chain, "최대 건축면적 참고값"),
            self._ratio("FLOOR_AREA_RATIO", zoning, multi_zoning, parcel, by_cat["FLOOR_AREA_RATIO"], chain, "최대 연면적 참고값"),
            self._parking_requirement(by_cat["PARKING_REQUIREMENT"], needs_input["PARKING_REQUIREMENT"], chain),
            self._stall_size(by_cat["PARKING_STALL_SIZE"], chain),
            self._aisle_width(by_cat["DRIVE_AISLE_WIDTH"], chain),
        ]
        for i, it in enumerate(items):  # 특화구역 격상 전에 '승인 규칙 없음' 안내를 먼저 남긴다
            it.sort_order = i
            if not it.rule_parts and it.reason_code == "DATA_MISSING" and zoning is not None:
                notices.append({"code": "RULE_NOT_APPROVED",
                                "message": f"{it.title}: 이 지역·용도지역에 대해 검수 승인된 법규 데이터가 아직 없습니다."})
        if overlay:
            for it in items:
                if it.category in OVERLAY_ESCALATED:
                    it.status, it.reason_code = "MANUAL_CHECK", "ZONE_OVERLAY"
        for doc, ver in versions_in_force_without_rules(self.s, self.as_of, chain):
            notices.append({"code": "RULE_NOT_APPROVED",
                            "message": f"{doc.official_title} {ver.effective_from.isoformat()} 시행 버전의 규칙이 아직 검수되지 않아 이전 버전 기준을 쓰지 않았습니다."})

        for doc, ver in pending_amendments(self.s, self.as_of, chain):
            notices.append({"code": "PENDING_AMENDMENT",
                            "message": f"{doc.official_title} 개정분({ver.effective_from.isoformat()} 시행 예정)은 이번 검토에 반영하지 않았습니다."})
        return EngineOutput(items, self._manual_checks(parcel, overlay), _dedupe(notices), rhash, overlay, zoning)

    # ── 용도지역 · 용도 ───────────────────────────────────
    def _zoning_use(self, zoning, multi, parcel, rules, chain) -> ItemDraft:
        it = ItemDraft("ZONING_USE", TITLES["ZONING_USE"], "MANUAL_CHECK", "DATA_MISSING", _empty_applied(), [])
        if parcel.designations is None or (zoning is None and not multi):
            it.applied["criteria_text"] = "용도지역 정보 확인 필요"
            return it
        if multi:
            names = ", ".join(d["name"] for d in parcel.designations if d["kind"] == "zoning")
            it.applied["criteria_text"] = f"둘 이상의 용도지역에 걸친 대지 ({names}) — 별도 확인 필요"
            return it
        l1 = [r for r in rules if r.layer == "L1_NATIONAL"]
        l3 = [r for r in rules if r.layer == "L3_LOCAL"]
        it.applied["criteria_text"] = f"{zoning} 안에서 건축할 수 있는 건축물"
        if not l1 and not l3:
            return it
        # 국가 법령(별표) 제1호 = 건축할 수 있는 건축물(enum_allowed), 제2호 = 조례로 정하는 건축물(enum_conditional).
        # 조례가 제2호 중 무엇을 허용했는지는 용도 '기본 이름'(' 중 '·괄호 앞)으로 맞춘다 — 표기가 조금씩 다르기 때문.
        l1_vals = [v for r in l1 for v in r.values if v.enum_value and v.value_type.startswith("enum_")]
        l3_vals = [v for r in l3 for v in r.values if v.enum_value and v.value_type.startswith("enum_")]
        l3_by_base: dict[str, list] = {}
        for v in l3_vals:
            l3_by_base.setdefault(base_use_name(v.enum_value), []).append(v)
        has_local = bool(l3)
        listed: list[dict] = []
        consumed: set = set()

        def add(name, status, note, layer):
            listed.append({"use_name": name, "use_group": use_group(name), "status": status, "condition_note": note, "_layer": layer})

        for v in l1_vals:
            if v.value_type == "enum_allowed":
                # 괄호·'으로서 …' 같은 한정 조건이 붙은 용도는 조건부 검토 (기능정의서 13장)
                add(v.enum_value, "CONDITIONAL" if v.condition_note else "REVIEWABLE", v.condition_note, "L1_NATIONAL")
            elif v.value_type == "enum_conditional":
                local = l3_by_base.get(base_use_name(v.enum_value), [])
                if local:
                    for lv in local:
                        if lv.id in consumed:
                            continue
                        consumed.add(lv.id)
                        if lv.value_type == "enum_allowed":
                            add(lv.enum_value, "CONDITIONAL" if lv.condition_note else "REVIEWABLE", lv.condition_note, "L3_LOCAL")
                        elif lv.value_type == "enum_conditional":
                            add(lv.enum_value, "CONDITIONAL", lv.condition_note, "L3_LOCAL")
                elif has_local:
                    add(v.enum_value, "MANUAL_CHECK", "조례의 허용 목록에서 확인되지 않음 — 별도 확인 필요", "L1_NATIONAL")
                else:
                    add(v.enum_value, "CONDITIONAL", v.condition_note or "도시·군계획조례로 정하는 바에 따라 건축할 수 있는 건축물",
                        "L1_NATIONAL")
        for lv in l3_vals:
            if lv.id not in consumed and lv.value_type != "enum_prohibited":
                # 국가 법령 목록과 맞춰지지 않은 조례 항목 (데이터 대조 필요)
                add(lv.enum_value, "MANUAL_CHECK" if l1 else ("REVIEWABLE" if lv.value_type == "enum_allowed" else "CONDITIONAL"),
                    (lv.condition_note or "") + (" — 국가 법령 목록과 대조 필요" if l1 else ""), "L3_LOCAL")
        for grp in ("주거", "근린생활", "업무"):
            if not any(u["use_group"] == grp for u in listed):
                listed.append({"use_name": f"{grp} 용도", "use_group": grp, "status": "MANUAL_CHECK",
                               "condition_note": "검토 가능 용도 목록에서 확인되지 않음 — 별도 확인 필요", "_layer": None})
        it.applied["uses"] = [{k: v for k, v in u.items() if not k.startswith("_")} for u in listed]
        if l1 and has_local:
            it.status, it.reason_code = ("CONDITIONAL", "EXCEPTION_CLAUSE") if _exceptions(l1 + l3) else ("REVIEWABLE", None)
        elif l1:
            it.status, it.reason_code = "CONDITIONAL", "NO_LOCAL_VALUE"
        else:
            it.status, it.reason_code = "MANUAL_CHECK", "DATA_MISSING"
        it.rule_parts = [(r, True) for r in l1 + l3]
        it.basis = basis_for_rules(self.s, l3 + l1, self.as_of)
        # 설명용 엔진 사실: 대상 용도지역 + 묶음별 상태 (원장 인용 가능하도록)
        it.engine_facts.append(EngineFact(kind="SUBJECT", quote=zoning, name=zoning, must_mention=True))
        label = {"REVIEWABLE": "검토 가능", "CONDITIONAL": "조건부 검토", "MANUAL_CHECK": "별도 확인 필요"}
        for grp in ("주거", "근린생활", "업무", "기타"):
            members = [u for u in listed if u["use_group"] == grp and u["_layer"] is not None]
            if not members:
                continue
            worst = max((u["status"] for u in members), key=lambda x: STATUS_RANK[x])
            # 설명용 요약에는 숫자가 없는 기본 이름만 쓴다 (사후 검증에서 근거 없는 숫자로 걸리지 않도록)
            bases = list(dict.fromkeys(re.split(r"[\(\[（]", u["use_name"])[0].split(" 중 ")[0].strip() for u in members))
            names = ", ".join(bases[:8]) + (" 등" if len(bases) > 8 else "")
            it.engine_facts.append(EngineFact(kind="CONDITION", quote=f"{grp} 용도({names}) — {label[worst]}", key_terms=[grp]))
        return it

    # ── 건폐율 / 용적률 ───────────────────────────────────
    def _ratio(self, cat, zoning, multi, parcel, rules, chain, ref_label) -> ItemDraft:
        it = ItemDraft(cat, TITLES[cat], "MANUAL_CHECK", "DATA_MISSING", _empty_applied(), [])
        it.applied["unit"] = "%"
        if zoning is None:
            it.applied["criteria_text"] = ("둘 이상의 용도지역에 걸친 대지 — 별도 확인 필요" if multi else "용도지역 정보 확인 필요")
            return it
        l1 = _pick([r for r in rules if r.layer == "L1_NATIONAL"], chain)
        l3 = _pick([r for r in rules if r.layer == "L3_LOCAL"], chain)
        v1 = next((v for v in l1.values if v.value_key == "ratio"), None) if l1 else None
        v3 = next((v for v in l3.values if v.value_key == "ratio"), None) if l3 else None
        lo, hi = (v1.num_min, v1.num_max) if v1 else (None, None)
        if v1:
            it.applied["national_range"] = {"min": lo, "max": hi}
        if v3 is not None and v1 is not None:
            val = v3.num_max if v3.num_max is not None else v3.num_exact
            in_range = val is not None and (hi is None or val <= hi) and (lo is None or val >= lo)
            it.rule_parts = [(l1, False), (l3, True)]
            it.basis = basis_for_rules(self.s, [l3, l1], self.as_of)
            if in_range:
                it.status, it.reason_code = ("CONDITIONAL", "EXCEPTION_CLAUSE") if _exceptions([l1, l3]) else ("REVIEWABLE", None)
                it.applied.update(criteria_text=f"{fmt_num(val)}% 이하", value_type="max", value=val)
            else:
                it.status, it.reason_code = "MANUAL_CHECK", "OUT_OF_RANGE"
                it.applied["criteria_text"] = "조례 값이 국가 법령 범위를 벗어나 별도 확인 필요 (데이터 검수 대상)"
                log.error("OUT_OF_RANGE: %s L3=%s L1=%s~%s (rules %s, %s) — 검수 필요", cat, val, lo, hi, l3.id, l1.id)
        elif v1 is not None:
            it.rule_parts = [(l1, True)]
            it.basis = basis_for_rules(self.s, [l1], self.as_of)
            it.status, it.reason_code = "CONDITIONAL", "NO_LOCAL_VALUE"
            rng = (f"{fmt_num(lo)}% 이상 " if lo is not None else "") + f"{fmt_num(hi)}% 이하"
            it.applied.update(criteria_text=f"국가 법령 범위 {rng} (지역 조례 값 미확인)", value_type="range" if lo is not None else "max")
        elif v3 is not None:
            it.applied["criteria_text"] = "상위 법령 기준이 검수 승인되지 않아 조례 값을 확정할 수 없음"
            it.basis = basis_for_rules(self.s, [l3], self.as_of)
        if it.applied["value"] is not None and parcel.land_area_m2:
            ratio = it.applied["value"] / 100
            it.applied["reference_value"] = {"label": ref_label, "value": round(parcel.land_area_m2 * ratio, 2), "unit": "㎡",
                                             "formula": f"{fmt_num(parcel.land_area_m2)} × {ratio:.2f}"}
        return it

    # ── 주차 ──────────────────────────────────────────────
    def _parking_requirement(self, rules, needs_input, chain) -> ItemDraft:
        it = ItemDraft("PARKING_REQUIREMENT", TITLES["PARKING_REQUIREMENT"], "MANUAL_CHECK", "DATA_MISSING", _empty_applied(), [])
        it.applied["unit"] = "대"
        use = self.inputs.get("building_use")
        candidates = rules + needs_input
        if not candidates:
            it.applied["criteria_text"] = "주차 기준 확인 필요"
            return it
        if not use or not rules:
            it.status, it.reason_code = "CONDITIONAL", "NEEDS_USE_INPUT"
            it.applied["criteria_text"] = "건축물 용도에 따라 산정"
            table = []
            for r in sorted(candidates, key=lambda r: r.rule_key)[:20]:
                for v in r.values:
                    label = v.condition_note or r.title
                    table.append({"label": label[:80], "value": v.formula or fmt_num(v.num_exact or v.num_max or v.num_min), "unit": v.unit})
            it.applied["table"] = table or None
            local = [r for r in candidates if r.layer == "L3_LOCAL"]
            parts = (local or candidates)[:6]
            it.rule_parts = [(r, True) for r in parts]
            it.basis = basis_for_rules(self.s, parts, self.as_of)
            return it
        rule = _pick([r for r in rules if r.layer == "L3_LOCAL"] or rules, chain)
        v = next((v for v in rule.values if v.formula or v.num_exact is not None), None)
        it.rule_parts = [(rule, True)]
        it.basis = basis_for_rules(self.s, [rule], self.as_of)
        if v and v.formula:
            try:
                n = eval_formula(v.formula, {"gross_floor_area": self.inputs.get("gross_floor_area"),
                                             "facility_area": self.inputs.get("facility_area") or self.inputs.get("gross_floor_area"),
                                             "households": self.inputs.get("households"), "site_area": self.inputs.get("site_area")})
                it.applied.update(value=float(n), value_type="formula", criteria_text=f"{v.condition_note or rule.title}: {v.formula}")
                it.status, it.reason_code = ("CONDITIONAL", "EXCEPTION_CLAUSE") if _exceptions([rule]) else ("REVIEWABLE", None)
            except ValueError:
                it.status, it.reason_code = "CONDITIONAL", "NEEDS_USE_INPUT"
                it.applied["criteria_text"] = "연면적·세대수 등 계산 입력 필요"
        return it

    def _stall_size(self, rules, chain) -> ItemDraft:
        it = ItemDraft("PARKING_STALL_SIZE", TITLES["PARKING_STALL_SIZE"], "MANUAL_CHECK", "DATA_MISSING", _empty_applied(), [])
        it.applied["unit"] = "m"
        rules = [r for r in rules if r.layer == "L1_NATIONAL"] or rules
        if not rules:
            it.applied["criteria_text"] = "주차구획 기준 확인 필요"
            return it
        table, gen = [], {}
        for r in sorted(rules, key=lambda r: r.rule_key):
            for v in sorted(r.values, key=lambda v: v.value_key):
                parts = v.value_key.split(".")
                if len(parts) == 3 and parts[2] in ("width", "length"):
                    form, typ, dim = parts
                    label = f"{FORM_LABEL.get(form, form)} · {STALL_LABEL.get(typ, typ)} {'너비' if dim == 'width' else '길이'}"
                    if form == "non_parallel" and typ == "general":
                        gen[dim] = v.num_min
                else:
                    label = v.value_key
                table.append({"label": label, "value": v.num_min if v.num_min is not None else (v.num_exact or v.num_max),
                              "unit": "m 이상" if v.num_min is not None else v.unit})
        it.applied["table"] = table
        if gen.get("width") and gen.get("length"):
            it.applied["criteria_text"] = f"일반형 {fmt_num(gen['width'])}m × {fmt_num(gen['length'])}m 이상"
        it.status, it.reason_code = ("CONDITIONAL", "EXCEPTION_CLAUSE") if _exceptions(rules) else ("REVIEWABLE", None)
        it.rule_parts = [(r, True) for r in rules]
        it.basis = basis_for_rules(self.s, rules, self.as_of)
        return it

    def _aisle_width(self, rules, chain) -> ItemDraft:
        it = ItemDraft("DRIVE_AISLE_WIDTH", TITLES["DRIVE_AISLE_WIDTH"], "MANUAL_CHECK", "DATA_MISSING", _empty_applied(), [])
        it.applied["unit"] = "m"
        rules = [r for r in rules if r.layer == "L1_NATIONAL"] or rules
        if not rules:
            it.applied["criteria_text"] = "차로 폭 기준 확인 필요"
            return it
        table = []
        perp = None
        for r in sorted(rules, key=lambda r: r.rule_key):
            for v in sorted(r.values, key=lambda v: v.value_key):
                parts = v.value_key.split(".")
                if len(parts) == 2 and parts[0] in AISLE_LABEL:
                    label = f"{AISLE_LABEL[parts[0]]} 차로 ({GATE_LABEL.get(parts[1], parts[1])})"
                    if parts[0] == "perpendicular_90" and parts[1] == "one_gate":
                        perp = v.num_min
                elif v.value_key.startswith("gate."):
                    label = "출입구 너비" + (" (" + v.condition_note + ")" if v.condition_note else "")
                else:
                    label = v.value_key
                table.append({"label": label[:80], "value": v.num_min if v.num_min is not None else (v.num_exact or v.num_max),
                              "unit": "m 이상" if v.num_min is not None else v.unit})
        it.applied["table"] = table
        it.applied["criteria_text"] = (f"직각주차 차로 {fmt_num(perp)}m 이상 (출입구 1개 기준) — " if perp else "") + "기준정보 제공, 실제 충족 여부는 배치 단계에서 검토"
        it.status, it.reason_code = ("CONDITIONAL", "EXCEPTION_CLAUSE") if _exceptions(rules) else ("REVIEWABLE", None)
        it.rule_parts = [(r, True) for r in rules]
        it.basis = basis_for_rules(self.s, rules, self.as_of)
        return it

    # ── L4 중첩 · 별도 확인 ────────────────────────────────
    def _overlays(self, parcel: ParcelSnapshot) -> list[dict]:
        found = []
        geom = None
        if parcel.geometry:
            try:
                geom = shape(parcel.geometry)
            except Exception:  # noqa: BLE001
                geom = None
        for z in self.s.scalars(select(ZoneOverlay)).all():
            if z.effective_from and z.effective_from > self.as_of or z.effective_to and z.effective_to < self.as_of:
                continue
            hit = parcel.pnu in (z.pnu_list or [])
            if not hit and geom is not None and z.geom:
                try:
                    hit = geom.intersects(shape(z.geom)) and geom.intersection(shape(z.geom)).area > 0
                except Exception:  # noqa: BLE001
                    hit = False
            if hit:
                found.append({"zone_type": z.zone_type, "name": z.name, "document_id": str(z.document_id), "source": "zone_overlay"})
        for d in parcel.designations or []:
            if d["kind"] in ("zone", "district", "other") and L4_ESCALATE_RE.search(d["name"]) and d.get("conflict") != "adjacent":
                found.append({"zone_type": "designation", "name": d["name"], "document_id": None, "source": "parcel_designation"})
        return found

    def _manual_checks(self, parcel: ParcelSnapshot, overlay: list[dict]) -> list[dict]:
        road_why = "도로 폭과 진출입 위치는 현장·배치 조건에 따라 달라집니다."
        if parcel.road_side:
            road_why += f" (토지특성 자료의 도로접면 표기: {parcel.road_side})"
        districts = [d["name"] for d in (parcel.designations or []) if d["kind"] in ("district", "zone")]
        related = []
        for o in overlay:
            if o["document_id"]:
                from app.db.models import Provision, SourceDocument  # noqa: PLC0415
                doc = self.s.get(SourceDocument, uuid.UUID(o["document_id"]))
                ver = next(iter(sorted(doc.versions, key=lambda v: v.effective_from or date.min, reverse=True)), None) if doc else None
                if ver is not None:
                    p = self.s.scalar(select(Provision).where(Provision.version_id == ver.id).order_by(Provision.sort_order))
                    if p is not None:
                        n = node_for(self.s, p, quote=None, rule_id=None)
                        n.pop("_sort", None), n.pop("_pid", None)
                        related.append(n)
                    else:
                        related.append({"layer": doc.layer, "document_title": doc.official_title, "article_label": None,
                                        "effective_from": ver.effective_from.isoformat() if ver.effective_from else None,
                                        "quote": None, "url": safe_url(ver.source_url), "rule_id": None})
            else:
                # 토지이용계획에서만 확인된 구역: 고시 원문을 아직 등록하지 않았으므로 토지이음 열람 경로를 안내한다
                related.append({"layer": "L4_SPECIAL_ZONE", "document_title": f"{o['name']} (토지이음에서 결정 고시·도면 확인)",
                                "article_label": None, "effective_from": None, "quote": None,
                                "url": "https://www.eum.go.kr/web/am/amMain.jsp", "rule_id": None})
        trig = None
        if overlay:
            trig = ", ".join(sorted({o["name"] for o in overlay})) + " 중첩 확인"
        elif districts:
            trig = ", ".join(districts) + " 지정 확인"
        return [
            {"category": "ROAD_ACCESS", "title": "도로 및 진출입 조건", "why": road_why,
             "what_to_check": ["도로 폭", "접도 조건", "차량 진출입 가능 위치"], "related_basis": [], "triggered_by": None},
            {"category": "SETBACK", "title": "건축선 및 이격거리", "why": "건축선과 이격거리는 도로·인접 대지 조건과 별도 지정 여부에 따라 달라집니다.",
             "what_to_check": ["도로경계", "인접대지경계", "별도 건축선 지정 여부"], "related_basis": [], "triggered_by": None},
            {"category": "HEIGHT_SUNLIGHT", "title": "높이 및 일조 조건", "why": "일조·높이 기준은 용도지역, 인접 대지 방향과 건축 계획에 따라 달라집니다.",
             "what_to_check": ["일조권", "높이 제한", "사선 제한 등"], "related_basis": [], "triggered_by": None},
            {"category": "SPECIAL_ZONE", "title": "추가 지역 규제", "why": "지구단위계획 등 개별 계획·고시는 자동 반영하지 않습니다.",
             "what_to_check": ["지구단위계획", "특별계획구역", "경관지구", "특화구역", "기타 개별 인허가 조건"],
             "related_basis": related, "triggered_by": trig},
        ]


def _dedupe(notices: list[dict]) -> list[dict]:
    seen, out = set(), []
    for n in notices:
        k = (n["code"], n["message"])
        if k not in seen:
            seen.add(k)
            out.append(n)
    return out
