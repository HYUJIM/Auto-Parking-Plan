package com.autoparkingplan.server.tools.e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/**
 * 파이썬판 {@code httpx.Client(base_url=…, timeout=…)} 에 해당하는 작은 HTTP 도우미 ({@code java.net.http}).
 *
 * <p>응답은 {@link Resp} (상태 코드, 소문자 이름의 헤더, 본문 바이트). 연결 실패·시간 초과는 예외로 올라가 파이썬판처럼
 * "E2E 실행 중 예외 없음" 점검 실패로 기록된다.
 */
final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER) // httpx 기본값 follow_redirects=False
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String baseUrl;
    private final Duration timeout;

    Http(String baseUrl, double timeoutS) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis((long) (timeoutS * 1000));
    }

    /** 응답. 헤더 이름은 소문자, 같은 이름이 여럿이면 {@code ", "} 로 이어 붙인다 (httpx {@code dict(r.headers)} 와 같음). */
    record Resp(int status, Map<String, String> headers, byte[] body) {
        /** {@code r.text} (UTF-8). */
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** {@code r.json()} — JSON 이 아니면 예외. */
        JsonNode json() {
            return Json.parse(body);
        }

        /** {@code r.headers.get(name)} — 없으면 null. */
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    Resp get(String path) {
        return get(path, Map.of(), Map.of());
    }

    Resp get(String path, Map<String, String> params) {
        return get(path, params, Map.of());
    }

    Resp get(String path, Map<String, String> params, Map<String, String> headers) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path + query(params))).GET(), headers);
    }

    /** {@code c.post(path, json=…)}. */
    Resp post(String path, JsonNode json, Map<String, String> headers) {
        return post(path, Json.write(json).getBytes(StandardCharsets.UTF_8), "application/json", headers);
    }

    /** {@code c.post(path, content=bytes, headers={"Content-Type": …})}. */
    Resp post(String path, byte[] body, String contentType, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", contentType);
        return send(b, headers);
    }

    private Resp send(HttpRequest.Builder b, Map<String, String> headers) {
        headers.forEach(b::header);
        b.timeout(timeout);
        try {
            return exchange(b.build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code httpx.get(url, timeout=…)} — 연결 실패는 {@link IOException} (서버 기동 대기용). */
    static Resp getUrl(String url, double timeoutS) throws IOException {
        return exchange(HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofMillis((long) (timeoutS * 1000))).build());
    }

    private static Resp exchange(HttpRequest req) throws IOException {
        try {
            HttpResponse<byte[]> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return new Resp(r.statusCode(), flatten(r.headers()), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청이 중단됐습니다: " + req.uri(), e);
        }
    }

    private static Map<String, String> flatten(HttpHeaders h) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : h.map().entrySet()) {
            out.merge(e.getKey().toLowerCase(Locale.ROOT), String.join(", ", e.getValue()), (a, b) -> a + ", " + b);
        }
        return out;
    }

    /** 질의 문자열 (파이썬 urlencode 와 같이 공백은 +, 그 밖은 UTF-8 퍼센트 인코딩). */
    private static String query(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 소켓에 HTTP/1.1 GET 을 직접 써서 보내고 응답을 그대로 읽는다. 두 가지 경우에 쓴다.
     * <ul>
     *   <li>헤더 값을 바이트 그대로 보내야 할 때 (httpx 의 bytes 헤더 값 — {@code java.net.http} 는 비ASCII 헤더 값을 거부하거나 '?' 로 바꾼다)</li>
     *   <li>응답 헤더의 <b>순서</b>가 상세 문구에 들어갈 때 ({@code java.net.http} 의 {@code HttpHeaders} 는 이름순으로 정렬해 돌려준다;
     *       httpx {@code dict(r.headers)} 는 서버가 보낸 순서)</li>
     * </ul>
     * 본문은 Content-Length 또는 chunked 로 끝을 판단한다 (Connection: close 를 보내지 않으므로 응답 헤더도 평소와 같다).
     */
    static Resp rawGet(String baseUrl, String path, Map<String, byte[]> headers, double timeoutS) {
        URI u = URI.create(baseUrl);
        int port = u.getPort() < 0 ? 80 : u.getPort();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(u.getHost(), port), (int) (timeoutS * 1000));
            s.setSoTimeout((int) (timeoutS * 1000));
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(("GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nAccept: */*\r\n").getBytes(StandardCharsets.US_ASCII));
            for (Map.Entry<String, byte[]> e : headers.entrySet()) {
                req.write((e.getKey() + ": ").getBytes(StandardCharsets.US_ASCII));
                req.write(e.getValue());
                req.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            req.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().write(req.toByteArray());
            s.getOutputStream().flush();
            return readResponse(s.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final byte[] CRLFCRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private static Resp readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int sep = -1;
        while (sep < 0) {
            int n = in.read(tmp);
            if (n < 0) {
                break;
            }
            buf.write(tmp, 0, n);
            sep = indexOf(buf.toByteArray(), CRLFCRLF);
        }
        byte[] all = buf.toByteArray();
        if (sep < 0) {
            throw new IOException("HTTP 응답 헤더가 끝나기 전에 연결이 닫혔습니다");
        }
        String[] lines = new String(all, 0, sep, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] statusLine = lines[0].split(" ");
        if (statusLine.length < 2) {
            throw new IOException("HTTP 응답 상태 줄을 해석할 수 없습니다: " + lines[0]);
        }
        int status = Integer.parseInt(statusLine[1]);
        Map<String, String> hs = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                hs.merge(lines[i].substring(0, c).strip().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).strip(), (a, b) -> a + ", " + b);
            }
        }
        byte[] body = Arrays.copyOfRange(all, sep + 4, all.length);
        if ("chunked".equalsIgnoreCase(hs.getOrDefault("transfer-encoding", ""))) {
            byte[] decoded;
            while ((decoded = dechunk(body)) == null) {
                body = readMore(in, body, tmp);
            }
            body = decoded;
        } else if (hs.containsKey("content-length")) {
            int len = Integer.parseInt(hs.get("content-length").strip());
            while (body.length < len) {
                body = readMore(in, body, tmp);
            }
            body = Arrays.copyOf(body, len);
        } else {
            while (true) { // 길이 정보가 없으면 연결이 닫힐 때까지
                int n = in.read(tmp);
                if (n < 0) {
                    break;
                }
                body = concat(body, tmp, n);
            }
        }
        return new Resp(status, hs, body);
    }

    private static byte[] readMore(InputStream in, byte[] body, byte[] tmp) throws IOException {
        int n = in.read(tmp);
        if (n < 0) {
            throw new IOException("HTTP 응답 본문이 끝나기 전에 연결이 닫혔습니다");
        }
        return concat(body, tmp, n);
    }

    private static byte[] concat(byte[] a, byte[] b, int n) {
        byte[] out = Arrays.copyOf(a, a.length + n);
        System.arraycopy(b, 0, out, a.length, n);
        return out;
    }

    /** chunked 본문 해독. 마지막(크기 0) 조각까지 아직 안 왔으면 null. */
    private static byte[] dechunk(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            int eol = indexOf(data, CRLF, pos);
            if (eol < 0) {
                return null;
            }
            String sizeLine = new String(data, pos, eol - pos, StandardCharsets.US_ASCII);
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).strip(), 16);
            int start = eol + 2;
            if (size == 0) {
                return out.toByteArray();
            }
            if (data.length < start + size + 2) {
                return null;
            }
            out.write(data, start, size);
            pos = start + size + 2;
        }
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        return indexOf(hay, needle, 0);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
