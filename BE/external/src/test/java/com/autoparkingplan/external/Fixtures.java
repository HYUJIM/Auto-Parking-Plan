package com.autoparkingplan.external;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * tests/external 공통 도우미 (파이썬 {@code tests/external/conftest.py}): 응답 fixture 로더, 모의 전송.
 *
 * <p>파이썬의 Recorder + httpx.MockTransport 는 {@link MockTransport}(common) 가 대신한다: 결과를 순서대로 돌려주고
 * (마지막 결과 반복) 요청을 기록한다. mock 서버(TestClient/uvicorn) 도우미는 종단 시험(test_mockserver_e2e)용이라 옮기지 않았다.
 */
final class Fixtures {
    private Fixtures() {
    }

    /** fixtures/external/&lt;name&gt;. observed_* = 2026-11-06 실호출 응답, synthetic_* = 공식 필드표로 만든 가상 응답. */
    static ObjectNode load(String name) {
        return (ObjectNode) Json.resource(Fixtures.class, "/fixtures/external/" + name);
    }

    /** {@code mock_http(outcome1, outcome2, ...)} → 모의 전송. */
    static MockTransport mockHttp(Object... outcomes) {
        return new MockTransport(outcomes);
    }

    static HttpResponse jsonResponse(JsonNode payload, int status) {
        return HttpResponse.json(status, payload);
    }

    static HttpResponse jsonResponse(String json, int status) {
        return HttpResponse.json(status, json);
    }
}
