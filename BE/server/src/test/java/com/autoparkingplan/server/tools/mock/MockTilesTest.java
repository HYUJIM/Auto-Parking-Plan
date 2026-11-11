package com.autoparkingplan.server.tools.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.autoparkingplan.external.UpstreamError;
import com.autoparkingplan.external.VWorldTileClient;
import com.autoparkingplan.external.VWorldTiles;

/** 모의 서버의 배경지도 타일 (자바판 추가): 실제 클라이언트로 불러 본다 — 성공은 PNG, 실패는 실제 API 처럼 200 + XML. */
class MockTilesTest {
    static final Path FIXTURES = Path.of("..", "data", "mock", "parcels.json");

    @Test
    void pngIsDecodable256Square() throws Exception {
        var img = ImageIO.read(new ByteArrayInputStream(MockTiles.png(0xEDF1F4, 0xC7D0D9)));
        assertThat(img.getWidth()).isEqualTo(256);
        assertThat(img.getHeight()).isEqualTo(256);
        assertThat(img.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xC7D0D9); // 타일 경계선
        assertThat(img.getRGB(100, 100) & 0xFFFFFF).isEqualTo(0xEDF1F4); // 바탕
    }

    @Test
    void pathShapes() {
        assertThat(MockTiles.respond("k/Base/19/203286/449482.png").contentType()).isEqualTo("image/png");
        assertThat(MockTiles.respond("k/Satellite/6/25/54.jpeg").contentType()).isEqualTo("image/png");
        for (String xmlCase : new String[] {"invalid/Base/19/203286/449482.png", "k/Hybrid/19/1/1.png", "k/Base/19/1/1.jpeg", "k/Base/20/1/1.png",
                "k/Base/5/1/1.png", "k/Base/6/64/1.png", "k/Base/6/1/64.png", "k/Base/x/1/1.png"}) {
            MockTiles.Result r = MockTiles.respond(xmlCase);
            assertThat(r.contentType()).as(xmlCase).isEqualTo(MockTiles.XML_TYPE);
            assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("<ExceptionReport");
        }
        assertThat(MockTiles.respond("k/Base/19/1")).isNull();
        assertThat(MockTiles.respond("k/Base/19/1/1")).isNull();
        assertThat(MockTiles.respond("k/Base/19/1/1/2.png")).isNull();
    }

    @Test
    void realClientAgainstMockServer() throws Exception {
        try (MockServer server = MockServer.start("127.0.0.1", 0, FIXTURES, 0.1)) {
            String base = "http://127.0.0.1:" + server.port();
            VWorldTileClient ok = new VWorldTileClient("mock-key", base, 3.0);
            VWorldTiles.Tile tile = ok.getTile(VWorldTiles.Layer.BASE, 19, 449482, 203286).orElseThrow();
            assertThat(tile.contentType()).isEqualTo("image/png");
            assertThat(ImageIO.read(new ByteArrayInputStream(tile.body())).getWidth()).isEqualTo(256);
            assertThat(ok.getTile(VWorldTiles.Layer.SATELLITE, 6, 54, 25)).isPresent();

            VWorldTileClient bad = new VWorldTileClient(MockServer.INVALID_KEY, base, 3.0);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> bad.getTile(VWorldTiles.Layer.BASE, 19, 449482, 203286))
                    .isInstanceOf(UpstreamError.class).hasMessageContaining("인증키 오류");

            // 실제 API 처럼 실패도 HTTP 200 이다. 그 밖의 경로·방식은 기존 규칙(404·405) 그대로
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> xml = http.send(HttpRequest.newBuilder(URI.create(base + "/req/wmts/1.0.0/mock-key/Base/20/1/1.png")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(xml.statusCode()).isEqualTo(200);
            assertThat(xml.headers().firstValue("content-type")).contains(MockTiles.XML_TYPE);
            assertThat(xml.body()).contains("tilematrix");
            HttpResponse<String> shape = http.send(HttpRequest.newBuilder(URI.create(base + "/req/wmts/1.0.0/mock-key/Base/19/1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(shape.statusCode()).isEqualTo(404);
        }
    }
}
