package com.autoparkingplan.ai.verification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.ai.pycompat.PyRe;
import com.autoparkingplan.ai.pycompat.PyRepr;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.ai.schema.SchemaError;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * 사후 검증기 (Post-Verifier) — 화면에 나가기 직전, AI 쉬운 설명의 환각을 최종 차단한다 ({@code app/verification/post_verifier.py}).
 *
 * <p>여기서 '환각'은 세 가지를 모두 뜻한다.
 * <ul>
 *   <li>A. 날조 : 원문·원장에 없는 숫자, 기간, 금액, 법령명, 조문, 용도지역, 사실</li>
 *   <li>B. 세부 왜곡 : 값·단위·비교어·서술 성격이 원문과 다름
 *       (1년→8개월, 60% 이하→60% 미만, 2.5m→250cm, 설치하여야→설치할 수 있다)</li>
 *   <li>C. 누락 : 원장에서 '반드시 설명'으로 지정된 사실(조건, 기간, 예외, 의무 등)이 빠지거나
 *       언급은 했지만 값·비교어·핵심어가 빠짐</li>
 * </ul>
 *
 * <p>검사 순서 (결정적 검사 1~7은 AI를 쓰지 않으며, 의미 검증 8은 실패만 추가할 수 있고 통과시킬 수는 없다)
 * <ol>
 *   <li>형식 SCHEMA, UNKNOWN_FACT_ID, UNGROUNDED_SENTENCE</li>
 *   <li>수치·기간 FABRICATED_VALUE, DETAIL_MISMATCH, UNIT_MISMATCH, COMPARATOR_MISMATCH, COMPARATOR_MISSING,
 *       VALUE_WITHOUT_UNIT, UNCITED_FACT_USE</li>
 *   <li>명칭 REFERENCE_FABRICATED, LAW_NAME_FABRICATED, SUBJECT_MISMATCH</li>
 *   <li>서술 성격 POLARITY_MISMATCH (허용/금지/의무/면제 뒤집힘)</li>
 *   <li>누락 OMISSION, OMISSION_DETAIL</li>
 *   <li>상태 과장 OVERSTATEMENT, CAUTION_MISSING</li>
 *   <li>원장 완전성 LEDGER_INCOMPLETE (원장 자체가 원문을 빠뜨렸는지 — {@link FactLedger#checkCompleteness})</li>
 *   <li>의미 검증 SEMANTIC_CONTRADICTED, SEMANTIC_NOT_IN_SOURCE, SEMANTIC_COVERAGE_PARTIAL,
 *       JUDGE_UNAVAILABLE (판정 실패·형식 오류·미판정 문장 → 통과 아님) (별도 모델 judge 가 원장 인용문만 보고 문장별 함의/누락 판정)</li>
 * </ol>
 *
 * <p>{@link #finalize} 가 전체 흐름을 묶는다: 생성 → 검증 → (실패 시 오류 피드백 재생성, 최대 N회)
 * → 그래도 실패하면 원장 인용문으로 만든 템플릿 설명(이것도 검증 통과해야 함) → 화면.
 *
 * <p>원장·설명은 파이썬 dict 를 그대로 옮긴 {@link JsonNode} 다. 보고서는 파이썬판과 같은 키 순서·같은 문구의
 * {@link ObjectNode} 이며 {@code checked_at} 만 시각에 따라 다르다.
 */
public final class PostVerifier {
    private PostVerifier() {
    }

    public static final Pattern CAUTION_WORDS = PyRe.compile("확인|달라질|조건|검토가?\\s*필요");
    public static final Pattern BANNED = PyRe.compile(
            "허가.{0,6}(?:받을\\s*수\\s*있|가능|됩니다|나요|난다)|무조건|반드시\\s*가능|문제\\s*없|확정(?:입니다|됩니다|돼요)");
    public static final Pattern ASSERTIVE = PyRe.compile("(?:가능합니다|가능해요|할\\s*수\\s*있습니다|지을\\s*수\\s*있습니다)");

    /**
     * 단위 환산은 '허용'하지 않는다. 환산하면 같은 값이 되는 경우를 찾아 UNIT_MISMATCH 로 정확히 알려주기 위한 표.
     * 키 = (설명의 dim, 원장의 dim).
     */
    public static final Map<List<String>, Double> CONVERSIONS = Map.of(
            List.of("length_cm", "length"), 0.01, List.of("area_pyeong", "area"), 3.305785);

    public static final Map<String, String> KIND_LABEL = Map.of(
            "PERIOD", "기간", "QUANTITY", "기준", "DATE", "날짜", "EXCEPTION", "예외",
            "MODALITY", "의무·제한", "SUBJECT", "적용 대상", "REFERENCE", "근거");

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");
    private static final Pattern WS_RUN = PyRe.compile("\\s+");

    /**
     * 의미 검증 모델 (작성 모델과 분리). 원장 사실(fact_id·kind·quote)과 설명 문장을 받아 judge_verdict@1.0 객체를 돌려준다.
     * 예외를 던지면 JUDGE_UNAVAILABLE (통과 아님).
     */
    @FunctionalInterface
    public interface Judge {
        JsonNode judge(List<ObjectNode> facts, List<JsonNode> sentences) throws Exception;
    }

    /**
     * {@link #finalize} 결과.
     *
     * @param explanation   화면에 나갈 설명 (basis_only 면 null)
     * @param displaySource "ai_verified" | "template_fallback" | "basis_only"
     * @param attempts      시도별 검증 보고서
     */
    public record FinalizeResult(JsonNode explanation, String displaySource, List<ObjectNode> attempts) {
    }

    static String family(String dim) {
        return dim.startsWith("period") ? "period" : dim;
    }

    static String norm(String s) {
        return PyRe.sub(WS_RUN, "", s);
    }

    /** key_terms: ["연면적", ["사용승인", "준공"]] → 각 항목(대안 목록이면 그중 하나)이 모두 문장에 있어야 함. */
    static boolean termsOk(String text, JsonNode keyTerms) {
        String t = norm(text);
        if (!PyObj.truthy(keyTerms)) {
            return true;
        }
        for (JsonNode term : iterate(keyTerms)) {
            List<JsonNode> alts = term.isArray() ? listOf(term) : List.of(term);
            boolean any = false;
            for (JsonNode a : alts) {
                if (t.contains(norm(PyObj.requireStr(a)))) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** 파이썬 {@code _q_matches(q, canon)}: 같은 차원·같은 값(날짜는 같은 날짜)인가. */
    public static boolean qMatches(KoQuantity.Q q, JsonNode canon) {
        if (!q.kind().equals("DATE")) {
            return PyObj.truthy(canon) && PyObj.eq(PyObj.item(canon, "dim"), q.dim())
                    && q.floatValue() == PyObj.toFloat(PyObj.item(canon, "value"));
        }
        return PyObj.truthy(canon) && PyObj.eq(PyObj.item(canon, "dim"), "date")
                && PyObj.eq(PyObj.item(canon, "value"), (String) q.value());
    }

    static boolean convertedMatch(KoQuantity.Q q, List<JsonNode> numericFacts) {
        for (JsonNode f : numericFacts) {
            JsonNode canon = PyObj.item(f, "canonical");
            JsonNode fdim = PyObj.item(canon, "dim");
            if (fdim.isArray() || fdim.isObject()) {
                throw new IllegalArgumentException("TypeError: unhashable type");
            }
            Double k = fdim.isString() ? CONVERSIONS.get(List.of(q.dim(), fdim.stringValue())) : null;
            if (k != null && k != 0.0) {
                double fv = PyObj.toFloat(PyObj.item(canon, "value"));
                if (Math.abs(q.floatValue() * k - fv) <= 0.01 * fv) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 파이썬 {@code _schema_errors(obj, name)}: [{"code": "SCHEMA", "message": "path: message"}] (iter_errors 순서). */
    static List<ObjectNode> schemaErrors(JsonNode obj, String name) {
        List<ObjectNode> out = new ArrayList<>();
        for (SchemaError e : JsonSchemas.errors(obj == null ? NullNode.getInstance() : obj, name)) {
            ObjectNode o = Json.object();
            o.put("code", "SCHEMA");
            o.put("message", e.describe());
            out.add(o);
        }
        return out;
    }

    public static ObjectNode verify(JsonNode explanation, JsonNode ledger) {
        return verify(explanation, ledger, null, null);
    }

    public static ObjectNode verify(JsonNode explanation, JsonNode ledger, Map<String, String> provisions) {
        return verify(explanation, ledger, provisions, null);
    }

    /** 설명 1건을 원장과 대조해 검증 보고서(verification_report@1.0)를 돌려준다. */
    public static ObjectNode verify(JsonNode explanation, JsonNode ledger, Map<String, String> provisions, Judge judge) {
        return verify(explanation, ledger, provisions, judge, Clock.systemUTC());
    }

    /** {@link #verify(JsonNode, JsonNode, Map, Judge)} — checked_at 시계를 지정. */
    public static ObjectNode verify(JsonNode explanation, JsonNode ledger, Map<String, String> provisions, Judge judge, Clock clock) {
        List<ObjectNode> errors = new ArrayList<>(schemaErrors(explanation, "explanation.schema.json"));
        if (!errors.isEmpty()) {
            return report("FAIL", errors, List.of(), null, explanation, ledger, clock);
        }
        Errors err = new Errors(errors);

        Map<Object, JsonNode> facts = new LinkedHashMap<>();
        for (JsonNode f : iterate(PyObj.item(ledger, "facts"))) {
            facts.put(PyObj.hashKey(PyObj.item(f, "fact_id")), f);
        }
        List<JsonNode> numericFacts = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        Set<String> refNames = new LinkedHashSet<>();
        Set<String> subjects = new LinkedHashSet<>();
        for (JsonNode f : facts.values()) {
            if (PyObj.truthy(PyObj.get(f, "canonical"))) {
                numericFacts.add(f);
            }
        }
        for (JsonNode f : facts.values()) {
            if (PyObj.eq(PyObj.item(f, "kind"), "REFERENCE")) {
                refs.add(KoQuantity.normalizeRef(PyObj.requireStr(PyObj.item(f, "quote"))));
            }
        }
        for (JsonNode f : facts.values()) {
            if (PyObj.eq(PyObj.item(f, "kind"), "REFERENCE")) {
                JsonNode name = PyObj.get(f, "name");
                refNames.add(KoQuantity.normalizeRef(name == null ? "" : PyObj.requireStr(name)));
            }
        }
        for (JsonNode f : facts.values()) {
            if (PyObj.eq(PyObj.item(f, "kind"), "SUBJECT")) {
                subjects.add(KoQuantity.normalizeRef(PyObj.requireStr(PyObj.item(f, "name"))));
            }
        }
        JsonNode statusFact = null;
        for (JsonNode f : facts.values()) {
            if (PyObj.eq(PyObj.item(f, "kind"), "STATUS")) {
                statusFact = f;
                break;
            }
        }
        List<JsonNode> sentences = listOf(explanation.get("sentences"));

        // 7. 원장 완전성 (원장이 원문을 빠뜨리지 않았는지)
        if (provisions != null) {
            for (ObjectNode gap : FactLedger.checkCompleteness(provisions, ledger)) {
                err.add("LEDGER_INCOMPLETE", "원장 누락: " + PyRepr.repr(gap.get("quote")) + " (" + PyRepr.str(gap.get("kind")) + ")",
                        null, null, null, gap.get("quote"));
            }
        }

        Map<String, List<KoQuantity.Q>> parsedBySid = new HashMap<>();
        for (JsonNode s : sentences) {
            JsonNode sid = s.get("sid");
            String text = s.get("text").stringValue();
            List<JsonNode> citedIds = listOf(s.get("fact_ids"));
            // 1. 근거 연결
            for (JsonNode i : citedIds) {
                if (!facts.containsKey(PyObj.hashKey(i))) {
                    err.add("UNKNOWN_FACT_ID", "원장에 없는 fact_id 인용", sid, i, null, null);
                }
            }
            List<JsonNode> cited = new ArrayList<>();
            for (JsonNode i : citedIds) {
                JsonNode f = facts.get(PyObj.hashKey(i));
                if (f != null) {
                    cited.add(f);
                }
            }
            if (cited.isEmpty()) {
                err.add("UNGROUNDED_SENTENCE", "근거 사실을 하나도 인용하지 않은 문장", sid, null, null, s.get("text"));
            }

            // 3. 명칭: 조문·법령명·용도지역
            List<KoQuantity.Q> qs = KoQuantity.parse(text);
            parsedBySid.put(sid.stringValue(), qs);
            for (KoQuantity.Q q : qs) {
                if (q.kind().equals("REFERENCE") && !refs.contains((String) q.value())) {
                    err.add("REFERENCE_FABRICATED", "원장에 없는 조문·법령 참조", sid, null, null, StringNode.valueOf(q.raw()));
                }
            }
            for (String name : KoQuantity.lawNames(text)) {
                if (!refNames.contains(name) && refs.stream().noneMatch(r -> r.contains(name))) {
                    err.add("LAW_NAME_FABRICATED", "원장에 없는 법령명", sid, null, null, StringNode.valueOf(name));
                }
            }
            for (String z : KoQuantity.zoneNames(text)) {
                if (!subjects.contains(z)) {
                    ArrayNode expected = Json.array();
                    subjects.stream().sorted(PyJson::compareCodePoints).forEach(expected::add);
                    err.add("SUBJECT_MISMATCH", "원장과 다른 용도지역·구역 명칭", sid, null, expected, StringNode.valueOf(z));
                }
            }

            // 2. 수치·기간·날짜
            List<JsonNode> citedCanon = new ArrayList<>();
            for (JsonNode f : cited) {
                if (PyObj.truthy(PyObj.get(f, "canonical"))) {
                    citedCanon.add(f);
                }
            }
            for (KoQuantity.Q q : qs) {
                if (q.kind().equals("REFERENCE")) {
                    continue;
                }
                StringNode found = StringNode.valueOf(q.raw());
                if (q.kind().equals("BARE")) {
                    boolean same = false;
                    for (JsonNode f : citedCanon) {
                        JsonNode canon = PyObj.item(f, "canonical");
                        if (!PyObj.eq(PyObj.item(canon, "dim"), "date")
                                && q.floatValue() == PyObj.toFloat(PyObj.item(canon, "value"))) {
                            same = true;
                            break;
                        }
                    }
                    if (same) {
                        err.add("VALUE_WITHOUT_UNIT", "단위·기간 표시 없이 숫자만 씀", sid, null, null, found);
                    } else {
                        err.add("FABRICATED_VALUE", "원장에 없는 숫자", sid, null, null, found);
                    }
                    continue;
                }
                List<JsonNode> exact = new ArrayList<>();
                for (JsonNode f : citedCanon) {
                    if (qMatches(q, PyObj.item(f, "canonical"))) {
                        exact.add(f);
                    }
                }
                if (!exact.isEmpty()) {
                    Map<Object, JsonNode> comps = new LinkedHashMap<>();
                    for (JsonNode f : exact) {
                        JsonNode c = PyObj.get(PyObj.item(f, "canonical"), "comparator");
                        comps.putIfAbsent(PyObj.hashKey(c), c == null ? NullNode.getInstance() : c);
                    }
                    Object qKey = q.comparator() == null ? PyObj.hashKey(null) : PyObj.hashKey(StringNode.valueOf(q.comparator()));
                    if (comps.containsKey(qKey)) {
                        continue;
                    }
                    JsonNode f0 = exact.get(0);
                    if (q.comparator() == null) {
                        err.add("COMPARATOR_MISSING", "원문의 이하/미만/이상/초과/이내 표현이 빠짐", sid, PyObj.item(f0, "fact_id"),
                                PyObj.item(PyObj.item(f0, "canonical"), "comparator"), found);
                    } else {
                        err.add("COMPARATOR_MISMATCH", "비교어가 원문과 다름", sid, PyObj.item(f0, "fact_id"),
                                sortedComparators(comps.values()), found);
                    }
                    continue;
                }
                JsonNode other = null;
                for (JsonNode f : numericFacts) {
                    if (qMatches(q, PyObj.item(f, "canonical"))) {
                        other = f;
                        break;
                    }
                }
                if (other != null) {
                    err.add("UNCITED_FACT_USE", "다른 사실의 값을 인용 표시 없이 사용", sid, PyObj.item(other, "fact_id"), null, found);
                    continue;
                }
                List<JsonNode> sameFamily = new ArrayList<>();
                for (JsonNode f : citedCanon) {
                    if (family(PyObj.requireStr(PyObj.item(PyObj.item(f, "canonical"), "dim"))).equals(family(q.dim()))) {
                        sameFamily.add(f);
                    }
                }
                if (!sameFamily.isEmpty()) {
                    ArrayNode expected = Json.array();
                    for (JsonNode f : sameFamily) {
                        expected.add(PyObj.item(f, "quote"));
                    }
                    err.add("DETAIL_MISMATCH", "값이 원문과 다름", sid, PyObj.item(sameFamily.get(0), "fact_id"), expected, found);
                } else if (!q.kind().equals("DATE") && (convertedMatch(q, numericFacts) || anySameValue(q, numericFacts))) {
                    err.add("UNIT_MISMATCH", "원문에 없는 단위로 표기(환산 금지)", sid, null, null, found);
                } else {
                    err.add("FABRICATED_VALUE", "원장에 없는 값", sid, null, null, found);
                }
            }

            // 4. 서술 성격
            String smod = KoQuantity.modality(text);
            for (JsonNode f : cited) {
                JsonNode fmod = PyObj.get(f, "modality");
                if (PyObj.truthy(fmod) && smod != null && !smod.isEmpty() && !PyObj.eq(fmod, smod)) {
                    err.add("POLARITY_MISMATCH", "허용/금지/의무/면제 성격이 원문과 다름", sid, PyObj.item(f, "fact_id"), fmod,
                            StringNode.valueOf(smod));
                }
            }

            // 6. 단정 표현
            Matcher banned = BANNED.matcher(text);
            if (banned.find()) {
                err.add("OVERSTATEMENT", "인허가를 단정하는 표현", sid, null, null, StringNode.valueOf(banned.group()));
            }
        }

        // 5. 누락 (must_mention)
        List<ObjectNode> coverage = new ArrayList<>();
        for (JsonNode f : facts.values()) {
            if (!PyObj.truthy(PyObj.get(f, "must_mention")) || PyObj.eq(PyObj.item(f, "kind"), "STATUS")) {
                continue;
            }
            JsonNode factId = PyObj.item(f, "fact_id");
            List<JsonNode> citing = new ArrayList<>();
            for (JsonNode s : sentences) {
                if (containsValue(s.get("fact_ids"), factId)) {
                    citing.add(s);
                }
            }
            if (citing.isEmpty()) {
                err.add("OMISSION", "반드시 설명해야 할 원문 사실이 빠짐", null, factId, PyObj.item(f, "quote"), null);
                coverage.add(coverageEntry(factId, "MISSING"));
                continue;
            }
            boolean ok = false;
            for (JsonNode s : citing) {
                String stext = s.get("text").stringValue();
                List<Boolean> conds = new ArrayList<>();
                conds.add(termsOk(stext, PyObj.get(f, "key_terms")));
                JsonNode canon = PyObj.get(f, "canonical");
                if (PyObj.truthy(canon)) {
                    boolean any = false;
                    for (KoQuantity.Q q : parsedBySid.get(s.get("sid").stringValue())) {
                        if (qMatches(q, canon) && PyObj.eq(PyObj.get(canon, "comparator"), q.comparator())) {
                            any = true;
                            break;
                        }
                    }
                    conds.add(any);
                }
                JsonNode fmod = PyObj.get(f, "modality");
                if (PyObj.truthy(fmod)) {
                    conds.add(PyObj.eq(fmod, KoQuantity.modality(stext)));
                }
                if (PyObj.eq(PyObj.item(f, "kind"), "SUBJECT")) {
                    conds.add(KoQuantity.zoneNames(stext).contains(KoQuantity.normalizeRef(PyObj.requireStr(PyObj.item(f, "name")))));
                }
                if (!conds.contains(false)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                err.add("OMISSION_DETAIL", "언급은 했지만 값·비교어·핵심어·성격 중 일부가 빠짐", null, factId, PyObj.item(f, "quote"), null);
            }
            coverage.add(coverageEntry(factId, ok ? "COVERED" : "PARTIAL"));
        }

        // 6. 상태 과장 / 유의 문구
        if (PyObj.truthy(statusFact) && !PyObj.eq(PyObj.item(statusFact, "value"), "REVIEWABLE")) {
            JsonNode statusId = PyObj.item(statusFact, "fact_id");
            boolean caution = false;
            for (JsonNode s : sentences) {
                if (containsValue(s.get("fact_ids"), statusId) && CAUTION_WORDS.matcher(s.get("text").stringValue()).find()) {
                    caution = true;
                    break;
                }
            }
            if (!caution) {
                err.add("CAUTION_MISSING", "조건부/별도 확인 상태인데 유의 문장이 없음", null, statusId, null, null);
            }
            for (JsonNode s : sentences) {
                Matcher a = ASSERTIVE.matcher(s.get("text").stringValue());
                if (a.find()) {
                    err.add("OVERSTATEMENT", "조건부/별도 확인 상태에서 단정 표현", s.get("sid"), null, null, StringNode.valueOf(a.group()));
                }
            }
        }

        // 8. 의미 검증 (별도 모델). 결정적 오류를 지우지 못하고, 오류를 추가만 한다.
        JsonNode judgeResult = null;
        if (judge != null) {
            try {
                List<ObjectNode> judgeFacts = new ArrayList<>();
                for (JsonNode f : facts.values()) {
                    ObjectNode o = Json.object();
                    for (String k : List.of("fact_id", "kind", "quote")) {
                        if (f.has(k)) {
                            o.set(k, f.get(k));
                        }
                    }
                    judgeFacts.add(o);
                }
                judgeResult = judge.judge(judgeFacts, sentences);
                List<ObjectNode> bad = schemaErrors(judgeResult, "judge_verdict.schema.json");
                Set<String> judged = new HashSet<>();
                if (bad.isEmpty()) {
                    for (JsonNode j : judgeResult.get("sentences").values()) {
                        judged.add(j.get("sid").stringValue());
                    }
                }
                Set<String> all = new HashSet<>();
                for (JsonNode s : sentences) {
                    all.add(s.get("sid").stringValue());
                }
                if (!bad.isEmpty() || !judged.equals(all)) {
                    throw new IllegalStateException("판정 형식 오류 또는 일부 문장 미판정");
                }
            } catch (Exception ex) { // 판정 실패는 통과가 아니라 실패
                err.add("JUDGE_UNAVAILABLE", "의미 검증 실패로 통과 불가: " + (ex.getMessage() == null ? "" : ex.getMessage()),
                        null, null, null, null);
                ObjectNode empty = Json.object();
                empty.putArray("sentences");
                empty.putArray("coverage");
                judgeResult = empty;
            }
            JsonNode js = judgeResult.get("sentences");
            for (JsonNode j : js == null ? List.<JsonNode>of() : js.values()) {
                String verdict = j.get("verdict").stringValue();
                if (verdict.equals("CONTRADICTED")) {
                    err.add("SEMANTIC_CONTRADICTED", noteOr(j, "원문과 모순"), j.get("sid"), null, null, null);
                } else if (verdict.equals("NOT_IN_SOURCE")) {
                    err.add("SEMANTIC_NOT_IN_SOURCE", noteOr(j, "원문에 없는 내용"), j.get("sid"), null, null, null);
                }
            }
            JsonNode jc = judgeResult.get("coverage");
            for (JsonNode c : jc == null ? List.<JsonNode>of() : jc.values()) {
                String verdict = c.get("verdict").stringValue();
                if (verdict.equals("MISSING") || verdict.equals("PARTIAL")) {
                    err.add("SEMANTIC_COVERAGE_PARTIAL", noteOr(c, "의미상 설명이 불충분"), null, c.get("fact_id"), null, null);
                }
            }
        }

        return report(errors.isEmpty() ? "PASS" : "FAIL", errors, coverage, judgeResult, explanation, ledger, clock);
    }

    private static String noteOr(JsonNode j, String dflt) {
        return j.has("note") ? PyRepr.str(j.get("note")) : dflt;
    }

    private static boolean anySameValue(KoQuantity.Q q, List<JsonNode> numericFacts) {
        for (JsonNode f : numericFacts) {
            JsonNode canon = PyObj.item(f, "canonical");
            if (!PyObj.eq(PyObj.item(canon, "dim"), "date") && q.floatValue() == PyObj.toFloat(PyObj.item(canon, "value"))) {
                return true;
            }
        }
        return false;
    }

    /** {@code sorted(c or "없음" for c in comps)}. */
    private static ArrayNode sortedComparators(Iterable<JsonNode> comps) {
        List<JsonNode> vals = new ArrayList<>();
        for (JsonNode c : comps) {
            vals.add(PyObj.truthy(c) ? c : StringNode.valueOf("없음"));
        }
        if (vals.size() > 1) {
            for (JsonNode v : vals) {
                if (!v.isString()) {
                    throw new IllegalArgumentException("TypeError: '<' not supported between instances");
                }
            }
            vals.sort((a, b) -> PyJson.compareCodePoints(a.stringValue(), b.stringValue()));
        }
        ArrayNode out = Json.array();
        vals.forEach(out::add);
        return out;
    }

    private static boolean containsValue(JsonNode list, JsonNode v) {
        for (JsonNode e : iterate(list)) {
            if (PyObj.pyEquals(e, v)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode coverageEntry(JsonNode factId, String status) {
        ObjectNode o = Json.object();
        o.set("fact_id", factId);
        o.put("status", status);
        return o;
    }

    /** 오류 dict 를 파이썬과 같은 키 순서(code, message, sid, fact_id, expected, found)로, None 인 값은 빼고 만든다. */
    private record Errors(List<ObjectNode> list) {
        void add(String code, String message, JsonNode sid, JsonNode factId, JsonNode expected, JsonNode found) {
            ObjectNode e = Json.object();
            e.put("code", code);
            e.put("message", message);
            putIfNotNone(e, "sid", sid);
            putIfNotNone(e, "fact_id", factId);
            putIfNotNone(e, "expected", expected);
            putIfNotNone(e, "found", found);
            list.add(e);
        }

        private static void putIfNotNone(ObjectNode e, String k, JsonNode v) {
            if (!PyObj.isNone(v)) {
                e.set(k, v);
            }
        }
    }

    private static ObjectNode report(String verdict, List<ObjectNode> errors, List<ObjectNode> coverage, JsonNode judgeResult,
                                     JsonNode explanation, JsonNode ledger, Clock clock) {
        ObjectNode r = Json.object();
        r.put("schema_version", "verification_report@1.0");
        r.put("verdict", verdict);
        r.put("checked_at", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                .format(ISO_SECONDS));
        r.put("ledger_hash", FactLedger.ledgerHash(ledger));
        r.put("explanation_hash", Py.sha256Hex(PyJson.dumpsSorted(explanation)));
        ArrayNode errs = r.putArray("errors");
        errors.forEach(errs::add);
        ArrayNode cov = r.putArray("coverage");
        coverage.forEach(cov::add);
        r.set("judge", judgeResult == null ? NullNode.getInstance() : judgeResult);
        ArrayNode fb = r.putArray("feedback_for_regeneration");
        for (ObjectNode e : errors) {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(PyRepr.str(e.get("code"))).append("] ").append(PyRepr.str(e.get("message")));
            if (PyObj.truthy(e.get("sid"))) {
                sb.append(" / 문장 ").append(PyRepr.str(e.get("sid")));
            }
            if (PyObj.truthy(e.get("expected"))) {
                sb.append(" / 원문: ").append(PyRepr.str(e.get("expected")));
            }
            if (PyObj.truthy(e.get("found"))) {
                sb.append(" / 작성: ").append(PyRepr.str(e.get("found")));
            }
            fb.add(sb.toString());
        }
        return r;
    }

    /** AI 설명이 끝내 검증을 통과하지 못할 때 쓰는 템플릿 설명. 원장의 원문 인용만 사용한다. */
    public static ObjectNode renderFallback(JsonNode ledger, String category, String audience) {
        List<JsonNode> facts = listOf(PyObj.item(ledger, "facts"));
        ArrayNode sentences = Json.array();
        int n = 0;
        for (JsonNode f : facts) {
            if (!PyObj.truthy(PyObj.get(f, "must_mention")) || PyObj.eq(PyObj.item(f, "kind"), "STATUS")) {
                continue;
            }
            n++;
            // 인용문 안에 다른 사실의 값이 있으면 함께 인용 (UNCITED_FACT_USE 방지)
            String quote = PyObj.requireStr(PyObj.item(f, "quote"));
            List<KoQuantity.Q> qs = KoQuantity.parse(quote);
            List<JsonNode> also = new ArrayList<>();
            for (JsonNode g : facts) {
                if (g == f || !PyObj.truthy(PyObj.get(g, "canonical"))) {
                    continue;
                }
                for (KoQuantity.Q q : qs) {
                    if (qMatches(q, PyObj.item(g, "canonical"))) {
                        also.add(PyObj.item(g, "fact_id"));
                        break;
                    }
                }
            }
            for (JsonNode g : facts) {
                if (g != f && PyObj.eq(PyObj.item(g, "kind"), "SUBJECT")
                        && KoQuantity.zoneNames(quote).contains(KoQuantity.normalizeRef(PyObj.requireStr(PyObj.item(g, "name"))))) {
                    also.add(PyObj.item(g, "fact_id"));
                }
            }
            ObjectNode s = Json.object();
            s.put("sid", "t" + n);
            s.put("role", "meaning");
            s.put("text", kindLabel(PyObj.item(f, "kind")) + ": “" + quote + "”");
            ArrayNode ids = s.putArray("fact_ids");
            ids.add(PyObj.item(f, "fact_id"));
            Set<Object> seen = new HashSet<>();
            for (JsonNode a : also) {
                if (seen.add(PyObj.hashKey(a))) {
                    ids.add(a);
                }
            }
            sentences.add(s);
        }
        JsonNode st = null;
        for (JsonNode f : facts) {
            if (PyObj.eq(PyObj.item(f, "kind"), "STATUS")) {
                st = f;
                break;
            }
        }
        if (PyObj.truthy(st) && !PyObj.eq(PyObj.item(st, "value"), "REVIEWABLE")) {
            ObjectNode s = Json.object();
            s.put("sid", "t_caution");
            s.put("role", "caution");
            s.put("text", "추가 조건에 따라 결과가 달라질 수 있어 확인이 필요합니다.");
            s.putArray("fact_ids").add(PyObj.item(st, "fact_id"));
            sentences.add(s);
        }
        ObjectNode out = Json.object();
        out.put("schema_version", "explanation@2.0");
        out.put("category", category);
        out.put("audience", audience);
        out.put("source", "template_fallback");
        out.set("sentences", sentences);
        out.putArray("glossary");
        return out;
    }

    private static String kindLabel(JsonNode kind) {
        if (kind.isArray() || kind.isObject()) {
            throw new IllegalArgumentException("TypeError: unhashable type");
        }
        String label = kind.isString() ? KIND_LABEL.get(kind.stringValue()) : null;
        return label == null ? "원문" : label;
    }

    public static FinalizeResult finalize(JsonNode ledger, Function<List<String>, JsonNode> generate, String category, String audience) {
        return finalize(ledger, generate, category, audience, null, null, 3);
    }

    /**
     * 화면에 나갈 설명을 확정한다.
     * <ul>
     *   <li>{@code generate(feedback) -> explanation} (AI 호출 래퍼, feedback 은 직전 실패 사유)</li>
     *   <li>반환: explanation(basis_only 면 null), display_source = "ai_verified" | "template_fallback" | "basis_only", attempts</li>
     * </ul>
     * 화면에는 verdict == PASS 인 설명만 나간다.
     */
    public static FinalizeResult finalize(JsonNode ledger, Function<List<String>, JsonNode> generate, String category, String audience,
                                          Map<String, String> provisions, Judge judge, int maxAttempts) {
        List<ObjectNode> attempts = new ArrayList<>();
        List<String> feedback = new ArrayList<>();
        for (int i = 0; i < maxAttempts; i++) {
            JsonNode exp = generate.apply(feedback);
            ObjectNode rep = verify(exp, ledger, provisions, judge);
            attempts.add(rep);
            if (rep.get("verdict").stringValue().equals("PASS")) {
                return new FinalizeResult(exp, "ai_verified", attempts);
            }
            feedback = new ArrayList<>();
            for (JsonNode x : rep.get("feedback_for_regeneration").values()) {
                feedback.add(x.stringValue());
            }
        }
        ObjectNode fb = renderFallback(ledger, category, audience);
        ObjectNode rep = verify(fb, ledger, provisions); // 템플릿도 같은 검증을 거친다 (judge 불필요: 원문 인용)
        attempts.add(rep);
        if (rep.get("verdict").stringValue().equals("PASS")) {
            return new FinalizeResult(fb, "template_fallback", attempts);
        }
        return new FinalizeResult(null, "basis_only", attempts);
    }

    // ── 도우미 ────────────────────────────────────────────────

    private static Iterable<JsonNode> iterate(JsonNode v) {
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("TypeError: 'NoneType' object is not iterable");
        }
        if (v.isArray()) {
            return v.values();
        }
        if (v.isString()) { // 파이썬은 문자열을 글자 단위로 순회한다
            List<JsonNode> chars = new ArrayList<>();
            v.stringValue().codePoints().forEach(cp -> chars.add(StringNode.valueOf(new String(Character.toChars(cp)))));
            return chars;
        }
        if (v.isObject()) { // dict 는 키를 순회한다
            List<JsonNode> keys = new ArrayList<>();
            v.propertyNames().forEach(k -> keys.add(StringNode.valueOf(k)));
            return keys;
        }
        throw new IllegalArgumentException("TypeError: object is not iterable: " + PyRepr.repr(v));
    }

    private static List<JsonNode> listOf(JsonNode v) {
        List<JsonNode> out = new ArrayList<>();
        iterate(v).forEach(out::add);
        return out;
    }
}
