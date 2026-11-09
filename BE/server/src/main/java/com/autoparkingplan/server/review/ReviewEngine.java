package com.autoparkingplan.server.review;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.db.RuleQueries;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.db.entity.ZoneOverlay;
import com.autoparkingplan.server.explain.EngineFact;
import com.autoparkingplan.server.parcels.ParcelSnapshot;
import com.autoparkingplan.server.parcels.ParcelSnapshot.Designation;
import com.autoparkingplan.server.security.Sanitize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 검토 엔진 (설계서 7장) — AI 없이 SQL 과 코드만으로 동작한다 (파이썬 app.review.engine).
 * 같은 필지·기준일·규칙셋이면 언제나 같은 결과가 나오고, ruleset_hash 로 재현할 수 있다.
 *
 * <p>값 결정 규칙 (3장)
 * <ul>
 *   <li>L3 값이 있고 L1 범위 안 → L3 값 채택 (검토 가능), 근거는 L3 + L1</li>
 *   <li>L3 값이 없음 → L1 범위만 표시 (조건부 검토, NO_LOCAL_VALUE)</li>
 *   <li>L3 값이 L1 범위를 벗어남 → 값을 쓰지 않음 (별도 확인 필요, OUT_OF_RANGE) + 검수 알림</li>
 *   <li>L4 중첩 → 별도 확인 필요 (ZONE_OVERLAY)로 격상, 고시 링크 표시</li>
 * </ul>
 */
public final class ReviewEngine {
    private static final Logger log = LoggerFactory.getLogger(ReviewEngine.class);

    public static final String ENGINE_VERSION = "0.1.0";
    public static final List<String> AUTO_CATEGORIES = List.of("ZONING_USE", "BUILDING_COVERAGE", "FLOOR_AREA_RATIO", "PARKING_REQUIREMENT",
            "PARKING_STALL_SIZE", "DRIVE_AISLE_WIDTH");
    public static final Map<String, String> TITLES = Map.of("ZONING_USE", "검토 가능한 주요 용도", "BUILDING_COVERAGE", "건폐율",
            "FLOOR_AREA_RATIO", "용적률", "PARKING_REQUIREMENT", "주차대수 (부설주차장)", "PARKING_STALL_SIZE", "주차구획 크기",
            "DRIVE_AISLE_WIDTH", "차로 폭");
    public static final Map<String, Integer> STATUS_RANK = Map.of("REVIEWABLE", 0, "CONDITIONAL", 1, "MANUAL_CHECK", 2);
    static final Set<String> OVERLAY_ESCALATED = Set.of("ZONING_USE", "BUILDING_COVERAGE", "FLOOR_AREA_RATIO", "PARKING_REQUIREMENT");
    public static final String DISCLAIMER = "초기 검토용 참고 자료이며 인허가 가능 여부를 확정하지 않습니다. 최종 판단은 관계 기관과 전문가 확인이 필요합니다.";
    static final Map<String, String> STALL_LABEL = Map.of("compact", "경형", "general", "일반형", "extended", "확장형", "disabled", "장애인전용",
            "two_wheel", "이륜자동차전용", "residential_road", "보도·차도 구분 없는 주거지역 도로");
    static final Map<String, String> FORM_LABEL = Map.of("non_parallel", "평행주차 외", "parallel", "평행주차");
    static final Map<String, String> AISLE_LABEL = Map.of("parallel_0", "평행주차", "perpendicular_90", "직각주차", "angle_60", "60도 대향주차",
            "angle_45", "45도 대향주차", "cross", "교차주차");
    static final Map<String, String> GATE_LABEL = Map.of("one_gate", "출입구 1개", "two_plus_gates", "출입구 2개 이상");
    private static final Pattern USE_BASE_SPLIT = Pattern.compile("[\\(\\[（]");

    /** 적용 규칙 하나 (규칙, 적용 값 규칙 여부). */
    public record RulePart(Rule rule, boolean valueRule) {
    }

    /** 검토 항목 초안. */
    public static final class ItemDraft {
        public final String category;
        public final String title;
        public String status;
        public String reasonCode;
        public final ObjectNode applied;
        public List<ObjectNode> basis;
        public List<RulePart> ruleParts = new ArrayList<>();
        public final List<EngineFact> engineFacts = new ArrayList<>();
        public int sortOrder;

        ItemDraft(String category, String status, String reasonCode) {
            this.category = category;
            this.title = TITLES.get(category);
            this.status = status;
            this.reasonCode = reasonCode;
            this.applied = emptyApplied();
            this.basis = new ArrayList<>();
        }

        public List<String> appliedRuleIds() {
            return ruleParts.stream().map(p -> p.rule().getId().toString()).toList();
        }
    }

    public record EngineOutput(List<ItemDraft> items, List<ObjectNode> manualChecks, List<ObjectNode> dataNotices, String rulesetHash,
            List<ObjectNode> overlay, String zoning) {
    }

    private final EntityManager em;
    private final RuleQueries q;
    private final LocalDate asOf;
    private final Map<String, Object> inputs;

    public ReviewEngine(EntityManager em, RuleQueries q, LocalDate asOf, Map<String, Object> inputs) {
        this.em = em;
        this.q = q;
        this.asOf = asOf;
        this.inputs = inputs == null ? Map.of() : inputs;
    }

