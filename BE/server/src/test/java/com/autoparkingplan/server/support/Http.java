package com.autoparkingplan.server.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/**
 * 테스트용 HTTP/1.1 클라이언트 (파이썬 fastapi.testclient.TestClient 자리). 소켓에 요청 바이트를 그대로 쓴다 —
 * 표준 클라이언트가 막는 것(거짓 Content-Length, 비ASCII 헤더 바이트, Content-Length 없는 청크 본문)도 보낼 수 있다.
 *
 * <p>헤더 값은 ISO-8859-1 로 쓴다 (파이썬 {@code token.encode("latin-1")} 와 같은 바이트).
 */
public final class Http {
    private final String host;
    private final int port;

    public Http(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public Request get(String path) {
        return new Request("GET", path);
    }

    public Request post(String path) {
        return new Request("POST", path);
    }

    /** 응답. 헤더 이름은 소문자. */
    public record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.getFirst();
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            return Json.parse(body);
        }

        @Override
        public String toString() {
            return status + " " + text();
        }
    }

    public final class Request {
        private final String method;
        private final String path;
        private final Map<String, String> query = new LinkedHashMap<>();
        private final List<String[]> headers = new ArrayList<>();
        private byte[] body;
        private List<byte[]> chunks;

        Request(String method, String path) {
            this.method = method;
            this.path = path;
        }

        public Request query(String name, String value) {
            query.put(name, value);
            return this;
        }

        public Request header(String name, String value) {
            headers.add(new String[] {name, value});
            return this;
        }

        /** JSON 본문 (Content-Type: application/json). */
        public Request json(JsonNode node) {
            return content(Json.write(node), "application/json");
        }

        /** 파이썬 {@code json=...}: 본문 문자열 그대로 (Content-Type: application/json). */
        public Request json(String raw) {
            return content(raw, "application/json");
        }

        /** 파이썬 {@code content=...}: 본문 바이트 그대로. Content-Type 은 header() 로 직접 준다. */
        public Request content(byte[] raw) {
            this.body = raw;
            return this;
        }

        public Request content(String raw, String contentType) {
            header("Content-Type", contentType);
            return content(raw.getBytes(StandardCharsets.UTF_8));
        }

        /** Content-Length 없이 청크 전송 (파이썬 {@code content=generator}). */
        public Request chunked(List<byte[]> parts) {
            this.chunks = parts;
            return this;
        }

        public Response send() {
            StringBuilder target = new StringBuilder(path);
            if (!query.isEmpty()) {
                target.append('?');
                boolean first = true;
                for (Map.Entry<String, String> e : query.entrySet()) {
                    if (!first) {
                        target.append('&');
                    }
                    first = false;
                    target.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                            .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                }
            }
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append(':').append(port).append("\r\n");
            head.append("Connection: close\r\n");
            boolean hasLength = false;
            for (String[] h : headers) {
                head.append(h[0]).append(": ").append(h[1]).append("\r\n");
                hasLength |= h[0].equalsIgnoreCase("Content-Length");
            }
            if (chunks != null) {
                head.append("Transfer-Encoding: chunked\r\n");
            } else if (body != null && !hasLength) {
                head.append("Content-Length: ").append(body.length).append("\r\n");
            } else if (body == null && !hasLength && method.equals("POST")) {
                head.append("Content-Length: 0\r\n");
            }
            head.append("\r\n");
            try (Socket s = new Socket(host, port)) {
                s.setSoTimeout(120_000);
                OutputStream out = s.getOutputStream();
                try {
                    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                    if (chunks != null) {
                        for (byte[] c : chunks) {
                            out.write((Integer.toHexString(c.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                            out.write(c);
                            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        }
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    } else if (body != null) {
                        out.write(body);
                    }
                    out.flush();
                } catch (IOException e) {
                    // 서버가 본문을 다 읽기 전에 응답하고 연결을 닫을 수 있다 (413 등) → 응답은 그대로 읽는다
                }
                return parse(readAll(s.getInputStream()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        try {
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
            }
        } catch (java.net.SocketException e) {
            if (buf.size() == 0) {
                throw e;
            }
        }
        return buf.toByteArray();
    }

    static Response parse(byte[] raw) {
        int end = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        if (end < 0) {
            throw new IllegalStateException("HTTP 응답 헤더가 없습니다: " + new String(raw, StandardCharsets.ISO_8859_1));
        }
        String[] lines = new String(raw, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                headers.computeIfAbsent(lines[i].substring(0, c).strip().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(lines[i].substring(c + 1).strip());
            }
        }
        byte[] rest = Arrays.copyOfRange(raw, end + 4, raw.length);
        List<String> te = headers.get("transfer-encoding");
        if (te != null && te.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("chunked"))) {
            rest = dechunk(rest);
        } else if (headers.containsKey("content-length")) {
            int len = Integer.parseInt(headers.get("content-length").getFirst());
            rest = Arrays.copyOf(rest, Math.min(len, rest.length));
        }
        return new Response(status, headers, rest);
    }

    private static byte[] dechunk(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < b.length) {
            int eol = indexOf(b, "\r\n".getBytes(StandardCharsets.ISO_8859_1), i);
            if (eol < 0) {
                break;
            }
            String sizeLine = new String(b, i, eol - i, StandardCharsets.ISO_8859_1);
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).strip(), 16);
            if (size == 0) {
                break;
            }
            out.write(b, eol + 2, size);
            i = eol + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
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
