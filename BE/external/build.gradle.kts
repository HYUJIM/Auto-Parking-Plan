// 외부 공공 API 클라이언트 (도로명주소·브이월드·건축HUB), PNU·용도지역 코드. 순수 자바 (Spring 없음)
dependencies {
    api(project(":common"))
    api(libs.jackson.databind)
    implementation(libs.slf4j.api)
    // 테스트 로그 출력용 testRuntimeOnly(libs.slf4j.simple) 는 뺐다: 이 PC 의 Gradle 캐시에 jar 가 없어 --offline 빌드가
    // 깨진다. 없으면 SLF4J 가 NOP 로거를 쓴다(테스트 결과와 무관). 온라인에서 한 번 받으면 다시 넣어도 된다.
}
