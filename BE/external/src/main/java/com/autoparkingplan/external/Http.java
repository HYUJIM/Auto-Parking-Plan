package com.autoparkingplan.external;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.http.Redact;
import com.autoparkingplan.common.http.Urls;

import tools.jackson.databind.JsonNode;

/**
 * 외부 API 공통 HTTP 처리의 모듈 함수·상수 (파이썬 {@code app.external._http}). 호출기는 {@link HttpJsonClient}.
 *
 * <ul>
 *   <li>기본 시간 제한 3.0초 (프로토타입 API 명세).</li>
 *   <li>GET 은 시간 초과·연결 오류·5xx 에 한해 1회 재시도하고, 그래도 실패하면 {@link UpstreamError}.</li>
 *   <li>인증키는 예외 메시지·로그에 절대 넣지 않는다. 파이썬판은 httpx 자체 로거("httpx")가 INFO 로 남기는 요청 URL 에서도
 *       key·confmKey·serviceKey·OC 값을 가리는 필터를 설치했다. 자바판의 전송({@code JdkHttpTransport})은 요청 URL 을
 *       로그·예외 메시지에 남기지 않으므로 그런 필터가 필요 없다.</li>
 * </ul>
 *
 * <p>값 변환 함수({@link #toFloat}·{@link #cleanStr} 등)는 원천 JSON 값({@link JsonNode})이나 자바 값(String·Number·Boolean·null)을
 * 받아 파이썬 {@code str()}·{@code float()} 와 같은 결과를 낸다.
 */
public final class Http {
    public static final double DEFAULT_TIMEOUT = 3.0;
    public static final int DEFAULT_RETRIES = 1;

    private Http() {
    }

    /** 파이썬 {@code redact_secrets}: 문자열에서 인증키 파라미터 값과 주어진 비밀값을 '***' 로 가린다. */
    public static String redactSecrets(String text, Iterable<String> secrets) {
        return Redact.secrets(text, secrets);
    }

    public static String redactSecrets(String text) {
        return Redact.secrets(text);
    }

    /** 현재 시각 (UTC, 파이썬 {@code datetime.now(UTC)} 와 같은 마이크로초 정밀도). */
    public static OffsetDateTime utcnow() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** {@code base_url.rstrip("/") + "/" + path.lstrip("/")}. */
    public static String joinUrl(String baseUrl, String path) {
        return Urls.join(baseUrl, path);
    }

    /** '133361.6' / 133361.6 → double. 빈 값·숫자 아님 → null. (불리언은 숫자로 보지 않는다.) */
    public static Double toFloat(Object value) {
        if (PyStr.isNone(value)) {
            return null;
        }
        if (value instanceof Boolean || (value instanceof JsonNode n && n.isBoolean())) {
            return null;
        }
        if (value instanceof JsonNode n && n.isNumber()) {
            return n.doubleValue();
        }
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        String s = Py.strip(PyStr.str(value)).replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        return PyStr.parseFloat(s);
    }

    /** 0 이하(원천의 '값 없음' 표기로 보임)는 null. None = 미확인 원칙. */
    public static Double toPositiveFloat(Object value) {
        Double v = toFloat(value);
        return v != null && v > 0 ? v : null;
    }

    /**
     * 0 이하·숫자 아님은 null, 그 밖에는 파이썬 {@code int(round(v))} (은행원 반올림, 0.4 → 0).
     *
     * @throws ArithmeticException 값이 inf·nan 일 때 (파이썬도 OverflowError/ValueError)
     */
    public static Long toPositiveInt(Object value) {
        Double v = toFloat(value);
        if (v == null || v <= 0) {
            return null;
        }
        return Py.round(v);
    }

    /** 파이썬 {@code str(value).strip()}, 비면 null. */
    public static String cleanStr(Object value) {
        if (PyStr.isNone(value)) {
            return null;
        }
        String s = Py.strip(PyStr.str(value));
        return s.isEmpty() ? null : s;
    }
}
