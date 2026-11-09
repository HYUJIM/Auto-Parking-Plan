package com.autoparkingplan.ai.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.autoparkingplan.ai.schema.JsonSchemaValidator;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사후 검증기 환각 시나리오 테스트 — {@code tests/unit/test_post_verifier.py}.
 *
 * <p>예시 조문은 구조 시험용으로 만든 가짜 문구다(실제 법령 원문 아님).
 * 1년 기간, 연면적 조건, 너비 기준, 단서(예외), 설치 의무를 한 조문에 담았다.
 */
public class PostVerifierTest {
    static final String PID = "prov-example-1";
    static final String PROVISION = "[예시 조문 — 실제 법령 원문 아님] ① 제2종일반주거지역에서 연면적 200제곱미터 이상인 건축물을 건축하는 자는 "
            + "사용승인일부터 1년 이내에 부설주차장을 설치하여야 한다. ② 제1항의 주차구획은 너비 2.5미터 이상으로 한다. "
            + "다만, 연면적 200제곱미터 미만인 건축물은 그러하지 아니하다.";
    public static final Map<String, String> PROVISIONS = Map.of(PID, PROVISION);

    public static ObjectNode buildLedger() {
        List<ObjectNode> cands = FactLedger.extractCandidates(PID, PROVISION);
        java.util.function.BiFunction<String, Predicate<JsonNode>, ArrayNode> cid = (kind, pred) -> {
            ArrayNode a = Json.array();
            cands.stream().filter(c -> c.get("kind").stringValue().equals(kind) && pred.test(c))
                    .forEach(c -> a.add(c.get("candidate_id")));
            return a;
        };
        java.util.function.Function<Object[], Predicate<JsonNode>> q = args -> c -> {
            JsonNode canon = c.get("canonical");
            return canon.get("dim").stringValue().equals(args[0]) && canon.get("value").doubleValue() == (double) args[1]
                    && canon.get("comparator").stringValue().equals(args[2]);
        };
        ObjectNode led = (ObjectNode) Json.parse("""
                {"schema_version": "fact_ledger@1.0", "ledger_id": "ledger-example-1", "rule_ids": ["rule-example-1"],
                 "provision_ids": ["prov-example-1"], "facts": [
                  {"fact_id": "F_SUBJ", "kind": "SUBJECT", "origin": "provision", "provision_id": "prov-example-1",
                   "quote": "제2종일반주거지역", "name": "제2종일반주거지역", "must_mention": true},
                  {"fact_id": "F_AREA", "kind": "QUANTITY", "origin": "provision", "provision_id": "prov-example-1",
                   "quote": "연면적 200제곱미터 이상인 건축물", "must_mention": true, "key_terms": ["연면적"],
                   "canonical": {"dim": "area", "value": 200, "unit": "㎡", "comparator": "GE"}},
                  {"fact_id": "F_PERIOD", "kind": "PERIOD", "origin": "provision", "provision_id": "prov-example-1",
                   "quote": "사용승인일부터 1년 이내에", "must_mention": true, "key_terms": [["사용승인", "준공"]],
                   "canonical": {"dim": "period_month", "value": 12, "unit": "개월", "comparator": "LE"}},
                  {"fact_id": "F_OBL", "kind": "MODALITY", "origin": "provision", "provision_id": "prov-example-1",
                   "quote": "부설주차장을 설치하여야 한다", "modality": "OBLIGE", "must_mention": true, "key_terms": ["주차장", "설치"]},
                  {"fact_id": "F_WIDTH", "kind": "QUANTITY", "origin": "provision", "provision_id": "prov-example-1",
                   "quote": "주차구획은 너비 2.5미터 이상", "must_mention": true, "key_terms": ["너비"],
                   "canonical": {"dim": "length", "value": 2.5, "unit": "m", "comparator": "GE"}},
                  {"fact_id": "F_EXC", "kind": "EXCEPTION", "origin": "provision", "provision_id": "prov-example-1",
                   "quote": "다만, 연면적 200제곱미터 미만인 건축물은 그러하지 아니하다", "modality": "EXEMPT",
                   "must_mention": true, "key_terms": ["연면적"],
                   "canonical": {"dim": "area", "value": 200, "unit": "㎡", "comparator": "LT"}},
                  {"fact_id": "F_STATUS", "kind": "STATUS", "origin": "engine", "provision_id": null,
                   "quote": "엔진 판정: 조건부 검토", "value": "CONDITIONAL", "must_mention": false}],
                 "dismissed": [], "review_status": "approved"}
                """);
        // candidate_ids 는 파이썬 build_ledger 처럼 must_mention 다음, 사실별 마지막 키로 붙인다
        Map<String, ArrayNode> ids = new LinkedHashMap<>();
        ids.put("F_SUBJ", cid.apply("SUBJECT", c -> true));
        ids.put("F_AREA", cid.apply("QUANTITY", q.apply(new Object[] {"area", 200.0, "GE"})));
        ids.put("F_PERIOD", cid.apply("PERIOD", c -> true));
        ids.put("F_OBL", cid.apply("MODALITY", c -> true));
        ids.put("F_WIDTH", cid.apply("QUANTITY", q.apply(new Object[] {"length", 2.5, "GE"})));
        ArrayNode exc = cid.apply("EXCEPTION", c -> true);
        exc.addAll(cid.apply("QUANTITY", q.apply(new Object[] {"area", 200.0, "LT"})));
        ids.put("F_EXC", exc);
        for (JsonNode f : led.get("facts").values()) {
            ArrayNode a = ids.get(f.get("fact_id").stringValue());
            if (a != null) {
                ((ObjectNode) f).set("candidate_ids", a);
            }
        }
        return led;
    }

