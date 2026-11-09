package com.autoparkingplan.legal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;

/** (자바판 추가) 리다이렉트는 같은 사이트의 http(s) 로만 따라간다 — 가로채인 응답이 수집기를 다른 호스트·내부망으로 보내거나 TLS 를 벗기지 못하게. */
class RedirectFollowerTest {
    static HttpResponse redirect(String location) {
        return new HttpResponse(302, Map.of("location", List.of(location)), new byte[0]);
    }

    static HttpResponse ok() {
        return HttpResponse.of(200, "text/html", "ok".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void followsSameSiteIncludingSubdomainsAndRelativeLocations() throws IOException {
        MockTransport mock = MockTransport.handler(req -> switch (req.uri().getHost() + req.uri().getPath()) {
            case "law.go.kr/a" -> redirect("https://www.law.go.kr/b");
            case "www.law.go.kr/b" -> redirect("/c?x=1");
            default -> ok();
        });
        RedirectFollower.Result r = new RedirectFollower(mock).send(HttpRequest.get(URI.create("https://law.go.kr/a")), Duration.ofSeconds(1));
        assertThat(r.response().status()).isEqualTo(200);
        assertThat(r.request().uri().toString()).isEqualTo("https://www.law.go.kr/c?x=1");
        assertThat(mock.count()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/x", "http://www.law.go.kr/x", "ftp://www.law.go.kr/x", "javascript:alert(1)",
            "https://law.go.kr.evil.example/x", "https://go.kr/x", "http://[::1]:8080/x", "https://169.254.169.254/latest/meta-data/"})
    void doesNotFollowOtherSitesDowngradesOrOddSchemes(String location) throws IOException {
        MockTransport mock = new MockTransport(redirect(location), ok());
        RedirectFollower.Result r = new RedirectFollower(mock).send(HttpRequest.get(URI.create("https://www.law.go.kr/a")), Duration.ofSeconds(1));
        assertThat(r.response().status()).as(location).isEqualTo(302); // 따라가지 않고 그대로 → 호출한 쪽이 'Redirect response' 오류로 알린다
        assertThat(mock.count()).as(location).isEqualTo(1);
    }

    @Test
    void followableRules() {
        URI from = URI.create("https://www.law.go.kr/a");
        assertThat(RedirectFollower.followable(from, URI.create("https://www.law.go.kr/b"))).isTrue();
        assertThat(RedirectFollower.followable(from, URI.create("https://law.go.kr/b"))).isTrue();
        assertThat(RedirectFollower.followable(from, URI.create("https://img.law.go.kr:8443/b"))).isTrue(); // 같은 law.go.kr 의 다른 호스트
        assertThat(RedirectFollower.followable(URI.create("http://www.law.go.kr/a"), URI.create("https://www.law.go.kr/b"))).isTrue(); // 올라가기는 허용
        assertThat(RedirectFollower.followable(from, URI.create("http://www.law.go.kr/b"))).isFalse(); // 내려가기
        assertThat(RedirectFollower.followable(from, URI.create("https://go.kr/b"))).isFalse(); // 공통 상위가 2 레이블뿐
        assertThat(RedirectFollower.followable(from, URI.create("https://other.go.kr/b"))).isFalse();
        assertThat(RedirectFollower.followable(from, URI.create("https:///b"))).isFalse(); // 호스트 없음
        assertThat(RedirectFollower.redirectTarget(from, "%zz")).isNull(); // 해석 불가 Location
    }
}
