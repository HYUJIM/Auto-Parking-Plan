package com.autoparkingplan.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.ai.verification.PostVerifier;
import com.autoparkingplan.ai.verification.PostVerifierTest;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** FakeLLM 의 scripted 응답·호출 기록과 설정 → 클라이언트 조립 ({@code app/llm/fake.py}, {@code app/llm/factory.py}). */
class FakeLLMAndFactoryTest {

    private static String writerUser(JsonNode ledger, String category, String audience) {
        ObjectNode payload = Json.object();
        payload.put("category", category);
        payload.put("audience", audience);
        payload.putObject("review_item").put("category", category);
        var facts = payload.putArray("facts");
        for (JsonNode f : ledger.get("facts").values()) {
            ObjectNode o = (ObjectNode) f.deepCopy();
            o.remove("candidate_ids");
            facts.add(o);
        }
        return PyJson.dumps(payload);
    }

    /** fake.py 기본 동작: 작성 결과는 source='ai' 이고 사후 검증을 통과한다 (파이프라인 테스트의 ai_verified 경로). */
    @Test
    void writerOutputPassesPostVerifier() {
        ObjectNode led = PostVerifierTest.buildLedger();
        FakeLLM llm = new FakeLLM();
        JsonNode exp = llm.generateJson(Task.EXPLANATION_WRITER, "s", writerUser(led, "PARKING_REQUIREMENT", "owner"),
                JsonSchemas.schema("explanation.schema.json"), 4000).data();
        assertThat(exp.get("source").stringValue()).isEqualTo("ai");
        assertThat(exp.get("sentences").get(0).get("role").stringValue()).isEqualTo("headline");
        assertThat(PostVerifier.verify(exp, led, PostVerifierTest.PROVISIONS).get("verdict").stringValue()).isEqualTo("PASS");
        assertThat(llm.model()).isEqualTo("fake-deterministic-1");
        assertThat(llm.provider()).isEqualTo("fake");
    }

    /**
     * tests/integration/test_pipeline_example.py::test_bad_ai_explanation_falls_back_to_template 의 순수 부분:
     * scripted 작성 결과가 세 번 모두 날조(50%)면 템플릿 설명으로 대체되고, 화면 문장에 날조 값이 없다.
     */
    @Test
    void badAiExplanationFallsBackToTemplate() {
        ObjectNode led = PostVerifierTest.buildLedger();
        FakeLLM llm = new FakeLLM();
        FakeLLM.Scripted bad = FakeLLM.Scripted.compute(payload -> Json.parse("""
                {"schema_version": "explanation@2.0", "category": "%s", "audience": "%s",
                 "sentences": [{"sid": "s1", "role": "headline", "text": "건폐율은 50%% 이하예요.", "fact_ids": ["%s"]}]}"""
                .formatted(payload.get("category").stringValue(), payload.get("audience").stringValue(),
                        payload.get("facts").get(0).get("fact_id").stringValue())));
        llm.script(Task.EXPLANATION_WRITER, bad, bad, bad);
        String user = writerUser(led, "PARKING_REQUIREMENT", "architect");
        PostVerifier.FinalizeResult out = PostVerifier.finalize(led,
                fb -> llm.generateJson(Task.EXPLANATION_WRITER, "s", user, JsonSchemas.schema("explanation.schema.json")).data(),
                "PARKING_REQUIREMENT", "architect", PostVerifierTest.PROVISIONS, null, 3);
        assertThat(out.displaySource()).isEqualTo("template_fallback");
        assertThat(out.explanation().get("sentences").values()).allMatch(s -> !s.get("text").stringValue().contains("50%"));
        assertThat(llm.calls()).hasSize(3);
        assertThat(llm.scripted().get(Task.EXPLANATION_WRITER)).isEmpty();
    }

    /** tests/integration/test_pipeline_example.py::test_explanation_cache_reused 가 세는 호출 기록 (task, payload). */
    @Test
    void callsAreRecorded() {
        FakeLLM llm = new FakeLLM("fake-judge");
        llm.generateJson(Task.SEMANTIC_JUDGE, "s", "{\"facts\": [], \"sentences\": [{\"sid\": \"s1\"}]}", Json.object());
        assertThat(llm.calls()).hasSize(1);
        assertThat(llm.calls().get(0).task()).isEqualTo(Task.SEMANTIC_JUDGE);
        assertThat(llm.calls().get(0).payload().get("sentences").get(0).get("sid").stringValue()).isEqualTo("s1");
    }