    static final String GOOD_JSON = """
            {"schema_version": "explanation@2.0", "category": "PARKING_REQUIREMENT", "audience": "owner",
             "sentences": [
              {"sid": "s1", "role": "headline", "text": "사용승인일부터 1년 이내에 부설주차장을 설치해야 해요.", "fact_ids": ["F_PERIOD", "F_OBL"]},
              {"sid": "s2", "role": "summary", "text": "제2종일반주거지역에서 연면적 200㎡ 이상인 건물을 지을 때 적용돼요.", "fact_ids": ["F_SUBJ", "F_AREA"]},
              {"sid": "s3", "role": "meaning", "text": "주차 칸의 너비는 2.5m 이상이어야 해요.", "fact_ids": ["F_WIDTH"]},
              {"sid": "s4", "role": "exception", "text": "다만 연면적 200㎡ 미만인 건물은 이 의무에서 제외돼요.", "fact_ids": ["F_EXC"]},
              {"sid": "s5", "role": "caution", "text": "건물 용도와 규모 조건에 따라 달라질 수 있어 확인이 필요해요.", "fact_ids": ["F_STATUS"]}],
             "glossary": [{"term": "부설주차장", "plain": "건물에 딸려 함께 만들어야 하는 주차장"}]}
            """;
    static final JsonNode GOOD = Json.parse(GOOD_JSON);

    /** 파이썬 {@code variant(**edits)}: 문장 교체 / 삭제(drop) / 추가(add) / 인용 교체(fids). */
    static final class Variant {
        private final ObjectNode e = (ObjectNode) GOOD.deepCopy();
        private final List<String> drop = new ArrayList<>();
        private final List<JsonNode> add = new ArrayList<>();

        Variant text(String sid, String text) {
            sentence(sid, s -> s.put("text", text));
            return this;
        }

        Variant fids(String sid, String... ids) {
            sentence(sid, s -> {
                ArrayNode a = s.putArray("fact_ids");
                for (String i : ids) {
                    a.add(i);
                }
            });
            return this;
        }

        Variant drop(String sid) {
            drop.add(sid);
            return this;
        }

        Variant add(String json) {
            add.add(Json.parse(json));
            return this;
        }

        private void sentence(String sid, Consumer<ObjectNode> fn) {
            for (JsonNode s : e.get("sentences").values()) {
                if (s.get("sid").stringValue().equals(sid)) {
                    fn.accept((ObjectNode) s);
                }
            }
        }

        JsonNode build() {
            ArrayNode out = Json.array();
            for (JsonNode s : e.get("sentences").values()) {
                if (!drop.contains(s.get("sid").stringValue())) {
                    out.add(s);
                }
            }
            add.forEach(out::add);
            e.set("sentences", out);
            return e;
        }
    }

