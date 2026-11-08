package com.autoparkingplan.ai.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/**
 * 출력 스키마({@code /schemas/*.schema.json})와 프롬프트({@code /prompts/*.md}) 자원, 그리고 검증기.
 *
 * <p>파이썬판의 {@code app/resources/schemas}·{@code app/resources/prompts} 와 바이트 단위로 같은 파일이다.
 * 검증기는 파이썬 {@code Draft202012Validator(schema, registry=<모든 *.schema.json 을 $id 로 등록>, format_checker=FORMAT_CHECKER)}
 * 와 같다 ({@code review/service.py} · {@code verification/ai_output.py}). 사후 검증기({@code post_verifier._schema_errors})는
 * 레지스트리 없이 만들지만 그 스키마들(explanation·judge_verdict)에는 다른 문서 참조가 없어 결과가 같다.
 *
 * <p>파이썬은 디렉터리를 glob 하지만 클래스패스(jar 안)는 나열할 수 없어 파일 이름을 {@link #SCHEMA_FILES} 에 고정한다.
 */
public final class JsonSchemas {
    /** {@code app/resources/schemas/*.schema.json} 전체 (파일을 추가하면 여기에도 추가한다). */
    public static final List<String> SCHEMA_FILES = List.of(
            "explanation.schema.json", "fact_ledger.schema.json", "judge_verdict.schema.json",
            "review_result.schema.json", "rule_extraction.schema.json", "verification_report.schema.json");

    private static final Map<String, JsonNode> SCHEMAS = new ConcurrentHashMap<>();
    private static final Map<String, String> PROMPTS = new ConcurrentHashMap<>();
    private static final Map<String, JsonSchemaValidator> VALIDATORS = new ConcurrentHashMap<>();
    private static volatile Map<String, JsonNode> registry;

    private JsonSchemas() {
    }

    /**
     * 스키마 JSON (파이썬 {@code json.loads((SCHEMA_DIR / name).read_text())}). 캐시된 같은 객체를 돌려주므로 고치지 말 것
     * (고칠 일이 있으면 {@code deepCopy()}).
     */
    public static JsonNode schema(String name) {
        return SCHEMAS.computeIfAbsent(name, n -> Json.resource(JsonSchemas.class, "/schemas/" + n));
    }

    /** 프롬프트 원문 (UTF-8 그대로). */
    public static String prompt(String name) {
        return PROMPTS.computeIfAbsent(name, n -> Json.resourceText(JsonSchemas.class, "/prompts/" + n));
    }

    /** {@code $id} → 스키마 (파이썬 referencing Registry). */
    public static Map<String, JsonNode> registry() {
        Map<String, JsonNode> r = registry;
        if (r == null) {
            Map<String, JsonNode> m = new LinkedHashMap<>();
            for (String f : SCHEMA_FILES) {
                JsonNode doc = schema(f);
                m.put(doc.get("$id").stringValue(), doc);
            }
            r = Collections.unmodifiableMap(m);
            registry = r;
        }
        return r;
    }

    /** 이름의 스키마로 만든 검증기 (레지스트리 + format 검사). 스레드 안전, 캐시됨. */
    public static JsonSchemaValidator validator(String name) {
        return VALIDATORS.computeIfAbsent(name, n -> new JsonSchemaValidator(schema(n), registry(), true));
    }

    /** {@code list(validator.iter_errors(instance))} (파이썬과 같은 순서). */
    public static List<SchemaError> errors(JsonNode instance, String name) {
        return validator(name).iterErrors(instance);
    }

    /** {@code sorted(validator.iter_errors(instance), key=lambda e: list(e.absolute_path))}. */
    public static List<SchemaError> sortedErrors(JsonNode instance, String name) {
        return JsonSchemaValidator.sortedByPath(errors(instance, name));
    }

    public static boolean isValid(JsonNode instance, String name) {
        return validator(name).isValid(instance);
    }
}
