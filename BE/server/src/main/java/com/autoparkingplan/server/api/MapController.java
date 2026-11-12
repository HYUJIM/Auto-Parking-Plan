package com.autoparkingplan.server.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.external.UpstreamError;
import com.autoparkingplan.external.VWorldTileApi;
import com.autoparkingplan.external.VWorldTiles;
import com.autoparkingplan.server.config.MapTiles;

import tools.jackson.databind.node.ObjectNode;

/**
 * 공개 API (비로그인·무인증) 중 배경지도 타일 — 화면의 미리보기 지도가 필지 경계 아래에 까는 지도.
 *
 * <pre>
 *   GET /v1/map/config                        화면이 쓸 타일 소스 (브이월드 키가 없으면 {"tiles": null})
 *   GET /v1/map/tiles/{layer}/{z}/{x}/{y}     타일 이미지 (layer = base | satellite, 줌 6~19, 표준 XYZ 번호)
 * </pre>
 *
 * <p>왜 서버를 거치나: 필지 경계는 브이월드 연속지적도라서 브이월드 배경지도 위에서만 건물·도로와 정확히 맞는다
 * (다른 타일 공급자는 국내 지적과 6~10m 어긋난다). 브이월드 타일 주소에는 인증키가 들어가는데, 공공 API 키는 브라우저에 주지 않는
 * 것이 이 프로젝트의 규칙이라 서버가 대신 받아 준다. 타일 번호는 호출 전에 엄격히 검사한다 (아무 주소나 대신 불러 주는 통로가 되지 않게).
 *
 * <p>성공 응답은 하루 동안 캐시해도 된다 ({@code Cache-Control: public, max-age=86400}) — 앞단 CDN 이 타일을 대신 내주게.
 * 요청 제한은 다른 공개 API 와 섞이지 않는 전용 묶음이다 (RequestGuardFilter 의 "tiles").
 */
@RestController
@RequestMapping("/v1/map")
public class MapController {
    private static final Logger log = LoggerFactory.getLogger(MapController.class);
    /** 앞자리 0·부호·공백 없는 십진수만 (같은 타일을 여러 주소로 부르지 못하게 — 캐시 키가 주소다). */
    private static final Pattern INT = Pattern.compile("0|[1-9]\\d{0,6}");
    static final String TILE_CACHE_CONTROL = "public, max-age=86400";
    /** '그 타일 없음'도 잠깐 캐시한다 — 없는 타일을 계속 물어 브이월드 호출을 일으키지 못하게 (CDN 기본은 10초). */
    static final String MISSING_CACHE_CONTROL = "public, max-age=600";
    /** 브이월드로 동시에 나가는 타일 요청 수. 브이월드가 느려져도 타일이 요청 스레드를 다 차지해 검색·검토까지 멈추는 일을 막는다. */
    static final int UPSTREAM_CONCURRENCY = 24;
    static final long UPSTREAM_WAIT_MS = 300;
    private static final long UPSTREAM_LOG_INTERVAL_NS = 60_000_000_000L;

    private final VWorldTileApi vworld; // 브이월드 키가 없으면 null
    private final Semaphore upstreamSlots = new Semaphore(UPSTREAM_CONCURRENCY);
    private final AtomicLong lastUpstreamLog = new AtomicLong(System.nanoTime() - UPSTREAM_LOG_INTERVAL_NS);

    public MapController(MapTiles tiles) {
        this.vworld = tiles.vworld();
    }

    @GetMapping("/config")
    public ObjectNode config() {
        ObjectNode out = Json.object();
        if (vworld == null) {
            out.putNull("tiles"); // 화면은 자체 기본 타일로 대체한다
            return out;
        }
        ObjectNode t = out.putObject("tiles");
        t.put("provider", "vworld");
        t.put("normal", "/v1/map/tiles/" + VWorldTiles.Layer.BASE.id() + "/{z}/{x}/{y}");
        t.put("satellite", "/v1/map/tiles/" + VWorldTiles.Layer.SATELLITE.id() + "/{z}/{x}/{y}");
        t.put("min_zoom", VWorldTiles.MIN_ZOOM);
        t.put("max_zoom", VWorldTiles.MAX_ZOOM);
        return out;
    }

