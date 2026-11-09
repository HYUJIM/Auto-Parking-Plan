plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":external"))
    implementation(project(":legal"))
    implementation(project(":parking"))
    implementation(project(":ai"))
    implementation(project(":db"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3)
    implementation(libs.springdoc.webmvc.ui)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "app.jar"
}
tasks.named<Jar>("jar") { enabled = false }
