// 주차장 배치 시뮬레이션 엔진 (shapely → JTS, pyproj → proj4j). 순수 자바
dependencies {
    api(project(":common"))
    api(libs.jts.core)
    api(libs.jackson.databind)
    implementation(libs.proj4j)
    implementation(libs.proj4j.epsg)
    implementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
}