    static Variant variant() {
        return new Variant();
    }

    static Set<String> codes(JsonNode rep) {
        return rep.get("errors").values().stream().map(e -> e.get("code").stringValue()).collect(Collectors.toSet());
    }

    @Nested
    class LedgerTests {
        /** LedgerTests.test_ledger_schema_and_complete */
        @Test
        void ledgerSchemaAndComplete() {
            ObjectNode led = buildLedger();
            assertThat(JsonSchemaValidator.of(JsonSchemas.schema("fact_ledger.schema.json")).iterErrors(led)).isEmpty();
            assertThat(FactLedger.checkCompleteness(PROVISIONS, led)).isEmpty();
        }

        /** LedgerTests.test_ledger_missing_law_fact_is_caught */
        @Test
        void ledgerMissingLawFactIsCaught() {
            ObjectNode led = buildLedger();
            removeFact(led, "F_WIDTH");
            List<ObjectNode> gaps = FactLedger.checkCompleteness(PROVISIONS, led);
            assertThat(gaps).anyMatch(g -> g.get("quote").stringValue().contains("2.5"));
            JsonNode rep = PostVerifier.verify(variant().drop("s3").build(), led, PROVISIONS);
            assertThat(codes(rep)).contains("LEDGER_INCOMPLETE");
        }

        /** LedgerTests.test_dismissal_with_reason_is_accepted */
        @Test
        void dismissalWithReasonIsAccepted() {
            ObjectNode led = buildLedger();
            JsonNode width = removeFact(led, "F_WIDTH");
            ArrayNode dismissed = led.putArray("dismissed");
            for (JsonNode c : width.get("candidate_ids").values()) {
                ObjectNode d = dismissed.addObject();
                d.set("candidate_id", c);
                d.put("reason", "주차구획 기준은 별도 규칙에서 설명");
                d.put("reviewer", "검수자");
            }
            assertThat(FactLedger.checkCompleteness(PROVISIONS, led)).isEmpty();
        }

        private JsonNode removeFact(ObjectNode led, String id) {
            ArrayNode facts = (ArrayNode) led.get("facts");
            for (int i = 0; i < facts.size(); i++) {
                if (facts.get(i).get("fact_id").stringValue().equals(id)) {
                    return facts.remove(i);
                }
            }
            throw new AssertionError(id);
        }
    }

    @Nested
    class VerifierTests {
        ObjectNode led;

        @BeforeEach
        void setUp() {
            led = buildLedger();
        }

        JsonNode check(JsonNode exp, String... expectedCodes) {
            JsonNode rep = PostVerifier.verify(exp, led, PROVISIONS);
            assertThat(rep.get("verdict").stringValue()).as(rep.get("errors").toString())
                    .isEqualTo(expectedCodes.length > 0 ? "FAIL" : "PASS");
            assertThat(codes(rep)).as(rep.get("errors").toString()).contains(expectedCodes);
            return rep;
        }

        // ── 통과해야 하는 경우

        /** VerifierTests.test_good_passes */
        @Test
        void goodPasses() {
            JsonNode rep = check(GOOD);
            assertThat(rep.get("coverage").values()).allMatch(c -> c.get("status").stringValue().equals("COVERED"));
        }

        /** VerifierTests.test_equivalent_period_12_months_passes */
        @Test
        void equivalentPeriod12MonthsPasses() {
            check(variant().text("s1", "사용승인일부터 12개월 이내에 부설주차장을 설치해야 해요.").build());
        }

        /** VerifierTests.test_equivalent_unit_spelling_passes */
        @Test
        void equivalentUnitSpellingPasses() {
            check(variant().text("s3", "주차 칸의 너비는 2.5미터 이상이어야 해요.").build());
        }

        // ── B. 세부 왜곡

        /** VerifierTests.test_period_1year_to_8months */
        @Test
        void period1yearTo8months() {
            check(variant().text("s1", "사용승인일부터 8개월 이내에 부설주차장을 설치해야 해요.").build(), "DETAIL_MISMATCH", "OMISSION_DETAIL");
        }

