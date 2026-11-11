package com.autoparkingplan.external;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autoparkingplan.common.http.HttpResponse;
import com.autoparkingplan.common.http.Urls;

/**
 * 브이월드 배경지도 타일(WMTS) 의 상수·주소 조립·응답 판별. 클라이언트는 {@link VWorldTileClient}.
 *
 * <pre>
 * GET https://api.vworld.kr/req/wmts/1.0.0/{key}/{Layer}/{z}/{y}/{x}.{ext}
 *     Layer: Base(일반, png) · Satellite(위성, jpeg)
 *     z/x/y: 웹 메르카토르 표준 256px 타일 (OpenStreetMap 과 같은 번호). 주소의 순서는 z / y / x 다 (행이 먼저).
 *     줌 범위 6 ~ 19 (20 이상은 오류).
 * </pre>
 *
 * <p>2026-11-12 실호출로 확인한 것:
 * <ul>
 *   <li>서버에서 Referer 없이 호출해도 된다.</li>
 *   <li>실패(등록되지 않은 키, 서비스 제공영역 밖, 줌 범위 밖)도 <b>HTTP 200</b> 에 {@code Content-Type: application/xml} 과
 *       OWS {@code ExceptionReport} 본문으로 온다 → 성공 여부는 상태코드가 아니라 {@code image/*} 여부로 가른다.</li>
 *   <li>키 오류는 {@code <Exception exceptionCode="InvalidParameterValue" locator="key">}, 자료 없음은
 *       {@code exceptionCode="FileNotFound"}, 줌 범위 밖은 {@code locator="tilematrix"}.</li>
 * </ul>
 *
 * <p>인증키가 주소의 경로에 들어가므로(질의 파라미터가 아님) 요청 주소를 로그·예외 메시지·응답에 싣지 않는다.
 */
public final class VWorldTiles {
    public static final String WMTS_PATH = "/req/wmts/1.0.0";
    /** 브이월드가 타일을 주는 줌 범위 (실호출 오류 문구: "유효한 파라미터 값의 범위 : 6 ~ 19"). */
    public static final int MIN_ZOOM = 6;
    public static final int MAX_ZOOM = 19;
    /** 타일 한 장의 크기 상한. 실제 타일은 수 KB ~ 200KB 정도다. */
    public static final long MAX_TILE_BYTES = 1024L * 1024;

