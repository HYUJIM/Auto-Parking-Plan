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
}
