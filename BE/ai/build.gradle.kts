// 프론티어 LLM 호출(Anthropic·OpenAI·fake)과 AI 출력 사후 검증기. 순수 자바
dependencies {
    api(project(":common"))
    api(libs.jackson.databind)
    // JSON Schema 검증은 파이썬 jsonschema 와 같은 오류 문구·순서·format 범위가 필요해 직접 구현했다
    // (com.autoparkingplan.ai.schema.JsonSchemaValidator) — networknt json-schema-validator 는 쓰지 않는다
    implementation(libs.anthropic.java)
    implementation(libs.openai.java)
    implementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
    constraints {
        // openai-java 가 끌어오는 opentelemetry-api 1.62.0 (Spring Boot BOM 기준) 에는 CVE-2026-118259 (W3C tracestate 해석 시 CPU 소모,
        // GHSA-ccrj-gw8r-854m) 가 있어 수정판으로 올린다. 추적(OTel SDK)은 쓰지 않으므로 동작 변화는 없다
        implementation("io.opentelemetry:opentelemetry-api:1.68.0")
    }
}