        /** VerifierTests.test_period_1year_to_1year6months */
        @Test
        void period1yearTo1year6months() {
            check(variant().text("s1", "사용승인일부터 1년 6개월 이내에 부설주차장을 설치해야 해요.").build(), "DETAIL_MISMATCH");
        }

        /** VerifierTests.test_period_in_days_not_converted */
        @Test
        void periodInDaysNotConverted() {
            check(variant().text("s1", "사용승인일부터 365일 이내에 부설주차장을 설치해야 해요.").build(), "DETAIL_MISMATCH");
        }

        /** VerifierTests.test_comparator_flip */
        @Test
        void comparatorFlip() {
            check(variant().text("s3", "주차 칸의 너비는 2.5m를 초과해야 해요.").build(), "COMPARATOR_MISMATCH");
        }

        /** VerifierTests.test_comparator_dropped */
        @Test
        void comparatorDropped() {
            check(variant().text("s2", "제2종일반주거지역에서 연면적 200㎡인 건물을 지을 때 적용돼요.").build(), "COMPARATOR_MISSING");
        }

        /** VerifierTests.test_unit_conversion_flagged */
        @Test
        void unitConversionFlagged() {
            check(variant().text("s3", "주차 칸의 너비는 250cm 이상이어야 해요.").build(), "UNIT_MISMATCH");
        }

        /** VerifierTests.test_value_without_unit */
        @Test
        void valueWithoutUnit() {
            check(variant().text("s2", "제2종일반주거지역에서 연면적 200 이상인 건물을 지을 때 적용돼요.").build(), "VALUE_WITHOUT_UNIT");
        }

        /** VerifierTests.test_obligation_flipped_to_exempt */
        @Test
        void obligationFlippedToExempt() {
            check(variant().text("s1", "사용승인일부터 1년 이내라면 부설주차장을 설치하지 않아도 돼요.").build(), "POLARITY_MISMATCH");
        }

        /** VerifierTests.test_subject_changed */
        @Test
        void subjectChanged() {
            check(variant().text("s2", "제3종일반주거지역에서 연면적 200㎡ 이상인 건물을 지을 때 적용돼요.").build(), "SUBJECT_MISMATCH",
                    "OMISSION_DETAIL");
        }

        /** VerifierTests.test_uncited_value_use */
        @Test
        void uncitedValueUse() {
            check(variant().text("s3", "연면적 200㎡ 이상 건물의 주차 칸 너비는 2.5m 이상이어야 해요.").build(), "UNCITED_FACT_USE");
        }

        // ── A. 날조

        /** VerifierTests.test_fabricated_fine */
        @Test
        void fabricatedFine() {
            check(variant().add("""
                    {"sid": "s6", "role": "meaning", "text": "위반하면 과태료 50만원이 부과돼요.", "fact_ids": ["F_OBL"]}""").build(),
                    "FABRICATED_VALUE");
        }

        /** VerifierTests.test_fabricated_date */
        @Test
        void fabricatedDate() {
            check(variant().add("""
                    {"sid": "s6", "role": "meaning", "text": "2027년 1월 1일부터 적용돼요.", "fact_ids": ["F_OBL"]}""").build(),
                    "FABRICATED_VALUE");
        }

        /** VerifierTests.test_fabricated_reference_and_law_name */
        @Test
        void fabricatedReferenceAndLawName() {
            check(variant().add("""
                    {"sid": "s6", "role": "meaning", "text": "주차장법 제19조에 따른 기준이에요.", "fact_ids": ["F_OBL"]}""").build(),
                    "REFERENCE_FABRICATED", "LAW_NAME_FABRICATED");
        }

        /** VerifierTests.test_fabricated_permission_without_numbers */
        @Test
        void fabricatedPermissionWithoutNumbers() {
            check(variant().add("""
                    {"sid": "s6", "role": "meaning", "text": "주차장은 지하에만 설치할 수 있어요.", "fact_ids": ["F_OBL"]}""").build(),
                    "POLARITY_MISMATCH");
        }

        /** VerifierTests.test_ungrounded_sentence */
        @Test
        void ungroundedSentence() {
            check(variant().add("""
                    {"sid": "s6", "role": "meaning", "text": "요즘 많이들 이렇게 지어요.", "fact_ids": []}""").build(),
                    "UNGROUNDED_SENTENCE");
        }

