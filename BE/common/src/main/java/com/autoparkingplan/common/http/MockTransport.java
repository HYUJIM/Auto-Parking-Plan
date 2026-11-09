package com.autoparkingplan.common.http;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 테스트용 전송 (httpx.MockTransport + 파이썬 테스트의 Recorder 에 해당).
 *
 * <p>준비한 결과를 순서대로 돌려주고(마지막 결과는 반복) 받은 요청을 기록한다. 결과로 넣을 수 있는 것:
 * {@link HttpResponse}, {@link IOException}(던짐), {@link Handler}(요청을 보고 응답), 그 밖의 객체(200 JSON 응답).
 */
public final class MockTransport implements HttpTransport {
    @FunctionalInterface
    public interface Handler {
        HttpResponse handle(HttpRequest request) throws IOException;
    }

    private final List<Object> outcomes;
    private final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Duration> timeouts = Collections.synchronizedList(new ArrayList<>());

    public MockTransport(Object... outcomes) {
        if (outcomes.length == 0) {
            throw new IllegalArgumentException("결과를 하나 이상 주어야 합니다");
        }
        this.outcomes = List.of(outcomes);
    }

    public static MockTransport handler(Handler h) {
        return new MockTransport(h);
    }

    @Override
    public HttpResponse send(HttpRequest request, Duration timeout) throws IOException {
        int idx;
        synchronized (requests) {
            requests.add(request);
            timeouts.add(timeout);
            idx = Math.min(requests.size() - 1, outcomes.size() - 1);
        }
        Object out = outcomes.get(idx);
        if (out instanceof Handler h) {
            return h.handle(request);
        }
        if (out instanceof IOException e) {
            throw e;
        }
        if (out instanceof RuntimeException e) {
            throw e;
        }
        if (out instanceof HttpResponse r) {
            return r;
        }
        return HttpResponse.json(200, out);
    }

    public List<HttpRequest> requests() {
        return List.copyOf(requests);
    }

    public HttpRequest lastRequest() {
        synchronized (requests) {
            return requests.getLast();
        }
    }

    public int count() {
        return requests.size();
    }

    public List<Duration> timeouts() {
        return List.copyOf(timeouts);
    }
}
