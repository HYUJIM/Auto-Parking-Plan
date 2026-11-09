// DB 엔티티(JPA)·저장소·Flyway 마이그레이션 (H2: 로컬/테스트, PostgreSQL: 운영)
dependencies {
    api(project(":common"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-flyway")
    api(libs.jackson.databind)
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
}