    /** scripted 응답: 고정 JSON → 예외 → 비면 기본 동작, rule_extraction 은 scripted 가 없으면 LLMError. */
    @Test
    void scriptedRepliesThenDefaults() {
        JsonNode fixed = Json.parse("{\"rules\": []}");
        FakeLLM llm = new FakeLLM("m", Map.of(Task.RULE_EXTRACTION,
                List.of(FakeLLM.Scripted.reply(fixed), FakeLLM.Scripted.fail(new LLMError("주입된 실패")))));
        assertThat(llm.generateJson(Task.RULE_EXTRACTION, "s", "{}", Json.object()).data()).isSameAs(fixed);
        assertThatThrownBy(() -> llm.generateJson(Task.RULE_EXTRACTION, "s", "{}", Json.object())).hasMessage("주입된 실패");
        assertThatThrownBy(() -> llm.generateJson(Task.RULE_EXTRACTION, "s", "{}", Json.object()))
                .isExactlyInstanceOf(LLMError.class).hasMessage("FakeLLM: rule_extraction 에 대한 scripted 응답이 없습니다");
        assertThat(llm.calls()).hasSize(3);
    }

    /** factory.build_llms: none → 모두 null, fake → "fake:<모델>", 판정 제공자 따로 지정. */
    @Test
    void buildLlms() {
        LLMSet none = LlmFactory.buildLlms(LlmSettings.defaults());
        assertThat(none.writer()).isNull();
        assertThat(none.judge()).isNull();
        assertThat(none.extractor()).isNull();

        LLMSet fake = LlmFactory.buildLlms(LlmSettings.defaults().withProviders("fake", null));
        assertThat(fake.writer().provider()).isEqualTo("fake");
        assertThat(fake.writer().model()).isEqualTo("fake:claude-opus-5-5");
        assertThat(fake.judge().model()).isEqualTo("fake:claude-sonnet-5-5");
        assertThat(fake.extractor().model()).isEqualTo("fake:claude-opus-5-5");

        LLMSet mixed = LlmFactory.buildLlms(LlmSettings.defaults().withProviders("none", "fake"));
        assertThat(mixed.writer()).isNull();
        assertThat(mixed.judge()).isInstanceOf(FakeLLM.class);
        assertThat(LlmSettings.defaults().withProviders("openai", "").effectiveJudgeProvider()).isEqualTo("openai");
    }

    /** factory._make: 키 없음·모르는 제공자 → LLMUnavailable (파이썬과 같은 문구), 키가 있으면 SDK 클라이언트. */
    @Test
    void makeRequiresKeys() {
        assertThatThrownBy(() -> LlmFactory.buildLlms(LlmSettings.defaults().withProviders("anthropic", null)))
                .isInstanceOf(LLMUnavailable.class).hasMessage("LLM_PROVIDER=anthropic 인데 ANTHROPIC_API_KEY 가 없습니다");
        assertThatThrownBy(() -> LlmFactory.buildLlms(LlmSettings.defaults().withProviders("openai", null).withKeys(null, "")))
                .isInstanceOf(LLMUnavailable.class).hasMessage("LLM_PROVIDER=openai 인데 OPENAI_API_KEY 가 없습니다");
        assertThatThrownBy(() -> LlmFactory.buildLlms(LlmSettings.defaults().withProviders("gemini", null)))
                .isInstanceOf(LLMUnavailable.class).hasMessage("알 수 없는 LLM 제공자: gemini");
        LLMSet set = LlmFactory.buildLlms(LlmSettings.defaults().withProviders("anthropic", "openai").withKeys("a-key", "o-key"));
        assertThat(set.writer()).isInstanceOf(AnthropicLLM.class);
        assertThat(((AnthropicLLM) set.writer()).effort()).isEqualTo("medium");
        assertThat(((AnthropicLLM) set.extractor()).effort()).isEqualTo("high");
        assertThat(set.judge()).isInstanceOf(OpenAILLM.class);
        assertThat(set.judge().model()).isEqualTo("claude-sonnet-5-5");
        assertThat(LlmSettings.defaults().withKeys("secret", "secret2").toString()).doesNotContain("secret");
    }

    /** Task 이름은 파이썬과 같다 (오류 문구 머리말). */
    @Test
    void taskNames() {
        assertThat(Task.RULE_EXTRACTION).hasToString("rule_extraction");
        assertThat(Task.of("semantic_judge")).isEqualTo(Task.SEMANTIC_JUDGE);
    }
}