    static ObjectNode emptyApplied() {
        ObjectNode a = Json.object();
        for (String k : new String[] {"criteria_text", "value_type", "value", "national_range", "unit", "reference_value", "uses", "table"}) {
            a.putNull(k);
        }
        return a;
    }

    static ObjectNode notice(String code, String message) {
        ObjectNode n = Json.object();
        n.put("code", code);
        n.put("message", message);
        return n;
    }

    private static boolean exceptions(List<Rule> rules) {
        return rules.stream().anyMatch(r -> r.getEvidence().stream().anyMatch(e -> "exception".equals(e.getRole())));
    }

    /** 가장 구체적인 관할(시·군·구 > 시·도 > 전국)의 규칙, 같은 관할이면 최신 시행일. */
    static Rule pick(List<Rule> rules, List<String> chain) {
        if (rules.isEmpty()) {
            return null;
        }
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.<Rule>comparingInt(r -> chain.contains(r.getJurisdictionCode()) ? chain.indexOf(r.getJurisdictionCode()) : 99)
                .thenComparingLong(r -> -(r.getEffectiveFrom() != null ? r.getEffectiveFrom().toEpochDay() : Long.MIN_VALUE / 2)));
        return sorted.getFirst();
    }

    private List<ObjectNode> basis(List<Rule> rules) {
        return Basis.basisForRules(em, rules, asOf);
    }

    // ── 진입점 ─────────────────────────────────────────────
    public EngineOutput run(ParcelSnapshot parcel) {
        List<String> chain = RuleQueries.jurisdictionChain(parcel.jurisdictionCode());
        List<ObjectNode> notices = new ArrayList<>(parcel.notices());
        boolean supported = parcel.supported() && q.isSupported(parcel.jurisdictionCode());
        List<Rule> rules = q.activeRulesAt(asOf, chain, null);
        String rhash = RuleQueries.rulesetHash(rules);
        if (!supported) {
            notices.add(notice("REGION_NOT_SUPPORTED", "현재 해당 지역의 법규 검토 데이터가 준비되지 않았습니다. (MVP 검증 지역: 대구 수성구)"));
            return new EngineOutput(List.of(), List.of(), dedupe(notices), rhash, List.of(), null);
        }
        List<Designation> desig = parcel.designations() == null ? List.of() : parcel.designations();
        List<Designation> zoningList = desig.stream().filter(d -> "zoning".equals(d.kind()) && !"adjacent".equals(d.conflict())).toList();
        String zoning = zoningList.size() == 1 ? RulesLogic.norm(zoningList.getFirst().name()) : null;
        boolean multiZoning = zoningList.size() > 1;
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("zoning", zoning);
        keys.put("district", desig.stream().filter(d -> "district".equals(d.kind())).map(Designation::name).toList());
        keys.put("zone", desig.stream().filter(d -> "zone".equals(d.kind())).map(Designation::name).toList());
        keys.put("land_category", parcel.landCategory());
        keys.put("site_area", parcel.landAreaM2());
        keys.put("building_use", inputs.get("building_use"));
        keys.put("gross_floor_area", inputs.get("gross_floor_area"));
        keys.put("households", inputs.get("households"));
        keys.put("road_width", inputs.get("road_width"));
        List<ObjectNode> overlay = overlays(parcel);
        List<String> zoneTypes = overlay.stream().map(o -> o.get("zone_type").stringValue()).toList();
        keys.put("special_zone_type", zoneTypes.isEmpty() ? null : zoneTypes);

        Map<String, List<Rule>> byCat = new HashMap<>();
        Map<String, List<Rule>> needsInput = new HashMap<>();
        for (String c : AUTO_CATEGORIES) {
            byCat.put(c, new ArrayList<>());
            needsInput.put(c, new ArrayList<>());
        }
        for (Rule r : rules) {
            if (!byCat.containsKey(r.getCategory())) {
                continue;
            }
            Boolean m = RulesLogic.matchRule(r, keys);
            if (Boolean.TRUE.equals(m)) {
                byCat.get(r.getCategory()).add(r);
            } else if (m == null) {
                needsInput.get(r.getCategory()).add(r);
            }
        }

        List<ItemDraft> items = List.of(
                zoningUse(zoning, multiZoning, parcel, byCat.get("ZONING_USE")),
                ratio("BUILDING_COVERAGE", zoning, multiZoning, parcel, byCat.get("BUILDING_COVERAGE"), chain, "최대 건축면적 참고값"),
                ratio("FLOOR_AREA_RATIO", zoning, multiZoning, parcel, byCat.get("FLOOR_AREA_RATIO"), chain, "최대 연면적 참고값"),
                parkingRequirement(byCat.get("PARKING_REQUIREMENT"), needsInput.get("PARKING_REQUIREMENT"), chain),
                stallSize(byCat.get("PARKING_STALL_SIZE")),
                aisleWidth(byCat.get("DRIVE_AISLE_WIDTH")));
        for (int i = 0; i < items.size(); i++) { // 특화구역 격상 전에 '승인 규칙 없음' 안내를 먼저 남긴다
            ItemDraft it = items.get(i);
            it.sortOrder = i;
            if (it.ruleParts.isEmpty() && "DATA_MISSING".equals(it.reasonCode) && zoning != null) {
                notices.add(notice("RULE_NOT_APPROVED", it.title + ": 이 지역·용도지역에 대해 검수 승인된 법규 데이터가 아직 없습니다."));
            }
        }
        if (!overlay.isEmpty()) {
            for (ItemDraft it : items) {
                if (OVERLAY_ESCALATED.contains(it.category)) {
                    it.status = "MANUAL_CHECK";
                    it.reasonCode = "ZONE_OVERLAY";
                }
            }
        }
        for (RuleQueries.DocVersion dv : q.versionsInForceWithoutRules(asOf, chain)) {
            notices.add(notice("RULE_NOT_APPROVED", dv.document().getOfficialTitle() + " " + dv.version().getEffectiveFrom()
                    + " 시행 버전의 규칙이 아직 검수되지 않아 이전 버전 기준을 쓰지 않았습니다."));
        }
        for (RuleQueries.DocVersion dv : q.pendingAmendments(asOf, chain)) {
            notices.add(notice("PENDING_AMENDMENT", dv.document().getOfficialTitle() + " 개정분(" + dv.version().getEffectiveFrom()
                    + " 시행 예정)은 이번 검토에 반영하지 않았습니다."));
        }
        return new EngineOutput(items, manualChecks(parcel, overlay), dedupe(notices), rhash, overlay, zoning);
    }

    // ── 용도지역 · 용도 ───────────────────────────────────
    private record Listed(ObjectNode json, String layer) {
    }

    private ItemDraft zoningUse(String zoning, boolean multi, ParcelSnapshot parcel, List<Rule> rules) {
        ItemDraft it = new ItemDraft("ZONING_USE", "MANUAL_CHECK", "DATA_MISSING");
        if (parcel.designations() == null || (zoning == null && !multi)) {
            it.applied.put("criteria_text", "용도지역 정보 확인 필요");
            return it;
        }
        if (multi) {
            String names = String.join(", ", parcel.designations().stream().filter(d -> "zoning".equals(d.kind())).map(Designation::name).toList());
            it.applied.put("criteria_text", "둘 이상의 용도지역에 걸친 대지 (" + names + ") — 별도 확인 필요");
            return it;
        }
        List<Rule> l1 = rules.stream().filter(r -> "L1_NATIONAL".equals(r.getLayer())).toList();
        List<Rule> l3 = rules.stream().filter(r -> "L3_LOCAL".equals(r.getLayer())).toList();
        it.applied.put("criteria_text", zoning + " 안에서 건축할 수 있는 건축물");
        if (l1.isEmpty() && l3.isEmpty()) {
            return it;
        }
        // 국가 법령(별표) 제1호 = 건축할 수 있는 건축물(enum_allowed), 제2호 = 조례로 정하는 건축물(enum_conditional).
        // 조례가 제2호 중 무엇을 허용했는지는 용도 '기본 이름'(' 중 '·괄호 앞)으로 맞춘다 — 표기가 조금씩 다르기 때문.
        List<RuleValue> l1Vals = enumValues(l1);
        List<RuleValue> l3Vals = enumValues(l3);
        Map<String, List<RuleValue>> l3ByBase = new LinkedHashMap<>();
        for (RuleValue v : l3Vals) {
            l3ByBase.computeIfAbsent(RulesLogic.baseUseName(v.getEnumValue()), k -> new ArrayList<>()).add(v);
        }
        boolean hasLocal = !l3.isEmpty();
        List<Listed> listed = new ArrayList<>();
        Set<UUID> consumed = new HashSet<>();
        for (RuleValue v : l1Vals) {
            if ("enum_allowed".equals(v.getValueType())) {
                // 괄호·'으로서 …' 같은 한정 조건이 붙은 용도는 조건부 검토 (기능정의서 13장)
                add(listed, v.getEnumValue(), notEmpty(v.getConditionNote()) ? "CONDITIONAL" : "REVIEWABLE", v.getConditionNote(), "L1_NATIONAL");
            } else if ("enum_conditional".equals(v.getValueType())) {
                List<RuleValue> local = l3ByBase.getOrDefault(RulesLogic.baseUseName(v.getEnumValue()), List.of());
                if (!local.isEmpty()) {
                    for (RuleValue lv : local) {
                        if (!consumed.add(lv.getId())) {
                            continue;
                        }
                        if ("enum_allowed".equals(lv.getValueType())) {
                            add(listed, lv.getEnumValue(), notEmpty(lv.getConditionNote()) ? "CONDITIONAL" : "REVIEWABLE", lv.getConditionNote(),
                                    "L3_LOCAL");
                        } else if ("enum_conditional".equals(lv.getValueType())) {
                            add(listed, lv.getEnumValue(), "CONDITIONAL", lv.getConditionNote(), "L3_LOCAL");
                        }
                    }
                } else if (hasLocal) {
                    add(listed, v.getEnumValue(), "MANUAL_CHECK", "조례의 허용 목록에서 확인되지 않음 — 별도 확인 필요", "L1_NATIONAL");
                } else {
                    add(listed, v.getEnumValue(), "CONDITIONAL",
                            notEmpty(v.getConditionNote()) ? v.getConditionNote() : "도시·군계획조례로 정하는 바에 따라 건축할 수 있는 건축물", "L1_NATIONAL");
                }
            }
        }
        for (RuleValue lv : l3Vals) {
            if (!consumed.contains(lv.getId()) && !"enum_prohibited".equals(lv.getValueType())) {
                // 국가 법령 목록과 맞춰지지 않은 조례 항목 (데이터 대조 필요)
                String status = !l1.isEmpty() ? "MANUAL_CHECK" : ("enum_allowed".equals(lv.getValueType()) ? "REVIEWABLE" : "CONDITIONAL");
                String note = (lv.getConditionNote() == null ? "" : lv.getConditionNote()) + (!l1.isEmpty() ? " — 국가 법령 목록과 대조 필요" : "");
                add(listed, lv.getEnumValue(), status, note, "L3_LOCAL");
            }
        }
        for (String grp : new String[] {"주거", "근린생활", "업무"}) {
            if (listed.stream().noneMatch(u -> grp.equals(u.json().get("use_group").stringValue()))) {
                ObjectNode u = Json.object();
                u.put("use_name", grp + " 용도");
                u.put("use_group", grp);
                u.put("status", "MANUAL_CHECK");
                u.put("condition_note", "검토 가능 용도 목록에서 확인되지 않음 — 별도 확인 필요");
                listed.add(new Listed(u, null));
            }
        }
        ArrayNode uses = it.applied.putArray("uses");
        listed.forEach(u -> uses.add(u.json()));
        List<Rule> l13 = new ArrayList<>(l1);
        l13.addAll(l3);
        if (!l1.isEmpty() && hasLocal) {
            setStatus(it, exceptions(l13));
        } else if (!l1.isEmpty()) {
            it.status = "CONDITIONAL";
            it.reasonCode = "NO_LOCAL_VALUE";
        } else {
            it.status = "MANUAL_CHECK";
            it.reasonCode = "DATA_MISSING";
        }
        it.ruleParts = l13.stream().map(r -> new RulePart(r, true)).toList();
        List<Rule> l31 = new ArrayList<>(l3);
        l31.addAll(l1);
        it.basis = basis(l31);
        // 설명용 엔진 사실: 대상 용도지역 + 묶음별 상태 (원장 인용 가능하도록)
        it.engineFacts.add(EngineFact.subject(zoning, zoning));
        Map<String, String> label = Map.of("REVIEWABLE", "검토 가능", "CONDITIONAL", "조건부 검토", "MANUAL_CHECK", "별도 확인 필요");
        for (String grp : new String[] {"주거", "근린생활", "업무", "기타"}) {
            List<ObjectNode> members = listed.stream().filter(u -> u.layer() != null && grp.equals(u.json().get("use_group").stringValue()))
                    .map(Listed::json).toList();
            if (members.isEmpty()) {
                continue;
            }
            String worst = members.getFirst().get("status").stringValue();
            for (ObjectNode u : members) { // 파이썬 max(): 같은 순위면 먼저 나온 값
                String st = u.get("status").stringValue();
                if (STATUS_RANK.get(st) > STATUS_RANK.get(worst)) {
                    worst = st;
                }
            }
            // 설명용 요약에는 숫자가 없는 기본 이름만 쓴다 (사후 검증에서 근거 없는 숫자로 걸리지 않도록)
            LinkedHashSet<String> bases = new LinkedHashSet<>();
            for (ObjectNode u : members) {
                bases.add(Py.strip(USE_BASE_SPLIT.split(u.get("use_name").stringValue(), -1)[0].split(" 중 ", -1)[0]));
            }
            List<String> bl = new ArrayList<>(bases);
            String names = String.join(", ", bl.subList(0, Math.min(8, bl.size()))) + (bl.size() > 8 ? " 등" : "");
            it.engineFacts.add(EngineFact.condition(grp + " 용도(" + names + ") — " + label.get(worst), List.of(grp)));
        }
        return it;
    }

    private static List<RuleValue> enumValues(List<Rule> rules) {
        List<RuleValue> out = new ArrayList<>();
        for (Rule r : rules) {
            for (RuleValue v : r.getValues()) {
                if (notEmpty(v.getEnumValue()) && v.getValueType().startsWith("enum_")) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static void add(List<Listed> listed, String name, String status, String note, String layer) {
        ObjectNode u = Json.object();
        u.put("use_name", name);
        u.put("use_group", RulesLogic.useGroup(name));
        u.put("status", status);
        u.put("condition_note", note);
        listed.add(new Listed(u, layer));
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static void setStatus(ItemDraft it, boolean hasException) {
        if (hasException) {
            it.status = "CONDITIONAL";
            it.reasonCode = "EXCEPTION_CLAUSE";
        } else {
            it.status = "REVIEWABLE";
            it.reasonCode = null;
        }
    }

    // ── 건폐율 / 용적률 ───────────────────────────────────
    private static RuleValue valueByKey(Rule r, String key) {
        if (r == null) {
            return null;
        }
        return r.getValues().stream().filter(v -> key.equals(v.getValueKey())).findFirst().orElse(null);
    }

    private ItemDraft ratio(String cat, String zoning, boolean multi, ParcelSnapshot parcel, List<Rule> rules, List<String> chain, String refLabel) {
        ItemDraft it = new ItemDraft(cat, "MANUAL_CHECK", "DATA_MISSING");
        it.applied.put("unit", "%");
        if (zoning == null) {
            it.applied.put("criteria_text", multi ? "둘 이상의 용도지역에 걸친 대지 — 별도 확인 필요" : "용도지역 정보 확인 필요");
            return it;
        }
        Rule l1 = pick(rules.stream().filter(r -> "L1_NATIONAL".equals(r.getLayer())).toList(), chain);
        Rule l3 = pick(rules.stream().filter(r -> "L3_LOCAL".equals(r.getLayer())).toList(), chain);
        RuleValue v1 = valueByKey(l1, "ratio");
        RuleValue v3 = valueByKey(l3, "ratio");
        Double lo = v1 != null ? v1.getNumMin() : null;
        Double hi = v1 != null ? v1.getNumMax() : null;
        if (v1 != null) {
            ObjectNode nr = it.applied.putObject("national_range");
            nr.put("min", lo);
            nr.put("max", hi);
        }
        if (v3 != null && v1 != null) {
            Double val = v3.getNumMax() != null ? v3.getNumMax() : v3.getNumExact();
            boolean inRange = val != null && (hi == null || val <= hi) && (lo == null || val >= lo);
            it.ruleParts = List.of(new RulePart(l1, false), new RulePart(l3, true));
            it.basis = basis(List.of(l3, l1));
            if (inRange) {
                setStatus(it, exceptions(List.of(l1, l3)));
                it.applied.put("criteria_text", RulesLogic.fmtNum(val) + "% 이하");
                it.applied.put("value_type", "max");
                it.applied.put("value", val);
            } else {
                it.status = "MANUAL_CHECK";
                it.reasonCode = "OUT_OF_RANGE";
                it.applied.put("criteria_text", "조례 값이 국가 법령 범위를 벗어나 별도 확인 필요 (데이터 검수 대상)");
                log.warn("OUT_OF_RANGE: {} L3={} L1={}~{} (rules {}, {}) — 검수 필요", cat, val, lo, hi, l3.getId(), l1.getId());
            }
        } else if (v1 != null) {
            it.ruleParts = List.of(new RulePart(l1, true));
            it.basis = basis(List.of(l1));
            it.status = "CONDITIONAL";
            it.reasonCode = "NO_LOCAL_VALUE";
            String rng = (lo != null ? RulesLogic.fmtNum(lo) + "% 이상 " : "") + RulesLogic.fmtNum(hi) + "% 이하";
            it.applied.put("criteria_text", "국가 법령 범위 " + rng + " (지역 조례 값 미확인)");
            it.applied.put("value_type", lo != null ? "range" : "max");
        } else if (v3 != null) {
            it.applied.put("criteria_text", "상위 법령 기준이 검수 승인되지 않아 조례 값을 확정할 수 없음");
            it.basis = basis(List.of(l3));
        }
        JsonNode value = it.applied.get("value");
        if (!value.isNull() && parcel.landAreaM2() != null && parcel.landAreaM2() != 0.0) {
            double ratio = value.doubleValue() / 100;
            ObjectNode ref = it.applied.putObject("reference_value");
            ref.put("label", refLabel);
            ref.put("value", Py.round(parcel.landAreaM2() * ratio, 2));
            ref.put("unit", "㎡");
            ref.put("formula", RulesLogic.fmtNum(parcel.landAreaM2()) + " × " + fixed2(ratio));
        }
        return it;
    }

    /** 파이썬 f"{x:.2f}" (이진 값 그대로 은행원 반올림). */
    static String fixed2(double x) {
        return new BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    // ── 주차 ──────────────────────────────────────────────
    private ItemDraft parkingRequirement(List<Rule> rules, List<Rule> needsInput, List<String> chain) {
        ItemDraft it = new ItemDraft("PARKING_REQUIREMENT", "MANUAL_CHECK", "DATA_MISSING");
        it.applied.put("unit", "대");
        Object use = inputs.get("building_use");
        List<Rule> candidates = new ArrayList<>(rules);
        candidates.addAll(needsInput);
        if (candidates.isEmpty()) {
            it.applied.put("criteria_text", "주차 기준 확인 필요");
            return it;
        }
        if (use == null || "".equals(use) || rules.isEmpty()) {
            it.status = "CONDITIONAL";
            it.reasonCode = "NEEDS_USE_INPUT";
            it.applied.put("criteria_text", "건축물 용도에 따라 산정");
            ArrayNode table = Json.array();
            List<Rule> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparing(Rule::getRuleKey, com.autoparkingplan.common.PyJson::compareCodePoints));
            for (Rule r : sorted.subList(0, Math.min(20, sorted.size()))) {
                for (RuleValue v : r.getValues()) {
                    String label = notEmpty(v.getConditionNote()) ? v.getConditionNote() : r.getTitle();
                    ObjectNode row = table.addObject();
                    row.put("label", Py.slice(label, 0, 80));
                    row.put("value", notEmpty(v.getFormula()) ? v.getFormula() : RulesLogic.fmtNum(firstTruthy(v.getNumExact(), v.getNumMax(), v.getNumMin())));
                    row.put("unit", v.getUnit());
                }
            }
            if (table.isEmpty()) {
                it.applied.putNull("table");
            } else {
                it.applied.set("table", table);
            }
            List<Rule> local = candidates.stream().filter(r -> "L3_LOCAL".equals(r.getLayer())).toList();
            List<Rule> src = local.isEmpty() ? candidates : local;
            List<Rule> parts = src.subList(0, Math.min(6, src.size()));
            it.ruleParts = parts.stream().map(r -> new RulePart(r, true)).toList();
            it.basis = basis(parts);
            return it;
        }
        List<Rule> local = rules.stream().filter(r -> "L3_LOCAL".equals(r.getLayer())).toList();
        Rule rule = pick(local.isEmpty() ? rules : local, chain);
        RuleValue v = rule.getValues().stream().filter(x -> notEmpty(x.getFormula()) || x.getNumExact() != null).findFirst().orElse(null);
        it.ruleParts = List.of(new RulePart(rule, true));
        it.basis = basis(List.of(rule));
        if (v != null && notEmpty(v.getFormula())) {
            try {
                Map<String, Double> vars = new HashMap<>();
                vars.put("gross_floor_area", num(inputs.get("gross_floor_area")));
                Object fa = inputs.get("facility_area");
                vars.put("facility_area", num(truthy(fa) ? fa : inputs.get("gross_floor_area")));
                vars.put("households", num(inputs.get("households")));
                vars.put("site_area", num(inputs.get("site_area")));
                double n = RulesLogic.evalFormula(v.getFormula(), vars);
                it.applied.put("value", n);
                it.applied.put("value_type", "formula");
                it.applied.put("criteria_text", (notEmpty(v.getConditionNote()) ? v.getConditionNote() : rule.getTitle()) + ": " + v.getFormula());
                setStatus(it, exceptions(List.of(rule)));
            } catch (IllegalArgumentException e) {
                it.status = "CONDITIONAL";
                it.reasonCode = "NEEDS_USE_INPUT";
                it.applied.put("criteria_text", "연면적·세대수 등 계산 입력 필요");
            }
        }
        return it;
    }

    private static boolean truthy(Object o) {
        return o != null && !(o instanceof Number n && n.doubleValue() == 0.0) && !"".equals(o);
    }

    private static Double num(Object o) {
        if (o == null) {
            return null;
        }
        return RulesLogic.pyFloat(o);
    }

    /** 파이썬 {@code a or b or c} (0·None 은 거짓). */
    private static Double firstTruthy(Double... xs) {
        for (int i = 0; i < xs.length; i++) {
            if (xs[i] != null && (xs[i] != 0.0 || i == xs.length - 1)) {
                return xs[i];
            }
        }
        return xs[xs.length - 1];
    }

    private ItemDraft stallSize(List<Rule> rules0) {
        ItemDraft it = new ItemDraft("PARKING_STALL_SIZE", "MANUAL_CHECK", "DATA_MISSING");
        it.applied.put("unit", "m");
        List<Rule> l1 = rules0.stream().filter(r -> "L1_NATIONAL".equals(r.getLayer())).toList();
        List<Rule> rules = l1.isEmpty() ? rules0 : l1;
        if (rules.isEmpty()) {
            it.applied.put("criteria_text", "주차구획 기준 확인 필요");
            return it;
        }
        ArrayNode table = Json.array();
        Map<String, Double> gen = new HashMap<>();
        for (Rule r : sortedByKey(rules)) {
            for (RuleValue v : sortedValues(r)) {
                String[] parts = v.getValueKey().split("\\.", -1);
                String label;
                if (parts.length == 3 && (parts[2].equals("width") || parts[2].equals("length"))) {
                    label = FORM_LABEL.getOrDefault(parts[0], parts[0]) + " · " + STALL_LABEL.getOrDefault(parts[1], parts[1]) + " "
                            + (parts[2].equals("width") ? "너비" : "길이");
                    if (parts[0].equals("non_parallel") && parts[1].equals("general")) {
                        gen.put(parts[2], v.getNumMin());
                    }
                } else {
                    label = v.getValueKey();
                }
                ObjectNode row = table.addObject();
                row.put("label", label);
                row.put("value", v.getNumMin() != null ? v.getNumMin() : orNum(v.getNumExact(), v.getNumMax()));
                row.put("unit", v.getNumMin() != null ? "m 이상" : v.getUnit());
            }
        }
        it.applied.set("table", table);
        Double w = gen.get("width");
        Double l = gen.get("length");
        if (w != null && w != 0.0 && l != null && l != 0.0) {
            it.applied.put("criteria_text", "일반형 " + RulesLogic.fmtNum(w) + "m × " + RulesLogic.fmtNum(l) + "m 이상");
        }
        setStatus(it, exceptions(rules));
        it.ruleParts = rules.stream().map(r -> new RulePart(r, true)).toList();
        it.basis = basis(rules);
        return it;
    }

    /** 파이썬 {@code a or b}: a 가 None·0 이면 b. */
    private static Double orNum(Double a, Double b) {
        return a != null && a != 0.0 ? a : b;
    }

    private static List<Rule> sortedByKey(List<Rule> rules) {
        List<Rule> s = new ArrayList<>(rules);
        s.sort(Comparator.comparing(Rule::getRuleKey, com.autoparkingplan.common.PyJson::compareCodePoints));
        return s;
    }

    private static List<RuleValue> sortedValues(Rule r) {
        List<RuleValue> s = new ArrayList<>(r.getValues());
        s.sort(Comparator.comparing(RuleValue::getValueKey, com.autoparkingplan.common.PyJson::compareCodePoints));
        return s;
    }

    private ItemDraft aisleWidth(List<Rule> rules0) {
        ItemDraft it = new ItemDraft("DRIVE_AISLE_WIDTH", "MANUAL_CHECK", "DATA_MISSING");
        it.applied.put("unit", "m");
        List<Rule> l1 = rules0.stream().filter(r -> "L1_NATIONAL".equals(r.getLayer())).toList();
        List<Rule> rules = l1.isEmpty() ? rules0 : l1;
        if (rules.isEmpty()) {
            it.applied.put("criteria_text", "차로 폭 기준 확인 필요");
            return it;
        }
        ArrayNode table = Json.array();
        Double perp = null;
        for (Rule r : sortedByKey(rules)) {
            for (RuleValue v : sortedValues(r)) {
                String[] parts = v.getValueKey().split("\\.", -1);
                String label;
                if (parts.length == 2 && AISLE_LABEL.containsKey(parts[0])) {
                    label = AISLE_LABEL.get(parts[0]) + " 차로 (" + GATE_LABEL.getOrDefault(parts[1], parts[1]) + ")";
                    if (parts[0].equals("perpendicular_90") && parts[1].equals("one_gate")) {
                        perp = v.getNumMin();
                    }
                } else if (v.getValueKey().startsWith("gate.")) {
                    label = "출입구 너비" + (notEmpty(v.getConditionNote()) ? " (" + v.getConditionNote() + ")" : "");
                } else {
                    label = v.getValueKey();
                }
                ObjectNode row = table.addObject();
                row.put("label", Py.slice(label, 0, 80));
                row.put("value", v.getNumMin() != null ? v.getNumMin() : orNum(v.getNumExact(), v.getNumMax()));
                row.put("unit", v.getNumMin() != null ? "m 이상" : v.getUnit());
            }
        }
        it.applied.set("table", table);
        it.applied.put("criteria_text", (perp != null && perp != 0.0 ? "직각주차 차로 " + RulesLogic.fmtNum(perp) + "m 이상 (출입구 1개 기준) — " : "")
                + "기준정보 제공, 실제 충족 여부는 배치 단계에서 검토");
        setStatus(it, exceptions(rules));
        it.ruleParts = rules.stream().map(r -> new RulePart(r, true)).toList();
        it.basis = basis(rules);
        return it;
    }

    // ── L4 중첩 · 별도 확인 ────────────────────────────────
    private List<ObjectNode> overlays(ParcelSnapshot parcel) {
        List<ObjectNode> found = new ArrayList<>();
        Geometry geom = null;
        if (parcel.geometry() != null && !parcel.geometry().isNull()) {
            try {
                geom = GeoJson.toGeometry(parcel.geometry());
            } catch (RuntimeException e) { // 파이썬판처럼 중첩 검사를 건너뛴다 — 다만 조용히 넘기지 않고 남긴다 (PNU 목록 판정은 계속)
                log.warn("필지 {} 경계를 해석할 수 없어 L4 구역 경계 중첩 검사를 건너뜁니다 ({})", parcel.pnu(), e.getClass().getSimpleName());
                geom = null;
            }
        }
        for (ZoneOverlay z : em.createQuery("select z from ZoneOverlay z order by z.id", ZoneOverlay.class).getResultList()) {
            if ((z.getEffectiveFrom() != null && z.getEffectiveFrom().isAfter(asOf)) || (z.getEffectiveTo() != null && z.getEffectiveTo().isBefore(asOf))) {
                continue;
            }
            boolean hit = false;
            if (z.getPnuList() != null) {
                for (JsonNode p : z.getPnuList().values()) {
                    hit |= p.isString() && parcel.pnu().equals(p.stringValue());
                }
            }
            if (!hit && geom != null && z.getGeom() != null && !z.getGeom().isNull()) {
                try {
                    Geometry zg = GeoJson.toGeometry(z.getGeom());
                    hit = geom.intersects(zg) && geom.intersection(zg).getArea() > 0;
                } catch (RuntimeException e) {
                    log.warn("L4 구역 {} 중첩 계산 실패 ({}) — 이 구역은 겹치지 않는 것으로 둔다", z.getId(), e.getClass().getSimpleName());
                    hit = false;
                }
            }
            if (hit) {
                ObjectNode o = Json.object();
                o.put("zone_type", z.getZoneType());
                o.put("name", z.getName());
                o.put("document_id", z.getDocumentId().toString());
                o.put("source", "zone_overlay");
                found.add(o);
            }
        }
        for (Designation d : parcel.designations() == null ? List.<Designation>of() : parcel.designations()) {
            if (Set.of("zone", "district", "other").contains(d.kind()) && RulesLogic.L4_ESCALATE_RE.matcher(d.name()).find()
                    && !"adjacent".equals(d.conflict())) {
                ObjectNode o = Json.object();
                o.put("zone_type", "designation");
                o.put("name", d.name());
                o.putNull("document_id");
                o.put("source", "parcel_designation");
                found.add(o);
            }
        }
        return found;
    }

    private List<ObjectNode> manualChecks(ParcelSnapshot parcel, List<ObjectNode> overlay) {
        String roadWhy = "도로 폭과 진출입 위치는 현장·배치 조건에 따라 달라집니다.";
        if (notEmpty(parcel.roadSide())) {
            roadWhy += " (토지특성 자료의 도로접면 표기: " + parcel.roadSide() + ")";
        }
        List<String> districts = parcel.designations() == null ? List.of()
                : parcel.designations().stream().filter(d -> "district".equals(d.kind()) || "zone".equals(d.kind())).map(Designation::name).toList();
        ArrayNode related = Json.array();
        for (ObjectNode o : overlay) {
            if (!o.get("document_id").isNull()) {
                SourceDocument doc = em.find(SourceDocument.class, UUID.fromString(o.get("document_id").stringValue()));
                DocumentVersion ver = null;
                if (doc != null) {
                    List<DocumentVersion> vs = new ArrayList<>(doc.getVersions());
                    // sorted(key=effective_from or date.min, reverse=True) — 같은 값이면 원래 순서 유지
                    vs.sort(Comparator.comparing((DocumentVersion v) -> v.getEffectiveFrom() != null ? v.getEffectiveFrom() : LocalDate.MIN).reversed());
                    ver = vs.isEmpty() ? null : vs.getFirst();
                }
                if (ver != null) {
                    List<Provision> ps = em.createQuery("select p from Provision p where p.versionId = :v order by p.sortOrder", Provision.class)
                            .setParameter("v", ver.getId()).setMaxResults(1).getResultList();
                    if (!ps.isEmpty()) {
                        related.add(Basis.nodeFor(em, ps.getFirst(), null, null).json());
                    } else {
                        ObjectNode n = related.addObject();
                        n.put("layer", doc.getLayer());
                        n.put("document_title", doc.getOfficialTitle());
                        n.putNull("article_label");
                        n.put("effective_from", ver.getEffectiveFrom() != null ? ver.getEffectiveFrom().toString() : null);
                        n.putNull("quote");
                        n.put("url", Sanitize.safeUrl(ver.getSourceUrl()));
                        n.putNull("rule_id");
                    }
                }
            } else {
                // 토지이용계획에서만 확인된 구역: 고시 원문을 아직 등록하지 않았으므로 토지이음 열람 경로를 안내한다
                ObjectNode n = related.addObject();
                n.put("layer", "L4_SPECIAL_ZONE");
                n.put("document_title", o.get("name").stringValue() + " (토지이음에서 결정 고시·도면 확인)");
                n.putNull("article_label");
                n.putNull("effective_from");
                n.putNull("quote");
                n.put("url", "https://www.eum.go.kr/web/am/amMain.jsp");
                n.putNull("rule_id");
            }
        }
        String trig = null;
        if (!overlay.isEmpty()) {
            TreeSet<String> names = new TreeSet<>(com.autoparkingplan.common.PyJson::compareCodePoints);
            overlay.forEach(o -> names.add(o.get("name").stringValue()));
            trig = String.join(", ", names) + " 중첩 확인";
        } else if (!districts.isEmpty()) {
            trig = String.join(", ", districts) + " 지정 확인";
        }
        List<ObjectNode> out = new ArrayList<>();
        out.add(check("ROAD_ACCESS", "도로 및 진출입 조건", roadWhy, List.of("도로 폭", "접도 조건", "차량 진출입 가능 위치"), Json.array(), null));
        out.add(check("SETBACK", "건축선 및 이격거리", "건축선과 이격거리는 도로·인접 대지 조건과 별도 지정 여부에 따라 달라집니다.",
                List.of("도로경계", "인접대지경계", "별도 건축선 지정 여부"), Json.array(), null));
        out.add(check("HEIGHT_SUNLIGHT", "높이 및 일조 조건", "일조·높이 기준은 용도지역, 인접 대지 방향과 건축 계획에 따라 달라집니다.",
                List.of("일조권", "높이 제한", "사선 제한 등"), Json.array(), null));
        out.add(check("SPECIAL_ZONE", "추가 지역 규제", "지구단위계획 등 개별 계획·고시는 자동 반영하지 않습니다.",
                List.of("지구단위계획", "특별계획구역", "경관지구", "특화구역", "기타 개별 인허가 조건"), related, trig));
        return out;
    }

    private static ObjectNode check(String cat, String title, String why, List<String> what, ArrayNode related, String trig) {
        ObjectNode o = Json.object();
        o.put("category", cat);
        o.put("title", title);
        o.put("why", why);
        ArrayNode w = o.putArray("what_to_check");
        what.forEach(w::add);
        o.set("related_basis", related);
        o.put("triggered_by", trig);
        return o;
    }

    static List<ObjectNode> dedupe(List<ObjectNode> notices) {
        Set<String> seen = new HashSet<>();
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode n : notices) {
            String k = n.get("code").stringValue() + "\u0000" + n.get("message").stringValue();
            if (seen.add(k)) {
                out.add(n);
            }
        }
        return out;
    }
}
