package com.autoparkingplan.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDK 내장 HttpClient 로 보내는 기본 전송. 스레드 안전 (여러 요청이 하나를 같이 쓴다).
 *
 * <p>요청 URL(인증키 포함)을 로그에 남기지 않는다. 예외 메시지에도 URL 을 싣지 않는다.
 *
 * <p>원천(공공 API·law.go.kr·LLM 게이트웨이)이 잘못되거나 가로채였을 때를 대비한 상한 두 가지:
 * <ul>
 *   <li>본문 크기 — {@code maxBodyBytes}(기본 32MB) 를 넘는 응답은 받다가 끊고 IOException (JDK 의 ofByteArray 는 상한이 없다)</li>
 *   <li>전체 시간 — {@code timeout} 은 JDK 에서 응답 헤더까지의 제한이라 본문을 천천히 흘려보내는 원천이 요청 스레드를 붙잡을 수 있다
 *       → 헤더 + 본문을 {@code timeout} 의 2배 안에 다 받아야 하고, 넘으면 {@link HttpTimeoutException}</li>
 * </ul>
 */
public final class JdkHttpTransport implements HttpTransport {
    /** 응답 본문 상한 기본값. 법령 XML·HTML 은 수 MB, 별표 PDF 도 수 MB 다. */
    public static final long DEFAULT_MAX_BODY_BYTES = 32L * 1024 * 1024;

    private final HttpClient client;
    private final long maxBodyBytes;

    public JdkHttpTransport(Duration connectTimeout) {
        this(connectTimeout, DEFAULT_MAX_BODY_BYTES);
    }

    public JdkHttpTransport(Duration connectTimeout, long maxBodyBytes) {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes 는 0보다 커야 합니다");
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.maxBodyBytes = maxBodyBytes;
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
        CompletableFuture<java.net.http.HttpResponse<byte[]>> future = client.sendAsync(b.build(), limited(maxBodyBytes));
        try {
            java.net.http.HttpResponse<byte[]> r = future.get(Math.max(1L, timeout.toMillis() * 2), TimeUnit.MILLISECONDS);
            return new HttpResponse(r.statusCode(), r.headers().map(), r.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("응답을 제한 시간 안에 다 받지 못했습니다");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName(), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("요청이 중단되었습니다");
        }
    }

    /** 본문을 상한까지만 모으는 BodyHandler. Content-Length 가 상한을 넘으면 받지 않고 바로 실패한다. */
    static BodyHandler<byte[]> limited(long max) {
        return info -> new LimitedBodySubscriber(max, info.headers().firstValueAsLong("content-length").orElse(-1L) > max);
    }

    static final class LimitedBodySubscriber implements BodySubscriber<byte[]> {
        private final long max;
        private final boolean rejectUpfront;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBodySubscriber(long max, boolean rejectUpfront) {
            this.max = max;
            this.rejectUpfront = rejectUpfront;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (rejectUpfront) {
                tooLarge();
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        private void tooLarge() {
            subscription.cancel();
            result.completeExceptionally(new IOException("응답 본문이 너무 큽니다 (최대 " + max + " 바이트)"));
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer bb : items) {
                int n = bb.remaining();
                if ((long) buf.size() + n > max) {
                    tooLarge();
                    return;
                }
                byte[] chunk = new byte[n];
                bb.get(chunk);
                buf.write(chunk, 0, n);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            if (!result.isDone()) {
                result.complete(buf.toByteArray());
            }
        }
    }
}