        /** VerifierTests.test_unknown_fact_id */
        @Test
        void unknownFactId() {
            check(variant().fids("s3", "F_WIDTH", "F_NOPE").build(), "UNKNOWN_FACT_ID");
        }

        // ── C. 누락

        /** VerifierTests.test_exception_omitted */
        @Test
        void exceptionOmitted() {
            JsonNode rep = check(variant().drop("s4").build(), "OMISSION");
            assertThat(rep.get("coverage").values()).contains(Json.parse("{\"fact_id\": \"F_EXC\", \"status\": \"MISSING\"}"));
        }

        /** VerifierTests.test_condition_omitted */
        @Test
        void conditionOmitted() {
            check(variant().text("s2", "제2종일반주거지역에서 건물을 지을 때 적용돼요.").fids("s2", "F_SUBJ").build(), "OMISSION");
        }

        /** VerifierTests.test_exception_mentioned_but_meaning_lost */
        @Test
        void exceptionMentionedButMeaningLost() {
            check(variant().text("s4", "연면적 200㎡ 미만인 건물도 있어요.").build(), "OMISSION_DETAIL");
        }

        /** VerifierTests.test_key_term_missing */
        @Test
        void keyTermMissing() {
            check(variant().text("s1", "1년 이내에 부설주차장을 설치해야 해요.").build(), "OMISSION_DETAIL");
        }

        // ── 상태 과장

        /** VerifierTests.test_overstatement */
        @Test
        void overstatement() {
            check(variant().add("""
                    {"sid": "s6", "role": "meaning", "text": "주차장만 갖추면 허가를 받을 수 있어요.", "fact_ids": ["F_OBL"]}""").build(),
                    "OVERSTATEMENT");
        }

        /** VerifierTests.test_caution_missing_when_conditional */
        @Test
        void cautionMissingWhenConditional() {
            check(variant().drop("s5").build(), "CAUTION_MISSING");
        }

        /** VerifierTests.test_glossary_number_blocked_by_schema */
        @Test
        void glossaryNumberBlockedBySchema() {
            ObjectNode e = (ObjectNode) GOOD.deepCopy();
            e.set("glossary", Json.parse("[{\"term\": \"부설주차장\", \"plain\": \"200㎡ 이상 건물에 필요한 주차장\"}]"));
            check(e, "SCHEMA");
        }
    }

    @Nested
    class JudgeAndFinalizeTests {
        ObjectNode led;

        @BeforeEach
        void setUp() {
            led = buildLedger();
        }

        static ObjectNode verdicts(List<JsonNode> sents, java.util.function.Function<String, String> verdictBySid, ArrayNode coverage) {
            ObjectNode r = Json.object();
            r.put("schema_version", "judge_verdict@1.0");
            ArrayNode a = r.putArray("sentences");
            for (JsonNode s : sents) {
                ObjectNode o = a.addObject();
                o.set("sid", s.get("sid"));
                o.put("verdict", verdictBySid.apply(s.get("sid").stringValue()));
            }
            r.set("coverage", coverage);
            return r;
        }

        /** JudgeAndFinalizeTests.test_judge_adds_failures */
        @Test
        void judgeAddsFailures() {
            PostVerifier.Judge judge = (facts, sents) -> verdicts(sents, sid -> sid.equals("s2") ? "NOT_IN_SOURCE" : "ENTAILED", Json.array());
            JsonNode rep = PostVerifier.verify(GOOD, led, null, judge);
            assertThat(rep.get("verdict").stringValue()).isEqualTo("FAIL");
            assertThat(codes(rep)).contains("SEMANTIC_NOT_IN_SOURCE");
        }

        /** JudgeAndFinalizeTests.test_judge_cannot_override_deterministic_failure */
        @Test
        void judgeCannotOverrideDeterministicFailure() {
            PostVerifier.Judge judge = (facts, sents) -> verdicts(sents, sid -> "ENTAILED",
                    (ArrayNode) Json.parse("[{\"fact_id\": \"F_PERIOD\", \"verdict\": \"COVERED\"}]"));
            JsonNode bad = variant().text("s1", "사용승인일부터 8개월 이내에 부설주차장을 설치해야 해요.").build();
            assertThat(PostVerifier.verify(bad, led, null, judge).get("verdict").stringValue()).isEqualTo("FAIL");
        }

