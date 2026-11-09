// 모든 모듈 공용: 파이썬판과 같은 결과를 내기 위한 도우미(JSON 직렬화·문자열·반올림)와 HTTP 전송 추상화. 순수 자바
dependencies {
    api(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
}
