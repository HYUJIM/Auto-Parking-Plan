package com.autoparkingplan.server.config;

import jakarta.persistence.EntityManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.autoparkingplan.db.RuleQueries;
import com.autoparkingplan.server.explain.ExplanationService;
import com.autoparkingplan.server.parcels.ParcelService;
import com.autoparkingplan.server.parking.ParkingBridge;
import com.autoparkingplan.server.review.ReviewService;

/** 서비스 조립 (파이썬 app.container.build_container). API·워커·CLI 공통. */
@Configuration(proxyBeanMethods = false)
public class ServicesConfig {

    @Bean
    public Llms llms(AppSettings s) {
        Llms l = Llms.build(s);
        if (l.writer() != null && l.judge() != null && l.writer().provider().equals(l.judge().provider())
                && l.writer().model().equals(l.judge().model())) {
            org.slf4j.LoggerFactory.getLogger(ServicesConfig.class).warn("판정 모델이 작성 모델과 같습니다. 설계서 9장은 분리된 판정 모델을 권장합니다.");
        }
        return l;
    }

    @Bean(destroyMethod = "close")
    public ParcelService parcelService(AppSettings s, ExternalClients ext) {
        return new ParcelService(ext.juso(), ext.vworld(), ext.building(), s.parcelCacheDays, 5, !AppConfig.officialHosts(s),
                s.parcelRefreshMinIntervalS);
    }

    @Bean
    public ExplanationService explanationService(Llms llms, AppSettings s) {
        return new ExplanationService(llms.writer(), llms.judge(), s.explanationMaxAttempts);
    }

    @Bean(destroyMethod = "close")
    public ReviewService reviewService(EntityManager em, PlatformTransactionManager tm, RuleQueries q, ParcelService parcels,
            ExplanationService explainer, AppSettings s) {
        return new ReviewService(em, tm, q, parcels, explainer, s.reviewExecutor, s.reviewWorkers, s.timezone, s.maxPendingReviews);
    }

    @Bean
    public ParkingBridge parkingBridge() {
        return new ParkingBridge();
    }
}