    private static int pathInt(String name, String raw) {
        if (!INT.matcher(raw).matches()) {
            throw RequestValidationException.of(List.of("path", name), "0 이상의 정수여야 합니다");
        }
        return Integer.parseInt(raw);
    }

    @GetMapping("/tiles/{layer}/{z}/{x}/{y}")
    public ResponseEntity<byte[]> tile(@PathVariable("layer") String layerS, @PathVariable("z") String zS, @PathVariable("x") String xS,
            @PathVariable("y") String yS, HttpServletRequest request) {
        // 검사는 키 설정 여부와 무관하게 먼저 한다 (잘못된 주소는 언제나 422)
        VWorldTiles.Layer layer = VWorldTiles.Layer.of(layerS)
                .orElseThrow(() -> RequestValidationException.of(List.of("path", "layer"), "base 또는 satellite 여야 합니다"));
        int z = pathInt("z", zS);
        int x = pathInt("x", xS);
        int y = pathInt("y", yS);
        if (z < VWorldTiles.MIN_ZOOM || z > VWorldTiles.MAX_ZOOM) {
            throw RequestValidationException.of(List.of("path", "z"), VWorldTiles.MIN_ZOOM + "~" + VWorldTiles.MAX_ZOOM + " 이어야 합니다");
        }
        int n = 1 << z;
        if (x >= n) {
            throw RequestValidationException.of(List.of("path", "x"), "허용 범위를 벗어났습니다");
        }
        if (y >= n) {
            throw RequestValidationException.of(List.of("path", "y"), "허용 범위를 벗어났습니다");
        }
        // 타일 하나에 주소도 하나만: 라우팅은 ';v=1' 같은 경로 매개변수와 %xx 표기를 같은 타일로 풀어 주므로, 받은 주소가
        // 표준 표기와 글자까지 같을 때만 응답한다. 앞단 CDN 의 캐시 키가 주소라서, 변형 주소로 캐시를 비켜 가며 같은 타일을
        // 끝없이 새로 받게 하는 것을 막는다 (2026-11-12 운영 점검에서 변형 주소가 200 으로 응답함을 확인)
        String canonical = "/v1/map/tiles/" + layer.id() + "/" + z + "/" + x + "/" + y;
        if (!canonical.equals(request.getRequestURI())) {
            throw new ApiError("TILE_NOT_FOUND");
        }
        if (vworld == null) {
            throw new ApiError("TILE_NOT_FOUND"); // 타일 소스 없음 (GET /v1/map/config 가 tiles: null 을 알려 준다)
        }
        boolean acquired;
        try {
            acquired = upstreamSlots.tryAcquire(UPSTREAM_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw busy(); // 브이월드가 느려 자리가 꽉 찼다 — 요청 스레드를 붙잡지 않고 바로 돌려보낸다
        }
        VWorldTiles.Tile tile;
        try {
            tile = vworld.getTile(layer, z, x, y)
                    .orElseThrow(() -> new ApiError("TILE_NOT_FOUND", null, null, null, Map.of(HttpHeaders.CACHE_CONTROL, MISSING_CACHE_CONTROL)));
        } catch (UpstreamError e) {
            logUpstream(e);
            throw busy();
        } finally {
            upstreamSlots.release();
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, tile.contentType()).header(HttpHeaders.CACHE_CONTROL, TILE_CACHE_CONTROL)
                .body(tile.body());
    }

    /** 일시적인 실패는 CDN·브라우저가 붙들고 있지 않게 no-store 로 내보낸다. */
    private static ApiError busy() {
        return new ApiError("UPSTREAM_UNAVAILABLE", null, null, null, Map.of(HttpHeaders.CACHE_CONTROL, "no-store", "Retry-After", "2"));
    }

    /** 타일은 한꺼번에 수십 장이 실패하므로 원천 오류는 1분에 한 번만 남긴다. 메시지는 클라이언트가 이미 인증키를 가린 것이다. */
    private void logUpstream(UpstreamError e) {
        long now = System.nanoTime();
        long last = lastUpstreamLog.get();
        if (now - last >= UPSTREAM_LOG_INTERVAL_NS && lastUpstreamLog.compareAndSet(last, now)) {
            log.warn("지도 타일 조회 실패 (같은 경고는 1분간 생략): {}", e.getMessage());
        }
    }
}
