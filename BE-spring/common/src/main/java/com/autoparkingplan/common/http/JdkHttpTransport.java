package com.autoparkingplan.common.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * JDK 내장 HttpClient 로 보내는 기본 전송. 스레드 안전 (여러 요청이 하나를 같이 쓴다).
 *
 * <p>요청 URL(인증키 포함)을 로그에 남기지 않는다. 예외 메시지에도 URL 을 싣지 않는다.
 */
public final class JdkHttpTransport implements HttpTransport {
    private final HttpClient client;

    public JdkHttpTransport(Duration connectTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public JdkHttpTransport() {
        this(Duration.ofSeconds(10));
    }

    @Override
    public HttpResponse send(HttpRequest request, Duration timeout) throws IOException {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(request.uri()).timeout(timeout);
        request.headers().forEach(b::header);
        if (request.body().length > 0 || !"GET".equals(request.method())) {
            b.method(request.method(), java.net.http.HttpRequest.BodyPublishers.ofByteArray(request.body()));
        } else {
            b.GET();
        }
        try {
            java.net.http.HttpResponse<byte[]> r = client.send(b.build(), BodyHandlers.ofByteArray());
            return new HttpResponse(r.statusCode(), r.headers().map(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("요청이 중단되었습니다");
        }
    }
}