    private static final Pattern EXCEPTION_RE = Pattern.compile("<Exception\\b([^>]*)>");
    private static final Pattern LOCATOR_RE = Pattern.compile("\\blocator\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern CODE_RE = Pattern.compile("\\bexceptionCode\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TEXT_RE = Pattern.compile("<ExceptionText>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</ExceptionText>",
            Pattern.DOTALL);

    /** 배경지도 레이어. {@link #id} 는 우리 API 경로에 쓰는 이름, {@link #wmtsName}·{@link #ext} 는 브이월드 주소에 쓰는 값. */
    public enum Layer {
        BASE("base", "Base", "png"),
        SATELLITE("satellite", "Satellite", "jpeg");

        private final String id;
        private final String wmtsName;
        private final String ext;

        Layer(String id, String wmtsName, String ext) {
            this.id = id;
            this.wmtsName = wmtsName;
            this.ext = ext;
        }

        public String id() {
            return id;
        }

        public String wmtsName() {
            return wmtsName;
        }

        public String ext() {
            return ext;
        }

        /** 경로 이름(소문자 그대로 {@code base}·{@code satellite})으로 찾는다. 그 밖의 값은 empty. */
        public static Optional<Layer> of(String id) {
            for (Layer l : values()) {
                if (l.id.equals(id)) {
                    return Optional.of(l);
                }
            }
            return Optional.empty();
        }
    }

    /** 받은 타일: 이미지 형식({@code image/png}·{@code image/jpeg} 등, 매개변수 없음)과 바이트. */
    public record Tile(String contentType, byte[] body) {
    }

    /** 브이월드가 XML 로 돌려준 오류 (OWS ExceptionReport 의 첫 Exception). 못 읽은 값은 빈 문자열. */
    public record WmtsError(String code, String locator, String text) {
        /** 인증키 문제인가 (등록되지 않은 키 등) — 자료 없음과 달리 설정을 고쳐야 한다. */
        public boolean isKeyError() {
            return locator.equalsIgnoreCase("key");
        }
    }

    private VWorldTiles() {
    }

    /** 줌 6~19, 0 ≤ x,y &lt; 2^z 인가. */
    public static boolean isValidTile(int z, int x, int y) {
        if (z < MIN_ZOOM || z > MAX_ZOOM) {
            return false;
        }
        int n = 1 << z;
        return x >= 0 && x < n && y >= 0 && y < n;
    }

    /**
     * 네트워크 호출 전 검사.
     *
     * @throws IllegalArgumentException 줌·타일 번호가 범위 밖
     */
    public static void requireTile(int z, int x, int y) {
        if (!isValidTile(z, x, y)) {
            throw new IllegalArgumentException("타일 번호가 올바르지 않습니다: z=" + z + ", x=" + x + ", y=" + y);
        }
    }

    /**
     * 타일 주소. 우리 쪽 인자 순서는 (z, x, y) 지만 브이월드 주소는 {@code .../{z}/{y}/{x}.{ext}} 다.
     * 결과에 인증키가 들어 있으므로 로그·예외 메시지에 넣지 않는다.
     *
     * @param baseUrl scheme+host(+port). 경로는 고정
     */
    public static String tileUrl(String baseUrl, String apiKey, Layer layer, int z, int x, int y) {
        requireTile(z, x, y);
        String key = Urls.enc(apiKey).replace("+", "%20"); // 경로 조각으로 안전하게 (정상 키는 영숫자·하이픈이라 그대로다)
        return Http.joinUrl(baseUrl, WMTS_PATH) + "/" + key + "/" + layer.wmtsName() + "/" + z + "/" + y + "/" + x + "." + layer.ext();
    }

    /**
     * 응답이 타일 이미지면 그 형식({@code image/png} 처럼 매개변수를 뗀 소문자)을, 아니면 empty.
     * 브라우저에 그대로 내보낼 값이라 래스터 형식(png·jpeg·webp·gif)만 받는다 — SVG 같은 문서형 이미지는 타일이 아니다.
     */
    public static Optional<String> imageContentType(HttpResponse resp) {
        String ct = resp.header("content-type");
        if (ct == null || resp.body().length == 0) {
            return Optional.empty();
        }
        int semi = ct.indexOf(';');
        String type = (semi < 0 ? ct : ct.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "image/png", "image/jpeg", "image/webp", "image/gif" -> Optional.of(type);
            case "image/jpg" -> Optional.of("image/jpeg");
            default -> Optional.empty();
        };
    }

    /** 이미지가 아닌 응답 본문(OWS ExceptionReport XML)에서 오류 코드·위치·문구를 읽는다. 형식이 달라도 예외 없이 빈 값으로 채운다. */
    public static WmtsError parseError(HttpResponse resp) {
        byte[] body = resp.body();
        String xml = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8);
        String code = "";
        String locator = "";
        Matcher ex = EXCEPTION_RE.matcher(xml);
        if (ex.find()) {
            String attrs = ex.group(1);
            Matcher m = CODE_RE.matcher(attrs);
            if (m.find()) {
                code = m.group(1);
            }
            m = LOCATOR_RE.matcher(attrs);
            if (m.find()) {
                locator = m.group(1);
            }
        }
        Matcher t = TEXT_RE.matcher(xml);
        String text = t.find() ? t.group(1).strip() : "";
        return new WmtsError(code, locator, text.length() > 200 ? text.substring(0, 200) : text);
    }
}
