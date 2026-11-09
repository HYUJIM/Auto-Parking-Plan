package com.autoparkingplan.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.autoparkingplan.ai.ParityFixture;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anthropic·OpenAI 클라이언트를 로컬 HTTP 서버에 붙여, 파이썬판({@code anthropic_client.py}·{@code openai_client.py})이 같은 서버에
 * 보낸 요청 본문·베타 헤더와 같은 응답에 대한 결과(데이터·모델·요청 id·오류 종류·오류 문구)를 대조한다 (실제 API 호출 없음).
 * 기대값은 {@code generate_parity.py} 의 {@code llm_http_cases()} 가 파이썬 SDK 로 만들었다.
 */
class LlmClientParityTest {
    static LocalServer server;
    static final String USER = PyJson.dumps(Json.parse("{\"category\": \"BUILDING_COVERAGE\", \"facts\": [{\"fact_id\": \"F1\", \"quote\": \"60퍼센트 이하\"}]}"));

    @BeforeAll
    static void start() throws IOException {
        server = new LocalServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void reset() {
        server.reset();
    }

    static Stream<Arguments> anthropicCases() {
        return ParityFixture.get().get("llm_http").get("anthropic").values().stream()
                .filter(c -> c.has("request"))
                .map(c -> Arguments.of(c.get("name").stringValue(), c));
    }

    static Stream<Arguments> openaiCases() {
        return ParityFixture.get().get("llm_http").get("openai").values().stream().map(c -> Arguments.of(c.get("name").stringValue(), c));
    }

    private static LocalServer.Canned canned(JsonNode c) {
        Map<String, String> headers = new LinkedHashMap<>();
        c.get("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().stringValue()));
        JsonNode payload = c.get("payload");
        boolean text = c.get("name").stringValue().endsWith("_text") || c.get("name").stringValue().endsWith("_empty");
        return new LocalServer.Canned(c.get("status").intValue(), headers, payload.stringValue(), text ? "text/plain" : "application/json");
    }

    /** 결과 비교: 성공이면 data(json.dumps)·model·request_id·토큰 수, 실패면 예외 종류와 문구. */
    private static void assertResult(JsonNode expected, LLMResult res, Throwable err) {
        if (expected.get("ok").booleanValue()) {
            assertThat(err).isNull();
            assertThat(PyJson.dumps(res.data())).isEqualTo(expected.get("data").stringValue());
            assertThat(res.model()).isEqualTo(expected.get("model").stringValue());
            assertThat(res.requestId()).isEqualTo(expected.get("request_id").isNull() ? null : expected.get("request_id").stringValue());
            JsonNode tokens = expected.get("usage_tokens");
            if (tokens.isNull()) {
                assertThat(res.usage()).isNull();
            } else {
                JsonNode u = res.usage();
                long in = (u.has("input_tokens") ? u.get("input_tokens") : u.get("prompt_tokens")).longValue();
                long out = (u.has("output_tokens") ? u.get("output_tokens") : u.get("completion_tokens")).longValue();
                assertThat(List.of(in, out)).isEqualTo(List.of(tokens.get(0).longValue(), tokens.get(1).longValue()));
            }
        } else {
            assertThat(err).isInstanceOf(LLMError.class);
            assertThat(err.getClass().getSimpleName()).isEqualTo(expected.get("error").stringValue());
            assertThat(err.getMessage()).isEqualTo(expected.get("message").stringValue());
        }
    }

    /** anthropic_client.AnthropicLLM.generate_json — 요청 본문·fallbacks 베타·응답 처리·오류 매핑. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("anthropicCases")
    void anthropic(String name, JsonNode c) {
        server.enqueue(canned(c));
        AnthropicLLM llm = new AnthropicLLM("test-key", "claude-test-1", "high", 30, server.baseUrl(), c.get("fallbacks").stringValue(), 0);
        LLMResult[] res = new LLMResult[1];
        Throwable err = catchThrowable(() -> res[0] = llm.generateJson(Task.EXPLANATION_WRITER, "시스템 프롬프트", USER,
                JsonSchemas.schema("explanation.schema.json"), 4000));
        assertResult(c.get("result"), res[0], err);

        List<LocalServer.Received> got = server.received();
        assertThat(got).hasSize(1);
        LocalServer.Received req = got.get(0);
        JsonNode expectedReq = c.get("request");
        assertThat(req.method()).isEqualTo("POST");
        // 자바 SDK 는 베타 엔드포인트에 ?beta=true 를 붙인다 (파이썬 SDK 는 헤더만). 경로는 같아야 한다.
        assertThat(req.pathAndQuery().replace("?beta=true", "")).isEqualTo(expectedReq.get("path").stringValue());
        assertThat(Json.parse(req.body())).isEqualTo(expectedReq.get("body"));
        JsonNode beta = expectedReq.get("headers").get("anthropic-beta");
        assertThat(req.headers().get("anthropic-beta")).isEqualTo(beta.isNull() ? null : beta.stringValue());
        assertThat(req.headers().get("anthropic-version")).isEqualTo(expectedReq.get("headers").get("anthropic-version").stringValue());
        // 인증: 파이썬 SDK 2.6 은 Authorization: Bearer, 자바 SDK 는 x-api-key — 어느 쪽이든 같은 키
        assertThat(List.of(String.valueOf(req.headers().get("x-api-key")), String.valueOf(req.headers().get("authorization"))))
                .containsAnyOf("test-key", "Bearer test-key");
    }

    /** 연결 실패 → "연결 실패/시간 초과". */
    @Test
    void anthropicConnectionRefused() {
        JsonNode expected = ParityFixture.get().get("llm_http").get("anthropic").values().stream()
                .filter(c -> c.get("name").stringValue().equals("connection_refused")).findFirst().orElseThrow().get("result");
        AnthropicLLM dead = new AnthropicLLM("k", "m", "medium", 5, "http://127.0.0.1:9", "default", 0);
        Throwable err = catchThrowable(() -> dead.generateJson(Task.SEMANTIC_JUDGE, "s", "{}", Json.parse("{\"type\": \"object\"}")));
        assertResult(expected, null, err);
    }

    /** openai_client.OpenAILLM.generate_json — 스키마를 시스템 프롬프트에 붙인 요청과 오류 클래스 이름. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("openaiCases")
    void openai(String name, JsonNode c) {
        server.enqueue(canned(c));
        OpenAILLM llm = new OpenAILLM("test-key", "gpt-test-1", 30, server.baseUrl() + "/v1");
        LLMResult[] res = new LLMResult[1];
        Throwable err = catchThrowable(() -> res[0] = llm.generateJson(Task.RULE_EXTRACTION, "시스템", USER,
                JsonSchemas.schema("explanation.schema.json"), 16000));
        assertResult(c.get("result"), res[0], err);
        LocalServer.Received req = server.received().get(0);
        assertThat(req.pathAndQuery()).isEqualTo(c.get("request").get("path").stringValue());
        assertThat(Json.parse(req.body())).isEqualTo(c.get("request").get("body"));
        assertThat(req.headers().get("authorization")).isEqualTo("Bearer test-key");
    }

    /** 요청 본문 구성 (파이썬과 같은 output_config): fallbacks=off 이면 일반 엔드포인트, 베타 헤더 없음. */
    @Test
    void anthropicOffUsesPlainEndpoint() {
        server.enqueue(new LocalServer.Canned(200, Map.of(), """
                {"id": "m", "type": "message", "role": "assistant", "model": "claude-x", "content": [{"type": "text", "text": "{}"}],
                 "stop_reason": "end_turn", "stop_sequence": null, "usage": {"input_tokens": 1, "output_tokens": 1}}""", "application/json"));
        new AnthropicLLM("k", "claude-x", "low", 10, server.baseUrl(), "off", 0)
                .generateJson(Task.EXPLANATION_WRITER, "s", "{}", Json.parse("{\"type\": \"object\", \"pattern\": \"x\"}"));
        LocalServer.Received req = server.received().get(0);
        assertThat(req.pathAndQuery()).isEqualTo("/v1/messages");
        assertThat(req.headers()).doesNotContainKey("anthropic-beta");
        ObjectNode body = (ObjectNode) Json.parse(req.body());
        assertThat(body.has("fallbacks")).isFalse();
        assertThat(body.get("output_config")).isEqualTo(Json.parse(
                "{\"effort\": \"low\", \"format\": {\"type\": \"json_schema\", \"schema\": {\"type\": \"object\", \"additionalProperties\": false}}}"));
    }
}
