package com.autoparkingplan.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.ai.llm.ApiSchema;
import com.autoparkingplan.ai.llm.FakeLLM;
import com.autoparkingplan.ai.llm.Task;
import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.ai.pycompat.PyRe;
import com.autoparkingplan.ai.pycompat.PyRepr;
import com.autoparkingplan.ai.schema.JsonSchemaValidator;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.ai.schema.SchemaError;
import com.autoparkingplan.ai.verification.AiOutput;
import com.autoparkingplan.ai.verification.FactLedger;
import com.autoparkingplan.ai.verification.KoQuantity;
import com.autoparkingplan.ai.verification.PostVerifier;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 파이썬판과의 결과 대조 — 기대값은 {@code src/test/resources/parity/generate_parity.py} 가 파이썬판 app 을 직접 실행해 만들었다.
 * (파이썬 테스트에 대응하는 것이 없는 자바 전용 대조 테스트)
 */
class ParityTest {

    static Stream<Arguments> indexed(String key) {
        List<JsonNode> l = ParityFixture.list(key);
        return IntStream.range(0, l.size()).mapToObj(i -> Arguments.of(i, l.get(i)));
    }

    // ── 정규식 ─────────────────────────────────────────────

    /** 자바 패턴이 파이썬 원문 패턴(을 PyRe 로 옮긴 것)과 같다. */
    @Test
    void patternsAreTheSamePythonPatterns() {
        JsonNode p = ParityFixture.get().get("patterns");
        assertThat(KoQuantity.NUM).isEqualTo(p.get("NUM").stringValue());
        assertThat(KoQuantity.NATIVE_ALT).isEqualTo(p.get("NATIVE_ALT").stringValue());
        assertThat(KoQuantity.SINO_ALT).isEqualTo(p.get("SINO_ALT").stringValue());
        assertThat(KoQuantity.UNIT_ALT).isEqualTo(p.get("UNIT_ALT").stringValue());
        Map<String, Pattern> compiled = Map.ofEntries(
                Map.entry("COMPARATOR_RE", KoQuantity.COMPARATOR_RE), Map.entry("DATE_RE", KoQuantity.DATE_RE),
                Map.entry("REF_RE", KoQuantity.REF_RE), Map.entry("PERIOD_RE", KoQuantity.PERIOD_RE),
                Map.entry("MONEY_RE", KoQuantity.MONEY_RE), Map.entry("UNIT_RE", KoQuantity.UNIT_RE),
                Map.entry("BARE_RE", KoQuantity.BARE_RE), Map.entry("EXEMPT_RE", KoQuantity.EXEMPT_RE),
                Map.entry("NEG_RE", KoQuantity.NEG_RE), Map.entry("POS_RE", KoQuantity.POS_RE), Map.entry("OBL_RE", KoQuantity.OBL_RE),
                Map.entry("EXCEPTION_RE", KoQuantity.EXCEPTION_RE), Map.entry("CONDITION_RE", KoQuantity.CONDITION_RE),
                Map.entry("ZONE_NAME_RE", KoQuantity.ZONE_NAME_RE), Map.entry("LAW_NAME_RE", KoQuantity.LAW_NAME_RE));
        compiled.forEach((name, pat) -> assertThat(pat.pattern()).as(name).isEqualTo(PyRe.translate(p.get(name).stringValue())));
    }

    // ── ko_quantity ────────────────────────────────────────

    static Stream<Arguments> koCases() {
        return indexed("ko_quantity");
    }

    /** parse / modality / split_sentences / zone_names / law_names / normalize_ref / CONDITION_RE / _numbers. */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("koCases")
    void koQuantity(int i, JsonNode c) {
        String text = c.get("text").stringValue();
        ArrayNode parsed = Json.array();
        KoQuantity.parse(text).forEach(q -> parsed.add(q.toJson()));
        assertThat(PyJson.dumps(parsed)).as("parse " + PyRepr.reprString(text)).isEqualTo(c.get("parse").stringValue());
        assertThat(KoQuantity.modality(text)).isEqualTo(c.get("modality").isNull() ? null : c.get("modality").stringValue());

        ArrayNode sentences = Json.array();
        for (KoQuantity.Sentence s : KoQuantity.splitSentences(text)) {
            sentences.addArray().add(s.start()).add(s.text());
        }
        assertThat(sentences).as("split_sentences").isEqualTo(c.get("sentences"));
        assertThat(KoQuantity.zoneNames(text)).isEqualTo(strings(c.get("zones")));
        assertThat(KoQuantity.lawNames(text)).isEqualTo(strings(c.get("laws")));
        assertThat(KoQuantity.normalizeRef(text)).isEqualTo(c.get("normalized").stringValue());
        List<String> conds = new ArrayList<>();
        Matcher m = KoQuantity.CONDITION_RE.matcher(text);
        while (m.find()) {
            conds.add(m.group());
        }
        assertThat(conds).isEqualTo(strings(c.get("conditions")));
        List<Double> nums = new ArrayList<>(AiOutput.numbers(text));
        nums.sort(Double::compare);
        List<Double> expected = new ArrayList<>();
        c.get("numbers").values().forEach(n -> expected.add(n.doubleValue()));
        assertThat(nums).isEqualTo(expected);
    }

