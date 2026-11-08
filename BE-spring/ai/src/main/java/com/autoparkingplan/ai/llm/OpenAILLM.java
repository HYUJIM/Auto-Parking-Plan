package com.autoparkingplan.ai.llm;

import java.io.InterruptedIOException;
import java.time.Duration;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.openai.core.Timeout;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import tools.jackson.databind.JsonNode;

/**
 * OpenAI 호환 제공자 (선택). {@code app/llm/openai_client.py}.
 *
 * <p>JSON 객체 모드로 응답을 받고, 스키마는 프롬프트에 함께 넣어 지시한다. 결과는 호출 측이 원래 스키마로 다시 검증한다.
 * 모델 이름은 반드시 설정(LLM_WRITER_MODEL 등)으로 지정한다 (기본값을 추측하지 않음).
 *
 * <p>파이썬 {@code openai.OpenAI(api_key=..., base_url=None)} 처럼 base_url 이 없으면 OPENAI_BASE_URL 환경변수, 그것도 없으면
 * 공식 주소를 쓰고, 조직·프로젝트(OPENAI_ORG_ID·OPENAI_PROJECT_ID)는 환경변수에서 읽는다.
 */
public final class OpenAILLM implements LLMClient {
    static final String SCHEMA_INSTRUCTION = "\n\n출력은 아래 JSON Schema 를 따르는 JSON 객체 하나뿐이다.\n";

    private final OpenAIClient client;
    private final String model;

    public OpenAILLM(String apiKey, String model) {
        this(apiKey, model, 90.0, null);
    }

    public OpenAILLM(String apiKey, String model, double timeoutS, String baseUrl) {
        Duration t = Duration.ofMillis(Math.round(timeoutS * 1000));
        String url = baseUrl != null ? baseUrl : System.getenv("OPENAI_BASE_URL");
        OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Timeout.builder().connect(t).read(t).write(t).request(t).build())
                .maxRetries(2)
                .organization(System.getenv("OPENAI_ORG_ID"))
                .project(System.getenv("OPENAI_PROJECT_ID"));
        if (url != null) {
            b.baseUrl(url);
        }
        this.client = b.build();
        this.model = model;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public LLMResult generateJson(Task task, String system, String user, JsonNode schema, int maxTokens) {
        String sys = system + SCHEMA_INSTRUCTION + PyJson.dumps(ApiSchema.apiSchema(schema));
        ChatCompletion resp;
        try {
            resp = client.chat().completions().create(ChatCompletionCreateParams.builder()
                    .model(model)
                    .addSystemMessage(sys)
                    .addUserMessage(user)
                    .responseFormat(ResponseFormatJsonObject.builder().build())
                    .maxCompletionTokens(maxTokens)
                    .build());
        } catch (OpenAIException e) {
            throw new LLMError(task + ": OpenAI API 오류 " + pythonErrorName(e), e);
        }
        ChatCompletion.Choice choice = resp.choices().get(0);
        if ("length".equals(choice.finishReason().toString())) {
            throw new LLMError(task + ": 출력이 잘림");
        }
        JsonNode data;
        try {
            data = LlmJson.loads(choice.message().content().orElse(""));
        } catch (IllegalArgumentException e) {
            throw new LLMError(task + ": JSON 파싱 실패", e);
        }
        JsonNode usage = resp.usage().map(OpenAILLM::usageJson).orElse(null);
        return new LLMResult(data, resp.model(), usage, null);
    }

    private static JsonNode usageJson(Object usage) {
        try {
            return Json.parse(ObjectMappers.jsonMapper().writeValueAsString(usage));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 파이썬 openai 예외 클래스 이름 ({@code type(e).__name__}). */
    static String pythonErrorName(OpenAIException e) {
        if (e instanceof BadRequestException) {
            return "BadRequestError";
        }
        if (e instanceof UnauthorizedException) {
            return "AuthenticationError";
        }
        if (e instanceof PermissionDeniedException) {
            return "PermissionDeniedError";
        }
        if (e instanceof NotFoundException) {
            return "NotFoundError";
        }
        if (e instanceof UnprocessableEntityException) {
            return "UnprocessableEntityError";
        }
        if (e instanceof RateLimitException) {
            return "RateLimitError";
        }
        if (e instanceof InternalServerException) {
            return "InternalServerError";
        }
        if (e instanceof UnexpectedStatusCodeException u) {
            int code = u.statusCode();
            return code == 409 ? "ConflictError" : code >= 500 ? "InternalServerError" : "APIStatusError";
        }
        if (e instanceof OpenAIIoException) {
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                if (c instanceof InterruptedIOException) { // SocketTimeoutException·OkHttp 호출 시간 초과
                    return "APITimeoutError";
                }
            }
            return "APIConnectionError";
        }
        if (e instanceof OpenAIInvalidDataException) {
            return "APIResponseValidationError";
        }
        return "APIError";
    }
}
