package com.autoparkingplan.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.autoparkingplan.server.security.AdminAuthInterceptor;

/** 관리자 경로 인증 인터셉터 등록. */
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
}
