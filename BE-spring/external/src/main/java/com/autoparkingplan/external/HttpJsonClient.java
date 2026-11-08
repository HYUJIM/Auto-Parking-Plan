package com.autoparkingplan.external;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.HttpTransport;
import com.autoparkingplan.common.http.JdkHttpTransport;
import com.autoparkingplan.common.http.Urls;

import tools.jackson.databind.JsonNode;

/**
 * 서비스 하나(juso/vworld/building_hub)에 대한 GET+JSON 호출기 (파이썬 {@code app.external._http.HttpJsonClient}).
 *
 * <p>전송은 {@link HttpTransport} 로 보낸다 (파이썬 httpx transport 주입에 해당, 테스트는 {@code MockTransport}).
 * 주지 않으면 연결·응답 시간 제한이 모두 {@code timeout} 초인 {@link JdkHttpTransport} 를 만든다.
 * 오류 구분: {@link HttpTimeoutException} = 시간 초과, 그 밖의 {@link IOException} = 연결 오류 (httpx TransportError).
 *
 * <p>스레드 안전 (상태 없음). 여러 스레드가 같은 클라이언트로 동시에 호출해도 된다.
 */
public final class HttpJsonClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HttpJsonClient.class);

    /** {@link #getJson} 결과: (HTTP 상태코드, 본문 JSON). */
    public record JsonResult(int status, JsonNode payload) {
    }

    private final String service;
    private final double timeout;
    private final int retries;
    private final List<String> secrets;
    private final HttpTransport transport;
    private final Duration timeoutDuration;

    /**
     * @param transport null 이면 기본 {@link JdkHttpTransport}
     * @param secrets   예외 메시지에서 가릴 비밀값 (빈 값·null 은 무시)
     * @throws IllegalArgumentException timeout 이 0 이하
     */
    public HttpJsonClient(String service, double timeout, HttpTransport transport, int retries, Iterable<String> secrets) {
        if (timeout <= 0 || Double.isNaN(timeout)) {
            throw new IllegalArgumentException("timeout 은 0보다 커야 합니다");
        }
        this.service = service;
        this.timeout = timeout;
        this.retries = Math.max(0, retries);
        List<String> s = new ArrayList<>();
        if (secrets != null) {
            for (String x : secrets) {
                if (x != null && !x.isEmpty()) {
                    s.add(x);
                }
            }
        }
        this.secrets = List.copyOf(s);
        this.timeoutDuration = Duration.ofNanos(Math.round(timeout * 1_000_000_000d));
        this.transport = transport != null ? transport : new JdkHttpTransport(timeoutDuration);
    }

    public HttpJsonClient(String service, double timeout, HttpTransport transport, Iterable<String> secrets) {
        this(service, timeout, transport, Http.DEFAULT_RETRIES, secrets);
    }

    public HttpJsonClient(String service) {
        this(service, Http.DEFAULT_TIMEOUT, null, Http.DEFAULT_RETRIES, List.of());
    }

    public String service() {
        return service;
    }

    /** 시간 제한(초). */
    public double timeout() {
        return timeout;
    }

    public int retries() {
        return retries;
    }

    // --- 수명 관리 ---------------------------------------------------------------------------

    /** 파이썬판은 자체 생성한 httpx.Client 를 닫는다. JDK 전송은 닫을 자원이 없어 아무것도 하지 않는다. */
    @Override
    public void close() {
        // 닫을 자원 없음
    }

    /** 원천이 돌려준 메시지에서도 인증키를 가린다. */
    public String scrub(String text) {
        return Http.redactSecrets(String.valueOf(text), secrets);
    }

    // --- 호출 --------------------------------------------------------------------------------

    /**
     * GET 1회 + (시간 초과·연결 오류·5xx 이면) 재시도. 5xx 가 끝까지 나면 UpstreamError.
     *
     * <p>4xx 는 응답을 그대로 돌려준다(공공데이터포털처럼 4xx 본문에 오류 코드를 담는 API 가 있어서).
     *
     * @param params 질의 파라미터 (순서 유지; 값은 {@code String.valueOf}, null 은 뺀다)
     */
    public HttpResponse get(String url, Map<String, ?> params, String endpoint) {
        int attempts = 1 + retries;
        HttpRequest request = HttpRequest.get(Urls.withQuery(url, params));
        UpstreamError lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse resp = transport.send(request, timeoutDuration);
                if (resp.status() >= 500) {
                    lastError = new UpstreamError(service, endpoint + " 서버 오류(HTTP " + resp.status() + ")", true,
                            resp.status());
                } else {
                    return resp;
                }
            } catch (HttpTimeoutException exc) {
                lastError = new UpstreamError(service,
                        endpoint + " 요청 시간 초과(" + Py.formatG(timeout) + "초, " + exc.getClass().getSimpleName() + ")", true);
            } catch (IOException exc) {
                lastError = new UpstreamError(service, endpoint + " 연결 오류(" + exc.getClass().getSimpleName() + ")", true);
            }
            if (attempt < attempts) {
                log.warn("[{}] {} 재시도 {}/{}: {}", service, endpoint, attempt, retries, lastError.message());
            }
        }
        throw lastError;
    }

    /** GET 후 JSON 해석. (HTTP 상태코드, 본문) 을 돌려준다. JSON 이 아니면 UpstreamError. */
    public JsonResult getJson(String url, Map<String, ?> params, String endpoint) {
        HttpResponse resp = get(url, params, endpoint);
        return new JsonResult(resp.status(), decodeJson(resp, endpoint));
    }

    /**
     * 본문 JSON 해석 (UTF-8/16/32 자동 판별). 바이트 해석이 안 되면 응답 charset(없으면 UTF-8, 깨진 바이트는 대체 문자)으로
     * 문자열을 만들어 다시 해석한다. 그래도 JSON 이 아니면(빈 본문 포함) UpstreamError.
     */
    public JsonNode decodeJson(HttpResponse resp, String endpoint) {
        JsonNode node = tryParse(resp.body());
        if (node == null) {
            Charset cs = resp.charset();
            node = tryParse(new String(resp.body(), cs == null ? StandardCharsets.UTF_8 : cs));
        }
        if (node == null) {
            throw new UpstreamError(service, endpoint + " 응답이 JSON 이 아닙니다(HTTP " + resp.status() + ")", resp.status() >= 500,
                    resp.status());
        }
        return node;
    }

    private static JsonNode tryParse(byte[] body) {
        try {
            JsonNode n = Json.parse(body);
            return n == null || n.isMissingNode() ? null : n;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonNode tryParse(String text) {
        try {
            JsonNode n = Json.parse(text);
            return n == null || n.isMissingNode() ? null : n;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