    static Stream<Arguments> candidateCases() {
        return indexed("candidates");
    }

    /** fact_ledger.extract_candidates (후보 id = sha1 앞 12자리, 위치는 코드포인트). */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("candidateCases")
    void extractCandidates(int i, JsonNode c) {
        ArrayNode out = Json.array();
        FactLedger.extractCandidates(c.get("provision_id").stringValue(), c.get("text").stringValue()).forEach(out::add);
        assertThat(PyJson.dumps(out)).isEqualTo(c.get("candidates").stringValue());
    }

    @Test
    void locate() {
        for (JsonNode c : ParityFixture.list("locate")) {
            var span = AiOutput.locate(c.get("quote").stringValue(), c.get("text").stringValue());
            if (c.get("span").isNull()) {
                assertThat(span).isEmpty();
            } else {
                assertThat(span).hasValue(new AiOutput.Span(c.get("span").get(0).intValue(), c.get("span").get(1).intValue()));
            }
        }
    }

    // ── api_schema ─────────────────────────────────────────

    /** llm.base.api_schema: 스키마 파일마다 json.dumps 결과가 바이트 단위로 같다 (키 순서 포함). */
    @Test
    void apiSchemaForEverySchemaFile() {
        JsonNode expected = ParityFixture.get().get("api_schema");
        assertThat(expected.propertyNames()).containsExactlyInAnyOrderElementsOf(JsonSchemas.SCHEMA_FILES);
        for (String name : JsonSchemas.SCHEMA_FILES) {
            JsonNode original = JsonSchemas.schema(name);
            String before = PyJson.dumps(original);
            assertThat(PyJson.dumps(ApiSchema.apiSchema(original))).as(name).isEqualTo(expected.get(name).stringValue());
            assertThat(PyJson.dumps(original)).as("입력을 바꾸지 않는다").isEqualTo(before);
        }
        for (JsonNode c : ParityFixture.list("api_schema_synthetic")) {
            assertThat(PyJson.dumps(ApiSchema.apiSchema(c.get("schema")))).isEqualTo(c.get("expected").stringValue());
        }
    }

    // ── JSON Schema 검증 ───────────────────────────────────

    static Stream<Arguments> schemaCases() {
        return indexed("schema");
    }

    /** Draft202012Validator(registry, FORMAT_CHECKER) 와 레지스트리·format 없는 검증기의 오류 문구·순서·키워드. */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("schemaCases")
    void schemaValidation(int i, JsonNode c) {
        String schema = c.get("schema").stringValue();
        JsonNode instance = c.get("instance");
        List<SchemaError> errs = JsonSchemas.errors(instance, schema);
        assertThat(errs.stream().map(SchemaError::describe).toList()).as(c.get("name").stringValue())
                .isEqualTo(strings(c.get("registry").get("unsorted")));
        assertThat(JsonSchemaValidator.sortedByPath(errs).stream().map(SchemaError::describe).toList())
                .isEqualTo(strings(c.get("registry").get("sorted")));
        List<String> keywords = new ArrayList<>();
        c.get("registry").get("keywords").values().forEach(k -> keywords.add(k.isNull() ? null : k.stringValue()));
        assertThat(errs.stream().map(SchemaError::keyword).toList()).isEqualTo(keywords);
        assertThat(JsonSchemas.isValid(instance, schema)).isEqualTo(errs.isEmpty());
        if (!c.get("plain").isNull()) {
            List<SchemaError> plain = JsonSchemaValidator.of(JsonSchemas.schema(schema)).iterErrors(instance);
            assertThat(plain.stream().map(SchemaError::describe).toList()).isEqualTo(strings(c.get("plain").get("unsorted")));
        }
    }

    // ── ai_output.validate_extraction ──────────────────────

