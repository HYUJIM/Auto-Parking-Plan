package com.autoparkingplan.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.autoparkingplan.server.security.AdminAuthInterceptor;

/** 관리자 경로 인증 인터셉터 등록 + 응답 형식(항상 JSON). */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {
    private final AppSettings settings;
    private final AppConfig.SecurityState sec;

    public WebConfig(AppSettings settings, AppConfig.SecurityState sec) {
        this.settings = settings;
        this.sec = sec;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor(settings, sec)).addPathPatterns("/admin/**");
    }

    /**
     * FastAPI 처럼 Accept 헤더와 무관하게 항상 JSON 으로 답한다. 기본 협상대로면 {@code Accept: text/html} 만 보내는 브라우저 탐색·스캐너에
     * 본문 없는 406 이 나가서 "모든 오류는 같은 JSON 형식" 약속이 깨진다.
     */
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.ignoreAcceptHeader(true).defaultContentType(MediaType.APPLICATION_JSON);
    }
}
