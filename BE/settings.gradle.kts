// APP(Auto Parking Plan) 법규 서버 — Spring Boot 이식판.
// 모듈: 순수 자바 라이브러리(external·legal·parking·ai) + JPA(db) + Spring Boot 앱(server)
plugins {
    // JDK 21 이 없는 PC(윈도우 포함)에서도 빌드되도록 툴체인을 자동으로 내려받는다
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "autoparkingplan"

include("common", "external", "legal", "parking", "ai", "db", "server")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
