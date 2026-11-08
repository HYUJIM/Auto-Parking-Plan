plugins {
    alias(libs.plugins.spring.boot) apply false
}

val bootBom = libs.spring.boot.bom
val junit = libs.junit.jupiter
val junitLauncher = libs.junit.platform.launcher
val assertj = libs.assertj.core

subprojects {
    apply(plugin = "java-library")
    group = "com.autoparkingplan"
    version = "0.1.0"

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    dependencies {
        // 모든 모듈이 같은 버전(Jackson·JUnit·SLF4J 등)을 쓰도록 Spring Boot BOM 을 공통 적용
        "implementation"(platform(bootBom))
        "testImplementation"(platform(bootBom))
        "annotationProcessor"(platform(bootBom))
        "testImplementation"(junit)
        "testImplementation"(assertj)
        "testRuntimeOnly"(junitLauncher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8" // 윈도우(cp949) 기본 인코딩과 무관하게 한글 소스·문자열 유지
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation,unchecked"))
    }
    tasks.withType<Javadoc>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<ProcessResources>().configureEach { filteringCharset = "UTF-8" }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 테스트 JVM 의 기본 문자셋을 고정 (윈도우 콘솔 cp949 에서도 같은 결과)
        jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Duser.timezone=UTC")
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