    static Stream<Arguments> extractionCases() {
        return indexed("extraction");
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("extractionCases")
    void validateExtraction(int i, JsonNode c) {
        Map<String, String> provisions = ParityFixture.stringMap(
                ParityFixture.get().get("extraction_provisions").get(c.get("seed").stringValue()));
        AiOutput.ExtractionCheck r = AiOutput.validateExtraction(c.get("output"), provisions);
        assertThat(r.errors()).as(c.get("seed") + " " + c.get("variant")).isEqualTo(strings(c.get("errors")));
        ArrayNode spans = Json.array();
        r.spans().forEach(spans::add);
        assertThat(PyJson.dumps(spans)).isEqualTo(c.get("spans").stringValue());
    }

    // ── 원장 ───────────────────────────────────────────────

    static Stream<Arguments> ledgerCases() {
        return indexed("ledgers");
    }

    /** ledger_hash, check_completeness, render_fallback. */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("ledgerCases")
    void ledgers(int i, JsonNode c) {
        JsonNode ledger = c.get("ledger");
        Map<String, String> provisions = ParityFixture.stringMap(c.get("provisions"));
        assertThat(FactLedger.ledgerHash(ledger)).isEqualTo(c.get("ledger_hash").stringValue());
        assertThat(dumpsList(FactLedger.checkCompleteness(provisions, ledger))).isEqualTo(c.get("gaps").stringValue());
        ObjectNode noDismissed = (ObjectNode) ledger.deepCopy();
        noDismissed.putArray("dismissed");
        assertThat(dumpsList(FactLedger.checkCompleteness(provisions, noDismissed))).isEqualTo(c.get("gaps_without_dismissed").stringValue());
        assertThat(PyJson.dumps(PostVerifier.renderFallback(ledger, "PARKING_REQUIREMENT", "owner")))
                .isEqualTo(c.get("fallback_owner").stringValue());
        assertThat(PyJson.dumps(PostVerifier.renderFallback(ledger, "BUILDING_COVERAGE", "architect")))
                .isEqualTo(c.get("fallback_architect").stringValue());
    }

    // ── post_verifier.verify ───────────────────────────────

    static Stream<Arguments> verifyCases() {
        return indexed("verify");
    }

    /** 검증 보고서 전체 (checked_at 제외) — 오류 순서·문구·expected/found·coverage·judge·feedback·해시. */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("verifyCases")
    void verify(int i, JsonNode c) {
        JsonNode lc = ParityFixture.list("ledgers").get(c.get("ledger_index").intValue());
        JsonNode ledger = lc.get("ledger");
        Map<String, String> provisions = ParityFixture.stringMap(lc.get("provisions"));
        for (JsonNode run : c.get("runs").values()) {
            String judge = run.get("judge").isNull() ? null : run.get("judge").stringValue();
            ObjectNode rep = PostVerifier.verify(c.get("explanation").deepCopy(), ledger,
                    run.get("with_provisions").booleanValue() ? provisions : null, ParityFixture.judge(judge));
            assertThat(rep.get("checked_at").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+00:00");
            rep.remove("checked_at");
            assertThat(PyJson.dumps(rep)).as(c.get("mutation") + " judge=" + judge + " provisions=" + run.get("with_provisions"))
                    .isEqualTo(run.get("report").stringValue());
        }
    }

    // ── FakeLLM ────────────────────────────────────────────

    static Stream<Arguments> fakeCases() {
        return indexed("fake");
    }

    /** FakeLLM 작성·판정 출력이 파이썬과 바이트 단위로 같다. */
    @ParameterizedTest(name = "[{0}]")
    @MethodSource("fakeCases")
    void fakeLlm(int i, JsonNode c) {
        FakeLLM llm = new FakeLLM();
        JsonNode w = llm.generateJson(Task.EXPLANATION_WRITER, "s", c.get("writer_user").stringValue(), Json.object()).data();
        assertThat(PyJson.dumps(w)).isEqualTo(c.get("writer").stringValue());
        JsonNode j = llm.generateJson(Task.SEMANTIC_JUDGE, "s", c.get("judge_user").stringValue(), Json.object()).data();
        assertThat(PyJson.dumps(j)).isEqualTo(c.get("judge").stringValue());
        assertThat(llm.calls()).hasSize(2);
    }

    // ── 파이썬 repr / float ────────────────────────────────

    @Test
    void pythonReprAndStr() {
        for (JsonNode c : ParityFixture.list("repr")) {
            assertThat(PyRepr.repr(c.get("value"))).isEqualTo(c.get("repr").stringValue());
            assertThat(PyRepr.str(c.get("value"))).isEqualTo(c.get("str").stringValue());
        }
    }

    @Test
    void pythonFloat() {
        for (JsonNode c : ParityFixture.list("float")) {
            String text = c.get("text").stringValue();
            if (c.get("ok").booleanValue()) {
                assertThat(Py.floatRepr(PyObj.parseFloat(text))).as(PyRepr.reprString(text)).isEqualTo(c.get("value").stringValue());
            } else {
                assertThatThrownBy(() -> PyObj.parseFloat(text)).as(PyRepr.reprString(text)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.values().forEach(v -> out.add(v.stringValue()));
        return out;
    }

    private static String dumpsList(List<ObjectNode> l) {
        ArrayNode a = Json.array();
        l.forEach(a::add);
        return PyJson.dumps(a);
    }
}
