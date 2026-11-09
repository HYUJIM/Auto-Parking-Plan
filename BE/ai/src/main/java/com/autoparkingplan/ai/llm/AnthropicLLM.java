package com.autoparkingplan.ai.llm;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonString;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.Timeout;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.autoparkingplan.ai.pycompat.PyRepr;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * Claude (Anthropic API) 구현 — 구조화 출력(output_config.format = json_schema) 사용. {@code app/llm/anthropic_client.py}.
 *
 * <p>요청: model, max_tokens, system, user 메시지 하나, {@code output_config = {effort, format: {type: json_schema, schema: api_schema(schema)}}}.
 * fallbacks="default" 이면 베타 엔드포인트에 {@code anthropic-beta: server-side-fallback-2026-07-01} 과 본문 {@code "fallbacks": "default"} 를
 * 붙여, 안전 분류기 거절 시 서버가 권장 대체 모델로 같은 요청을 다시 실행하게 한다.
 */
public final class AnthropicLLM implements LLMClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicLLM.class);
    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final String effort;
    private final String fallbacks;

    public AnthropicLLM(String apiKey, String model) {
        this(apiKey, model, "medium", 90.0, "https://api.anthropic.com", "default", 2);
    }

    /**
     * @param baseUrl    명시해서 환경변수(ANTHROPIC_BASE_URL)에 의한 의도치 않은 우회를 막는다 (환경변수를 읽지 않는다)
     * @param fallbacks  "default" 면 서버 쪽 대체 모델 실행, 그 밖("off")이면 일반 엔드포인트
     * @param maxRetries 연결 오류·408·409·429·5xx 재시도 횟수
     */
    public AnthropicLLM(String apiKey, String model, String effort, double timeoutS, String baseUrl, String fallbacks, int maxRetries) {
        Duration t = Duration.ofMillis(Math.round(timeoutS * 1000));
        // 파이썬 httpx timeout=90 은 연결·읽기·쓰기 각각 90초 (전체 상한 없음). 여기서는 같은 값을 각 단계와 요청 전체에 건다.
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(Timeout.builder().connect(t).read(t).write(t).request(t).build())
                .maxRetries(maxRetries)
                .build();
        this.model = model;
        this.effort = effort;
        this.fallbacks = fallbacks;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    public String effort() {
        return effort;
    }

    public String fallbacks() {
        return fallbacks;
    }

    /** 응답에서 꺼낸 값 (비베타·베타 공통). */
    private record Raw(String stopReason, Optional<String> firstText, String model, JsonNode usage, String requestId) {
    }

    @Override
    public LLMResult generateJson(Task task, String system, String user, JsonNode schema, int maxTokens) {
        Map<String, JsonValue> schemaProps = sdkSchema(ApiSchema.apiSchema(schema));
        Raw resp;
        try {
            if ("default".equals(fallbacks)) {
                // 안전 분류기 거절 시 서버가 권장 대체 모델로 같은 요청을 다시 실행 (fallbacks: "default")
                var params = com.anthropic.models.beta.messages.MessageCreateParams.builder()
                        .addBeta(AnthropicBeta.of(FALLBACK_BETA))
                        .model(model)
                        .maxTokens(maxTokens)
                        .system(system)
                        .addUserMessage(user)
                        .outputConfig(BetaOutputConfig.builder()
                                .effort(BetaOutputConfig.Effort.of(effort))
                                .format(BetaJsonOutputFormat.builder()
                                        .schema(BetaJsonOutputFormat.Schema.builder().additionalProperties(schemaProps).build())
                                        .build())
                                .build())
                        .fallbacks(BetaFallbacksParam.ofDefault())
                        .build();
                try (HttpResponseFor<BetaMessage> raw = client.beta().messages().withRawResponse().create(params)) {
                    BetaMessage m = raw.parse();
                    resp = new Raw(m.stopReason().map(Object::toString).orElse(null),
                            m.content().stream().filter(BetaContentBlock::isText).map(b -> b.asText().text()).findFirst(),
                            m.model().asString(), usageJson(m.usage()), raw.requestId().orElse(null));
                }
            } else {
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .system(system)
                        .addUserMessage(user)
                        .outputConfig(OutputConfig.builder()
                                .effort(OutputConfig.Effort.of(effort))
                                .format(JsonOutputFormat.builder()
                                        .schema(JsonOutputFormat.Schema.builder().additionalProperties(schemaProps).build())
                                        .build())
                                .build())
                        .build();
                try (HttpResponseFor<Message> raw = client.messages().withRawResponse().create(params)) {
                    Message m = raw.parse();
                    resp = new Raw(m.stopReason().map(Object::toString).orElse(null),
                            m.content().stream().filter(ContentBlock::isText).map(b -> b.asText().text()).findFirst(),
                            m.model().asString(), usageJson(m.usage()), raw.requestId().orElse(null));
                }
            }
        } catch (UnauthorizedException e) {
            throw new LLMError(task + ": 인증 실패 (API 키 확인)", e);
        } catch (RateLimitException e) {
            throw new LLMError(task + ": 호출 한도 초과", e);
        } catch (BadRequestException e) {
            throw new LLMError(task + ": 잘못된 요청 " + statusErrorMessage(e.statusCode(), e.body()), e);
        } catch (AnthropicServiceException e) {
            throw new LLMError(task + ": API 오류 " + e.statusCode(), e);
        } catch (AnthropicIoException e) { // 시간 초과 포함
            throw new LLMError(task + ": 연결 실패/시간 초과", e);
        }

        if ("refusal".equals(resp.stopReason())) {
            throw new LLMRefusal(task + ": 모델이 요청을 거절함");
        }
        if ("max_tokens".equals(resp.stopReason())) {
            throw new LLMError(task + ": 출력이 max_tokens 에서 잘림");
        }
        if (resp.firstText().isEmpty()) {
            throw new LLMError(task + ": 텍스트 응답 없음");
        }
        JsonNode data;
        try {
            data = LlmJson.loads(resp.firstText().get());
        } catch (IllegalArgumentException e) {
            throw new LLMError(task + ": JSON 파싱 실패", e);
        }
        if (!data.isObject()) {
            throw new LLMError(task + ": 최상위가 객체가 아님");
        }
        log.debug("{} 응답: model={} request_id={}", task, resp.model(), resp.requestId());
        return new LLMResult(data, resp.model(), resp.usage(), resp.requestId());
    }

    /** api_schema 결과(객체)를 SDK 의 JsonOutputFormat.Schema 속성 맵으로 (키 순서 유지). */
    static Map<String, JsonValue> sdkSchema(JsonNode apiSchema) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : apiSchema.properties()) {
            m.put(e.getKey(), JsonValue.from(Json.toJava(e.getValue())));
        }
        return m;
    }

    private static JsonNode usageJson(Object usage) {
        if (usage == null) {
            return null;
        }
        try {
            return Json.parse(ObjectMappers.jsonMapper().writeValueAsString(usage));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 파이썬 SDK 의 상태 오류 문구 ({@code e.message}): 본문이 JSON 이면 {@code "Error code: 400 - " + repr(본문)},
     * 아니면 본문 텍스트(앞뒤 공백 제거), 비어 있으면 {@code "Error code: 400"}.
     */
    static String statusErrorMessage(int statusCode, JsonValue body) {
        if (body == null || body.isMissing()) {
            return "Error code: " + statusCode;
        }
        if (body instanceof JsonString js) { // JSON 이 아닌 본문 (게이트웨이 오류 문구 등)
            String t = Py.strip(js.value());
            return t.isEmpty() ? "Error code: " + statusCode : t;
        }
        try {
            JsonNode parsed = Json.parse(ObjectMappers.jsonMapper().writeValueAsString(body));
            return "Error code: " + statusCode + " - " + PyRepr.repr(parsed);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "Error code: " + statusCode;
        }
    }
}
