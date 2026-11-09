package com.autoparkingplan.ai.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/** 스키마·프롬프트 자원과 검증기 조립 (파이썬 {@code app/resources/{schemas,prompts}}, {@code review/service.review_result_validator}). */
class JsonSchemasTest {
    private static byte[] resource(String path) throws IOException {
        try (InputStream in = JsonSchemas.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }

    /** 스키마·프롬프트 자원 9개가 클래스패스에 있다 (이식 전 파이썬판 app/resources 와 바이트 단위로 같았던 파일들). */
    @Test
    void resourcesArePresent() throws IOException {
        for (String name : JsonSchemas.SCHEMA_FILES) {
            assertThat(resource("/schemas/" + name)).isNotEmpty();
        }
        for (String name : java.util.List.of("explanation_prompt.md", "judge_prompt.md", "rule_extraction_prompt.md")) {
            assertThat(resource("/prompts/" + name)).isNotEmpty();
        }
        assertThat(JsonSchemas.SCHEMA_FILES).hasSize(6);
    }

    @Test
    void schemasPromptsAndRegistry() {
        for (String name : JsonSchemas.SCHEMA_FILES) {
            JsonNode s = JsonSchemas.schema(name);
            assertThat(JsonSchemas.registry().get(s.get("$id").stringValue())).isSameAs(s);
        }
        assertThat(JsonSchemas.prompt("explanation_prompt.md")).isNotBlank();
        assertThat(JsonSchemas.prompt("judge_prompt.md")).isNotBlank();
        assertThat(JsonSchemas.prompt("rule_extraction_prompt.md")).isNotBlank();
        assertThat(JsonSchemas.validator("review_result.schema.json")).isSameAs(JsonSchemas.validator("review_result.schema.json"));
    }

    /** review_result 의 다른 문서 참조($ref → explanation.schema.json)를 레지스트리로 따라간다. */
    @Test
    void externalRefResolvedThroughRegistry() {
        JsonNode content = Json.parse("""
                {"schema_version": "explanation@2.0", "category": "BUILDING_COVERAGE", "audience": "owner",
                 "sentences": [{"sid": "BAD", "role": "headline", "text": "x", "fact_ids": []}]}""");
        JsonSchemaValidator v = JsonSchemas.validator("review_result.schema.json");
        JsonNode itemExplanation = Json.parse("{\"oneOf\": [{\"type\": \"null\"}, {\"$ref\": \"https://app.local/schemas/explanation.schema.json\"}]}");
        JsonSchemaValidator sub = new JsonSchemaValidator(itemExplanation, JsonSchemas.registry(), true);
        assertThat(sub.isValid(Json.parse("null"))).isTrue();
        assertThat(sub.isValid(content)).isFalse();
        assertThat(v.registeredIds()).contains("https://app.local/schemas/explanation.schema.json");
    }

    /** format: uuid·date 는 검사하고 date-time·uri 는 파이썬판처럼 검사하지 않는다. */
    @Test
    void formatCheckingMatchesPythonInstall() {
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"123e4567-e89b-12d3-a456-426614174000\""), "uuid")).isTrue();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"123e4567e89b12d3a456426614174000\""), "uuid")).isFalse();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"2024-02-29\""), "date")).isTrue();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"2023-02-29\""), "date")).isFalse();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"아무거나\""), "date-time")).isTrue();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"아무거나\""), "uri")).isTrue();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"a@b\""), "email")).isTrue();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"192.168.0.1\""), "ipv4")).isTrue();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("\"192.168.0.01\""), "ipv4")).isFalse();
        assertThat(JsonSchemaValidator.checkFormat(Json.parse("12"), "uuid")).isTrue();
    }
}
