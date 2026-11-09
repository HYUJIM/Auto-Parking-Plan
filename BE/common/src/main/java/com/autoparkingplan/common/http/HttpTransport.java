package com.autoparkingplan.common.http;

import java.io.IOException;
import java.time.Duration;

/**
 * HTTP 전송 (httpx 의 Transport 에 해당). 실제 전송은 {@link JdkHttpTransport}, 테스트는 {@link MockTransport}.
 *
 * <p>오류 구분: 시간 초과는 {@link java.net.http.HttpTimeoutException}, 연결 실패 등은 그 밖의 {@link IOException}.
 * 리다이렉트는 따라가지 않는다 (httpx 기본값과 같음).
 */
@FunctionalInterface
public interface HttpTransport {
    HttpResponse send(HttpRequest request, Duration timeout) throws IOException;
}
