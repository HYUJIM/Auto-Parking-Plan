package com.autoparkingplan.server.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * DATABASE_URL → JDBC 연결 정보.
 *
 * <p>파이썬판과 같은 값을 그대로 쓸 수 있게 SQLAlchemy 형식도 받는다.
 * <ul>
 *   <li>{@code jdbc:...} 그대로</li>
 *   <li>{@code postgresql://}, {@code postgresql+psycopg://}, {@code postgres://} → {@code jdbc:postgresql://} (+ 사용자·비밀번호 분리, 질의 문자열 유지)</li>
 *   <li>{@code sqlite:///경로} → 같은 경로의 H2 파일 DB (로컬·테스트용; SQLite 는 쓰지 않는다), {@code sqlite://} → H2 메모리 DB</li>
 * </ul>
 * H2 에는 {@code NON_KEYWORDS=KEY,VALUE} (컬럼 이름 key 때문)와, 파일 DB 이면 {@code AUTO_SERVER=TRUE}
 * (API 서버·검토 워커·CLI 가 같은 파일을 동시에 여는 경우)를 붙인다.
 */
public final class DatabaseUrls {
    private DatabaseUrls() {
    }

    public record JdbcTarget(String url, String user, String password) {
        public boolean isPostgres() {
            return url.startsWith("jdbc:postgresql:");
        }

        public boolean isH2() {
            return url.startsWith("jdbc:h2:");
        }

        /** 로그용: 비밀번호를 뺀 연결 대상. */
        public String describe() {
            String u = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            return u.contains(";") ? u.substring(0, u.indexOf(';')) : u;
        }
    }

    public static String defaultLocalUrl(Path root) {
        return "jdbc:h2:file:" + root.resolve("var").resolve("app").toAbsolutePath().toString().replace('\\', '/');
    }

    public static JdbcTarget parse(String databaseUrl) {
        String u = databaseUrl.strip();
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:h2:")) {
            return new JdbcTarget(h2Options(u), "sa", "");
        }
        if (lower.startsWith("jdbc:")) {
            return new JdbcTarget(u, null, null);
        }
        if (lower.startsWith("sqlite:")) {
            String rest = u.substring("sqlite:".length());
            if (rest.equals("//") || rest.equals("///:memory:") || rest.isEmpty()) {
                return new JdbcTarget(h2Options("jdbc:h2:mem:app;DB_CLOSE_DELAY=-1"), "sa", "");
            }
            String path = rest.startsWith("///") ? rest.substring(3) : rest.replaceFirst("^//", "");
            // 'sqlite:////abs/path' (유닉스 절대경로) → '/abs/path', 'sqlite:///C:/x' (윈도우) → 'C:/x', 'sqlite:///rel' → 'rel'
            if (path.endsWith(".sqlite3")) {
                path = path.substring(0, path.length() - ".sqlite3".length());
            }
            return new JdbcTarget(h2Options("jdbc:h2:file:" + Path.of(path).toAbsolutePath().toString().replace('\\', '/')), "sa", "");
        }
        if (lower.startsWith("postgresql") || lower.startsWith("postgres:")) {
            String norm = u.replaceFirst("^[A-Za-z0-9+]+://", "postgresql://");
            URI uri = URI.create(norm);
            String user = null;
            String password = null;
            String info = uri.getRawUserInfo();
            if (info != null) {
                int c = info.indexOf(':');
                user = dec(c < 0 ? info : info.substring(0, c));
                password = c < 0 ? null : dec(info.substring(c + 1));
            }
            String host = uri.getHost() == null ? "localhost" : uri.getHost();
            int port = uri.getPort() < 0 ? 5432 : uri.getPort();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String q = uri.getRawQuery();
            return new JdbcTarget("jdbc:postgresql://" + host + ":" + port + path + (q != null ? "?" + q : ""), user, password);
        }
        throw new AppSettings.InvalidSettings("DATABASE_URL 형식을 알 수 없습니다 (jdbc:, postgresql://, sqlite:/// 중 하나)");
    }

    private static String h2Options(String url) {
        String out = url;
        String up = url.toUpperCase(Locale.ROOT);
        if (!up.contains("NON_KEYWORDS=")) {
            out += ";NON_KEYWORDS=KEY,VALUE";
        }
        if (up.startsWith("JDBC:H2:FILE:") && !up.contains("AUTO_SERVER=")) {
            out += ";AUTO_SERVER=TRUE";
        }
        return out;
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
