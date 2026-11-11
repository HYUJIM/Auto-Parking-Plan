package com.autoparkingplan.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.autoparkingplan.common.http.HttpRequest;
import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.MockTransport;
import com.autoparkingplan.external.VWorldTiles.Layer;

/** 브이월드 배경지도 타일(WMTS): 번호 검사, 주소 조립(z/y/x 순서), 200+XML 오류 판별, 인증키 가리기. (자바판 추가 — 파이썬판에 없는 기능) */
class VWorldTilesTest {
    private static final String SECRET = "SECRET-VWORLD-KEY-5678";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    // 2026-11-12 실호출로 타일이 오는 것을 확인한 번호
    private static final int Z = 19;
    private static final int X = 449482;
    private static final int Y = 203286;

    /** 2026-11-12 실호출로 본 오류 본문 모양 (HTTP 200, application/xml). */
    private static HttpResponse xmlError(String code, String locator, String text) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ExceptionReport xmlns=\"http://www.opengis.net/ows/1.1\"\n"
                + "\tversion=\"1.1.0\" xml:lang=\"kor\">\n\t<Exception exceptionCode=\"" + code + "\" locator=\"" + locator + "\">\n"
                + "\t\t<ExceptionText><![CDATA[" + text + "]]></ExceptionText>\n\t</Exception>\n</ExceptionReport>\n";
        return HttpResponse.of(200, "application/xml;charset=UTF-8", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static VWorldTileClient client(MockTransport http) {
        return new VWorldTileClient(SECRET, "https://wmts.example", Http.DEFAULT_TIMEOUT, http);
    }

    // ---------------------------------------------------------------- 번호 검사 · 레이어

    @ParameterizedTest
    @CsvSource({"6,0,0", "6,63,63", "19,449482,203286", "19,524287,524287"})
    void validTiles(int z, int x, int y) {
        assertThat(VWorldTiles.isValidTile(z, x, y)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"5,0,0", "20,0,0", "-1,0,0", "6,64,0", "6,0,64", "6,-1,0", "6,0,-1", "19,524288,0", "19,0,524288", "31,0,0"})
    void invalidTiles(int z, int x, int y) {
        assertThat(VWorldTiles.isValidTile(z, x, y)).isFalse();
        assertThatThrownBy(() -> VWorldTiles.requireTile(z, x, y)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void layerLookupIsExact() {
        assertThat(Layer.of("base")).contains(Layer.BASE);
        assertThat(Layer.of("satellite")).contains(Layer.SATELLITE);
        for (String bad : new String[] {"Base", "BASE", "hybrid", "", " base", "base/..", null}) {
            assertThat(Layer.of(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    // ---------------------------------------------------------------- 주소 조립

    /** 우리 인자는 (z, x, y) 지만 브이월드 주소는 z / y / x 순서다. 확장자는 레이어마다 다르다. */
    @Test
    void tileUrlPutsRowBeforeColumn() {
        assertThat(VWorldTiles.tileUrl("https://api.vworld.kr", "KEY", Layer.BASE, Z, X, Y))
                .isEqualTo("https://api.vworld.kr/req/wmts/1.0.0/KEY/Base/19/203286/449482.png");
        assertThat(VWorldTiles.tileUrl("https://api.vworld.kr/", "KEY", Layer.SATELLITE, Z, X, Y))
                .isEqualTo("https://api.vworld.kr/req/wmts/1.0.0/KEY/Satellite/19/203286/449482.jpeg");
        assertThat(VWorldTiles.tileUrl("http://127.0.0.1:8090", "a b/c?d", Layer.BASE, 6, 54, 25))
                .isEqualTo("http://127.0.0.1:8090/req/wmts/1.0.0/a%20b%2Fc%3Fd/Base/6/25/54.png"); // 키는 경로 조각 하나로
    }

    @Test
    void clientRequestsUpstreamWithKeyInPathAndNoQuery() {
        MockTransport rec = new MockTransport(HttpResponse.of(200, "image/png", PNG));
        VWorldTiles.Tile tile = client(rec).getTile(Layer.BASE, Z, X, Y).orElseThrow();
        assertThat(tile.contentType()).isEqualTo("image/png");
        assertThat(tile.body()).isEqualTo(PNG);
        HttpRequest req = rec.lastRequest();
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.uri().getHost()).isEqualTo("wmts.example");
        assertThat(req.uri().getPath()).isEqualTo("/req/wmts/1.0.0/" + SECRET + "/Base/19/203286/449482.png");
        assertThat(req.uri().getRawQuery()).isNull();
        assertThat(rec.count()).isEqualTo(1);
    }

    @Test
    void satelliteUsesJpegAndNormalizesContentType() {
        MockTransport rec = new MockTransport(HttpResponse.of(200, "Image/JPEG; charset=binary", PNG));
        VWorldTiles.Tile tile = client(rec).getTile(Layer.SATELLITE, Z, X, Y).orElseThrow();
        assertThat(tile.contentType()).isEqualTo("image/jpeg");
        assertThat(rec.lastRequest().uri().getPath()).endsWith("/Satellite/19/203286/449482.jpeg");
    }

    // ---------------------------------------------------------------- 호출 전 검사

    @Test
    void invalidTileNeverReachesNetwork() {
        MockTransport rec = new MockTransport(HttpResponse.of(200, "image/png", PNG));
        VWorldTileClient c = client(rec);
        assertThatThrownBy(() -> c.getTile(Layer.BASE, 20, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.getTile(Layer.BASE, 5, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.getTile(Layer.BASE, 19, 1 << 19, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.getTile(Layer.BASE, 19, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rec.count()).isZero();
    }

    @Test
    void emptyKeyIsRejected() {
        assertThatThrownBy(() -> new VWorldTileClient("", null, 3.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VWorldTileClient(null, null, 3.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VWorldTileClient(SECRET, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new VWorldTileClient(SECRET, null, 3.0).baseUrl()).isEqualTo("https://api.vworld.kr");
    }

    // ---------------------------------------------------------------- 200 + XML

    /** 서비스 제공영역 밖·줌 범위 밖은 HTTP 200 + XML 로 온다 → 오류가 아니라 '그 타일 없음'. */
    @Test
    void xmlOn200MeansNoTile() {
        assertThat(client(new MockTransport(xmlError("FileNotFound", "", "서비스 제공영역이 아닙니다."))).getTile(Layer.BASE, Z, 1, 1)).isEmpty();
        assertThat(client(new MockTransport(xmlError("InvalidParameterValue", "tilematrix", "tilematrix 파라미터의 값이 유효한 범위를 넘었습니다.")))
                .getTile(Layer.SATELLITE, Z, X, Y)).isEmpty();
    }

    /** 이미지라고 했지만 본문이 비었거나, 형식을 알 수 없거나, 타일로 쓸 수 없는 형식이면 타일 없음. */
    @Test
    void nonImageOrEmptyBodyMeansNoTile() {
        assertThat(client(new MockTransport(HttpResponse.of(200, "image/png", new byte[0]))).getTile(Layer.BASE, Z, X, Y)).isEmpty();
        assertThat(client(new MockTransport(HttpResponse.of(200, null, PNG))).getTile(Layer.BASE, Z, X, Y)).isEmpty();
        assertThat(client(new MockTransport(HttpResponse.of(200, "text/html", "<html>".getBytes(StandardCharsets.UTF_8))))
                .getTile(Layer.BASE, Z, X, Y)).isEmpty();
        assertThat(client(new MockTransport(HttpResponse.of(200, "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8))))
                .getTile(Layer.BASE, Z, X, Y)).isEmpty();
        assertThat(client(new MockTransport(HttpResponse.of(404, "text/plain", new byte[0]))).getTile(Layer.BASE, Z, X, Y)).isEmpty();
    }

    @Test
    void parseErrorReadsCodeLocatorText() {
        VWorldTiles.WmtsError e = VWorldTiles.parseError(xmlError("InvalidParameterValue", "key", "등록되지 않은 인증키입니다."));
        assertThat(e.code()).isEqualTo("InvalidParameterValue");
        assertThat(e.locator()).isEqualTo("key");
        assertThat(e.text()).isEqualTo("등록되지 않은 인증키입니다.");
        assertThat(e.isKeyError()).isTrue();
        VWorldTiles.WmtsError junk = VWorldTiles.parseError(HttpResponse.of(200, "text/plain", "not xml".getBytes(StandardCharsets.UTF_8)));
        assertThat(junk).isEqualTo(new VWorldTiles.WmtsError("", "", ""));
        assertThat(junk.isKeyError()).isFalse();
    }

    // ---------------------------------------------------------------- 장애 · 인증키 가리기

    /** 키 오류는 자료 없음과 구분해 장애로 올린다 (다시 시도해도 소용없음). 원천 문구에 키가 섞여 와도 가린다. */
    @Test
    void keyErrorIsUpstreamErrorWithKeyRedacted() {
        MockTransport rec = new MockTransport(xmlError("InvalidParameterValue", "key", "등록되지 않은 인증키입니다. 입력한 값 : " + SECRET));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).getTile(Layer.BASE, Z, X, Y));
        assertThat(e.service()).isEqualTo("vworld");
        assertThat(e.retriable()).isFalse();
        assertThat(e.getMessage()).contains("인증키 오류").contains("***").doesNotContain(SECRET);
        assertThat(rec.count()).isEqualTo(1);
    }

    /** 타일은 다시 시도하지 않는다 (요청 1회). 예외 메시지·toString 어디에도 키·요청 주소가 없다. */
    @Test
    void networkFailuresAreUpstreamErrorsWithoutRetryOrKey() {
        for (Object failure : new Object[] {new HttpTimeoutException("timed out"), new IOException("connect https://wmts.example/" + SECRET),
                HttpResponse.text(503, "busy " + SECRET), HttpResponse.text(403, "denied " + SECRET)}) {
            MockTransport rec = new MockTransport(failure);
            UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> client(rec).getTile(Layer.BASE, Z, X, Y));
            assertThat(e).as(String.valueOf(failure.getClass())).isNotNull();
            assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain("wmts.example");
            assertThat(e.getCause()).isNull();
            assertThat(rec.count()).isEqualTo(1);
        }
        assertThat(client(new MockTransport(HttpResponse.of(200, "image/png", PNG))).toString()).doesNotContain(SECRET);
    }

    /** 잘못된 기본 주소: URI 예외 메시지에는 키가 든 전체 주소가 실리므로 그대로 올리지 않는다. */
    @Test
    void badBaseUrlDoesNotLeakKey() {
        VWorldTileClient c = new VWorldTileClient(SECRET, "ht tp://bad host", 3.0, new MockTransport(HttpResponse.of(200, "image/png", PNG)));
        UpstreamError e = catchThrowableOfType(UpstreamError.class, () -> c.getTile(Layer.BASE, Z, X, Y));
        assertThat(e.getMessage()).doesNotContain(SECRET);
        assertThat(e.retriable()).isFalse();
    }

    @Test
    void oversizedBodyFromInjectedTransportIsRejected() {
        byte[] big = new byte[(int) VWorldTiles.MAX_TILE_BYTES + 1];
        MockTransport rec = new MockTransport(HttpResponse.of(200, "image/png", big));
        assertThatThrownBy(() -> client(rec).getTile(Layer.BASE, Z, X, Y)).isInstanceOf(UpstreamError.class);
    }

    @Test
    void imageContentTypeAllowsOnlyRasterTypes() {
        assertThat(VWorldTiles.imageContentType(HttpResponse.of(200, "image/png", PNG))).contains("image/png");
        assertThat(VWorldTiles.imageContentType(HttpResponse.of(200, "image/jpg", PNG))).contains("image/jpeg");
        assertThat(VWorldTiles.imageContentType(HttpResponse.of(200, "image/webp", PNG))).contains("image/webp");
        assertThat(VWorldTiles.imageContentType(HttpResponse.of(200, "application/xml;charset=UTF-8", PNG))).isEqualTo(Optional.empty());
        assertThat(VWorldTiles.imageContentType(HttpResponse.of(200, "image/svg+xml", PNG))).isEmpty();
    }
}