        /** JudgeAndFinalizeTests.test_judge_failure_is_not_a_pass */
        @Test
        void judgeFailureIsNotAPass() {
            PostVerifier.Judge broken = (facts, sents) -> {
                throw new TimeoutException("judge timeout");
            };
            PostVerifier.Judge partial = (facts, sents) -> Json.parse("""
                    {"schema_version": "judge_verdict@1.0", "sentences": [{"sid": "s1", "verdict": "ENTAILED"}], "coverage": []}""");
            for (PostVerifier.Judge j : List.of(broken, partial)) {
                JsonNode rep = PostVerifier.verify(GOOD, led, null, j);
                assertThat(rep.get("verdict").stringValue()).isEqualTo("FAIL");
                assertThat(codes(rep)).contains("JUDGE_UNAVAILABLE");
            }
        }

        /** JudgeAndFinalizeTests.test_good_passes_with_agreeing_judge */
        @Test
        void goodPassesWithAgreeingJudge() {
            PostVerifier.Judge ok = (facts, sents) -> {
                ArrayNode cov = Json.array();
                for (ObjectNode f : facts) {
                    if (!f.get("kind").stringValue().equals("STATUS")) {
                        cov.addObject().set("fact_id", f.get("fact_id"));
                        ((ObjectNode) cov.get(cov.size() - 1)).put("verdict", "COVERED");
                    }
                }
                return verdicts(sents, sid -> "ENTAILED", cov);
            };
            assertThat(PostVerifier.verify(GOOD, led, null, ok).get("verdict").stringValue()).isEqualTo("PASS");
        }

        /** JudgeAndFinalizeTests.test_finalize_regenerates_with_feedback */
        @Test
        void finalizeRegeneratesWithFeedback() {
            List<List<String>> calls = new ArrayList<>();
            PostVerifier.FinalizeResult out = PostVerifier.finalize(led, fb -> {
                calls.add(fb);
                return calls.size() == 1 ? variant().text("s1", "사용승인일부터 8개월 이내에 부설주차장을 설치해야 해요.").build() : GOOD;
            }, "PARKING_REQUIREMENT", "owner", PROVISIONS, null, 3);
            assertThat(out.displaySource()).isEqualTo("ai_verified");
            assertThat(out.attempts()).hasSize(2);
            assertThat(calls.get(1)).anyMatch(f -> f.contains("DETAIL_MISMATCH"));
        }

        /** JudgeAndFinalizeTests.test_finalize_falls_back_to_verified_template */
        @Test
        void finalizeFallsBackToVerifiedTemplate() {
            PostVerifier.FinalizeResult out = PostVerifier.finalize(led, fb -> variant().drop("s4").build(), "PARKING_REQUIREMENT",
                    "owner", PROVISIONS, null, 3);
            assertThat(out.displaySource()).isEqualTo("template_fallback");
            assertThat(out.attempts().get(out.attempts().size() - 1).get("verdict").stringValue()).isEqualTo("PASS");
            assertThat(out.attempts()).hasSize(4);
        }

        /** JudgeAndFinalizeTests.test_fallback_alone_passes */
        @Test
        void fallbackAlonePasses() {
            ObjectNode fb = PostVerifier.renderFallback(led, "PARKING_REQUIREMENT", "owner");
            assertThat(PostVerifier.verify(fb, led, PROVISIONS).get("verdict").stringValue()).isEqualTo("PASS");
        }

        /** JudgeAndFinalizeTests.test_report_matches_schema */
        @Test
        void reportMatchesSchema() {
            JsonSchemaValidator v = JsonSchemaValidator.of(JsonSchemas.schema("verification_report.schema.json"));
            for (JsonNode exp : List.of(GOOD, variant().drop("s4").build())) {
                JsonNode rep = PostVerifier.verify(exp, led, PROVISIONS);
                assertThat(v.iterErrors(rep)).isEmpty();
            }
        }
    }
}
