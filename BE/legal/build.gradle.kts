// 국가법령정보(law.go.kr) 원문 수집·해석: DRF(XML) 클라이언트, 화면 HTML 파서, 조문 구조. 순수 자바
dependencies {
    api(project(":common"))
    api(libs.jackson.databind)
    implementation(libs.jsoup)
    implementation(libs.pdfbox)
    implementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
}
