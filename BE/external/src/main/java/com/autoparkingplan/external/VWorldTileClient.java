package com.autoparkingplan.external;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.HttpTransport;
import com.autoparkingplan.common.http.JdkHttpTransport;

/**
 * 브이월드 배경지도 타일(WMTS) 클라이언트. 상수·주소 조립·응답 판별은 {@link VWorldTiles}.
 *
 * <p>브라우저가 인증키를 알 필요 없이 서버가 대신 받아 준다 (공공 API 키는 서버에만 둔다). 인증키가 주소 경로에 들어가므로
 * 요청 주소는 어디에도 남기지 않고, 원천이 돌려준 문구는 {@link HttpJsonClient#scrub} 로 키를 가린 뒤에만 예외 메시지에 싣는다.
 *
 * <p>기본 전송은 응답 본문을 {@link VWorldTiles#MAX_TILE_BYTES} 까지만 받는다. 타일은 화면 하나에 수십 장씩 요청되므로
 * 다시 시도하지 않는다(재시도 0회) — 실패한 타일은 빈 칸으로 두는 편이 요청 스레드를 오래 붙잡는 것보다 낫다. 스레드 안전.
 */
public final class VWorldTileClient implements VWorldTileApi, AutoCloseable {
    private final String apiKey;
    private final String baseUrl;
    private final HttpJsonClient http;

    /**
     * @param apiKey    인증키 (비어 있으면 IllegalArgumentException)
     * @param baseUrl   null 이면 {@link VWorld#VWORLD_BASE_URL}. scheme+host(+port) 만 (경로는 고정)
     * @param timeout   시간 제한(초, 0보다 커야 함)
     * @param transport null 이면 본문 상한이 있는 기본 JDK 전송
     */
    public VWorldTileClient(String apiKey, String baseUrl, double timeout, HttpTransport transport) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("브이월드 인증키(api_key)가 비어 있습니다");
        }
        if (timeout <= 0 || Double.isNaN(timeout)) {
            throw new IllegalArgumentException("timeout 은 0보다 커야 합니다");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : VWorld.VWORLD_BASE_URL;
        HttpTransport t = transport != null ? transport
                : new JdkHttpTransport(Duration.ofNanos(Math.round(timeout * 1_000_000_000d)), VWorldTiles.MAX_TILE_BYTES);
        this.http = new HttpJsonClient(VWorld.SERVICE, timeout, t, 0, List.of(apiKey));
    }

    public VWorldTileClient(String apiKey, String baseUrl, double timeout) {
        this(apiKey, baseUrl, timeout, null);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 키 노출 방지. */
    @Override
    public String toString() {
        return "VWorldTileClient(base_url=" + PyStr.reprString(baseUrl) + ")";
    }

    @Override
    public void close() {
        http.close();
    }

    @Override
    public Optional<VWorldTiles.Tile> getTile(VWorldTiles.Layer layer, int z, int x, int y) {
        String endpoint = "wmts/" + layer.wmtsName();
        String url = VWorldTiles.tileUrl(baseUrl, apiKey, layer, z, x, y); // 범위 밖이면 여기서 IllegalArgumentException
        HttpResponse resp = http.get(url, Map.of(), endpoint);
        int status = resp.status();
        if (status == 404) {
            return Optional.empty();
        }
        if (status >= 400) {
            throw new UpstreamError(VWorld.SERVICE, endpoint + " HTTP " + status + " 응답", false, status);
        }
        Optional<String> type = VWorldTiles.imageContentType(resp);
        if (type.isPresent()) {
            if (resp.body().length > VWorldTiles.MAX_TILE_BYTES) { // 주입한 전송에는 상한이 없을 수 있다
                throw new UpstreamError(VWorld.SERVICE, endpoint + " 응답 본문이 너무 큽니다", false, status);
            }
            return Optional.of(new VWorldTiles.Tile(type.get(), resp.body()));
        }
        // 200 + XML: 키 오류만 장애로 올리고, 나머지(서비스 제공영역 밖·줌 범위 밖 등)는 '그 타일 없음'
        VWorldTiles.WmtsError err = VWorldTiles.parseError(resp);
        if (err.isKeyError()) {
            throw new UpstreamError(VWorld.SERVICE, endpoint + " 인증키 오류: " + http.scrub(err.text()), false, status);
        }
        return Optional.empty();
    }
}
