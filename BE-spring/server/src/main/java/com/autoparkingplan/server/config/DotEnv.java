package com.autoparkingplan.server.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 작업 폴더의 .env 파일 (파이썬 pydantic-settings 의 env_file=".env" 와 같은 우선순위: 환경변수 > .env > 기본값).
 * 형식: {@code KEY=VALUE}, {@code #} 주석, 값의 앞뒤 따옴표 제거, {@code export } 접두어 허용.
 */
public final class DotEnv {
    private DotEnv() {
    }

    public static Map<String, String> read(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).strip();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).strip();
                String v = line.substring(eq + 1).strip();
                if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                    v = v.substring(1, v.length() - 1);
                } else {
                    int hash = v.indexOf(" #");
                    if (hash >= 0) {
                        v = v.substring(0, hash).strip();
                    }
                }
                out.put(k.toUpperCase(Locale.ROOT), v);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 환경변수 → .env 순서로 찾는 조회 함수 (대소문자 무시). */
    public static java.util.function.Function<String, String> lookup(Map<String, String> env, Map<String, String> dotenv) {
        Map<String, String> upperEnv = new LinkedHashMap<>();
        env.forEach((k, v) -> upperEnv.put(k.toUpperCase(Locale.ROOT), v));
        return name -> {
            String key = name.toUpperCase(Locale.ROOT);
            String v = upperEnv.get(key);
            return v != null ? v : dotenv.get(key);
        };
    }
}
