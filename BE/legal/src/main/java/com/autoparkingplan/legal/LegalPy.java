package com.autoparkingplan.legal;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Py;

/**
 * legal 모듈에서 파이썬판과 같은 결과를 내기 위한 도우미 ({@code common.Py} 에 없는 것만).
 *
 * <ul>
 *   <li>정규식: 파이썬 str 패턴의 {@code \s} 는 {@code str.isspace()} 문자 전부(U+001C~U+001F 포함)라
 *       자바 {@code UNICODE_CHARACTER_CLASS} 의 {@code \s}(Unicode White_Space)와 다르다 → {@link #re(String)} 이 바꿔 쓴다.
 *       {@code .}/{@code $} 의 줄끝은 파이썬처럼 {@code \n} 하나만 ({@code UNIX_LINES}).</li>
 *   <li>{@code repr(str)}, {@code str(None)} 표기 (오류·누락 사유 문구에 들어간다)</li>
 *   <li>{@code urllib.parse.urlencode/quote/quote_plus/unquote/parse_qs}</li>
 *   <li>{@code int(str)}, {@code str.isdigit()}</li>
 * </ul>
 */
public final class LegalPy {
    private LegalPy() {
    }

    /** 한국 표준시 (+09:00). */
    public static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** 파이썬 {@code dt.datetime.now(KST).replace(microsecond=0).isoformat()}. */
    public static String nowKstIso() {
        return isoSeconds(OffsetDateTime.now(KST));
    }

    /** 초 단위까지의 ISO-8601 (파이썬 {@code isoformat()} 과 같은 모양, 예: 2026-11-06T12:22:45+09:00). */
    public static String isoSeconds(OffsetDateTime t) {
        return t.truncatedTo(ChronoUnit.SECONDS).format(ISO_SECONDS);
    }

    // ── 파일 이름 ─────────────────────────────────────────────

    /**
     * 저장할 파일 이름 검사 — 화면·API 에서 읽은 값(일련번호 등)이 섞인 이름이 폴더 구분자·상위 경로(..)·제어 문자로 저장 폴더 밖을
     * 가리키지 못하게 한다. 그대로 돌려주거나 IllegalArgumentException.
     */
    public static String requireSafeFileName(String name) {
        boolean bad = name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.chars().anyMatch(c -> c < 0x20 || c == 0x7F);
        if (bad) {
            throw new IllegalArgumentException("파일 이름으로 쓸 수 없습니다: " + repr(name));
        }
        return name;
    }

    // ── 정규식 ────────────────────────────────────────────────

    /** 파이썬 {@code str.isspace()} 문자 (정규식 문자 클래스 안에 넣는 형태). */
    static final String WS_CHARS = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";

    /** 파이썬 {@code re.compile(pattern)} (str 패턴) 과 같은 뜻의 자바 패턴. */
    public static Pattern re(String pythonPattern) {
        return re(pythonPattern, 0);
    }

    /**
     * 파이썬 {@code re.compile(pattern, flags)} 과 같은 뜻의 자바 패턴.
     * {@code \s}·{@code \S} 를 파이썬 공백 정의로 바꾸고, 문자 클래스 안의 {@code [}·{@code &&} 를 글자로 취급하게 고친다.
     *
     * @param javaFlags {@link Pattern#CASE_INSENSITIVE}(re.I), {@link Pattern#DOTALL}(re.S), {@link Pattern#MULTILINE}(re.M)
     */
    public static Pattern re(String pythonPattern, int javaFlags) {
        StringBuilder sb = new StringBuilder();
        boolean inClass = false;
        int classBodyStart = -1; // 문자 클래스 본문이 시작하는 sb 위치 ('[' 또는 '[^' 다음)
        int n = pythonPattern.length();
        for (int i = 0; i < n; i++) {
            char c = pythonPattern.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char d = pythonPattern.charAt(i + 1);
                i++;
                if (d == 's') {
                    sb.append(inClass ? WS_CHARS : "[" + WS_CHARS + "]");
                } else if (d == 'S') {
                    if (inClass) {
                        throw new IllegalArgumentException("문자 클래스 안의 \\S 는 지원하지 않습니다: " + pythonPattern);
                    }
                    sb.append("[^").append(WS_CHARS).append(']');
                } else {
                    sb.append(c).append(d);
                }
                continue;
            }
            if (inClass) {
                if (c == '^' && sb.length() == classBodyStart && sb.charAt(sb.length() - 1) == '[') {
                    sb.append(c);
                    classBodyStart = sb.length();
                } else if (c == ']' && sb.length() == classBodyStart) {
                    sb.append("\\]"); // 파이썬: 클래스 첫 글자 ']' 는 글자
                } else if (c == ']') {
                    inClass = false;
                    sb.append(c);
                } else if (c == '[') {
                    sb.append("\\["); // 파이썬: 클래스 안의 '[' 는 글자 (자바는 중첩 클래스)
                } else if (c == '&' && i + 1 < n && pythonPattern.charAt(i + 1) == '&') {
                    sb.append("\\&"); // 자바의 '&&'(교집합)를 글자로
                } else {
                    sb.append(c);
                }
                continue;
            }
            sb.append(c);
            if (c == '[') {
                inClass = true;
                classBodyStart = sb.length();
            }
        }
        return Pattern.compile(sb.toString(), javaFlags | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }

    // ── 문자열 ────────────────────────────────────────────────

    /** 파이썬 {@code s.split(sep)} (구분자 지정, 빈 조각 유지). */
    public static List<String> split(String s, String sep) {
        if (sep.isEmpty()) {
            throw new IllegalArgumentException("empty separator");
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = s.indexOf(sep, start)) >= 0) {
            out.add(s.substring(start, idx));
            start = idx + sep.length();
        }
        out.add(s.substring(start));
        return out;
    }

    /** 파이썬 {@code a or b} (문자열: None·빈 문자열이면 b). */
    public static String or(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    /** 파이썬 문자열 참·거짓 ({@code bool(s)}). */
    public static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    /** 파이썬 {@code str(x)} — None 은 'None'. */
    public static String str(Object o) {
        if (o == null) {
            return "None";
        }
        if (o instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (o instanceof Double d) {
            return Py.floatRepr(d);
        }
        if (o instanceof Collection<?> c) {
            return repr(c);
        }
        return o.toString();
    }

    /** 파이썬 {@code repr(x)} — str, None, bool, 정수, 문자열 목록. */
    public static String repr(Object o) {
        if (o == null) {
            return "None";
        }
        if (o instanceof String s) {
            return reprString(s);
        }
        if (o instanceof Collection<?> c) {
            StringJoiner j = new StringJoiner(", ", "[", "]");
            for (Object x : c) {
                j.add(repr(x));
            }
            return j.toString();
        }
        return str(o);
    }

    /** 파이썬 {@code repr(str)}: 따옴표 선택, 출력 불가 문자는 16진 이스케이프. */
    public static String reprString(String s) {
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        StringBuilder sb = new StringBuilder();
        sb.append(quote);
        s.codePoints().forEach(cp -> {
            if (cp == quote || cp == '\\') {
                sb.append('\\').appendCodePoint(cp);
            } else if (cp == '\t') {
                sb.append("\\t");
            } else if (cp == '\n') {
                sb.append("\\n");
            } else if (cp == '\r') {
                sb.append("\\r");
            } else if (cp < 0x20 || cp == 0x7F) {
                sb.append(String.format("\\x%02x", cp));
            } else if (cp < 0x7F || isPrintable(cp)) {
                sb.appendCodePoint(cp);
            } else if (cp <= 0xFF) {
                sb.append(String.format("\\x%02x", cp));
            } else if (cp <= 0xFFFF) {
                sb.append(String.format("\\u%04x", cp));
            } else {
                sb.append(String.format("\\U%08x", cp));
            }
        });
        sb.append(quote);
        return sb.toString();
    }

    /** 파이썬 {@code str.isprintable()} 의 글자 판정 (Cc·Cf·Cs·Co·Cn·Zl·Zp·Zs 는 출력 불가, 단 공백 ' ' 은 출력 가능). */
    static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                 Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** 파이썬 {@code f"{n:,}"} (세 자리마다 쉼표). */
    public static String thousands(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    // ── 숫자 ──────────────────────────────────────────────────

    /** 파이썬 {@code str.isdigit()}: 비어 있지 않고 모든 글자가 숫자(Nd 또는 Numeric_Type=Digit — 위첨자·동그라미 숫자 등). */
    public static boolean isDigit(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return s.codePoints().allMatch(cp -> Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER
                || (Character.getType(cp) == Character.OTHER_NUMBER && Character.getNumericValue(cp) >= 0
                && Character.getNumericValue(cp) <= 9));
    }

    /**
     * 파이썬 {@code int(s)} (10진). 앞뒤 공백·부호·밑줄 구분 허용, 유니코드 십진 숫자(Nd) 허용.
     *
     * @throws IllegalArgumentException 파이썬 ValueError 와 같은 경우
     */
    public static BigInteger pyInt(String s) {
        String t = Py.strip(s);
        StringBuilder digits = new StringBuilder();
        int i = 0;
        boolean neg = false;
        if (!t.isEmpty() && (t.charAt(0) == '+' || t.charAt(0) == '-')) {
            neg = t.charAt(0) == '-';
            i = 1;
        }
        boolean lastUnderscore = true; // 처음 글자는 밑줄일 수 없다
        int count = 0;
        while (i < t.length()) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '_') {
                if (lastUnderscore) {
                    throw invalidInt(s);
                }
                lastUnderscore = true;
                continue;
            }
            if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER) {
                throw invalidInt(s);
            }
            digits.append((char) ('0' + Character.digit(cp, 10)));
            lastUnderscore = false;
            count++;
        }
        if (count == 0 || lastUnderscore) {
            throw invalidInt(s);
        }
        BigInteger v = new BigInteger(digits.toString());
        return neg ? v.negate() : v;
    }

    private static IllegalArgumentException invalidInt(String s) {
        return new IllegalArgumentException("invalid literal for int() with base 10: " + reprString(s));
    }

    // ── URL (urllib.parse) ─────────────────────────────────────

    private static final String ALWAYS_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-~";

    /** 파이썬 {@code urllib.parse.quote(s, safe)} (UTF-8, 대문자 16진수). */
    public static String quote(String s, String safe) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v < 0x80 && (ALWAYS_SAFE.indexOf(v) >= 0 || safe.indexOf(v) >= 0)) {
                sb.append((char) v);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(v >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** 파이썬 {@code urllib.parse.quote(s)} (safe='/'). */
    public static String quote(String s) {
        return quote(s, "/");
    }

    /** 파이썬 {@code urllib.parse.quote_plus(s)} (safe='', 공백 → '+'). */
    public static String quotePlus(String s) {
        if (s.indexOf(' ') >= 0) {
            return quote(s, " ").replace(' ', '+');
        }
        return quote(s, "");
    }

    /** 파이썬 {@code urllib.parse.urlencode(dict)} (값은 문자열, 순서 유지). */
    public static String urlencode(Map<String, String> params) {
        StringJoiner j = new StringJoiner("&");
        params.forEach((k, v) -> j.add(quotePlus(k) + "=" + quotePlus(v)));
        return j.toString();
    }

    /** 파이썬 {@code urllib.parse.unquote(s)} (UTF-8, 잘못된 바이트는 대체 문자). */
    public static String unquote(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) >= 0x80) { // 비 ASCII 는 그대로
                out.append(s.charAt(i++));
                continue;
            }
            int j = i;
            while (j < n && s.charAt(j) < 0x80) {
                j++;
            }
            out.append(unquoteAscii(s.substring(i, j)));
            i = j;
        }
        return out.toString();
    }

    private static String unquoteAscii(String run) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < run.length()) {
            char c = run.charAt(i);
            if (c == '%' && i + 2 < run.length() && isHex(run, i + 1) && isHex(run, i + 2)) {
                bytes.write(Integer.parseInt(run.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                bytes.write(c);
                i++;
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isHex(String s, int i) {
        return i < s.length() && Character.digit(s.charAt(i), 16) >= 0 && s.charAt(i) < 0x80;
    }

    /** 파이썬 {@code urllib.parse.parse_qs(qs)} (keep_blank_values=False, 구분자 '&'). */
    public static Map<String, List<String>> parseQs(String qs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (qs == null || qs.isEmpty()) {
            return out;
        }
        for (String nameValue : split(qs, "&")) {
            if (nameValue.isEmpty()) {
                continue;
            }
            int eq = nameValue.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String v = nameValue.substring(eq + 1);
            if (v.isEmpty()) {
                continue;
            }
            String name = unquote(nameValue.substring(0, eq).replace('+', ' '));
            String value = unquote(v.replace('+', ' '));
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    /** 파이썬 {@code urllib.parse.urlsplit(url).query}. */
    public static String urlsplitQuery(String url) {
        int i = 0;
        while (i < url.length() && url.charAt(i) <= 0x20) {
            i++;
        }
        String u = url.substring(i).replace("\t", "").replace("\r", "").replace("\n", "");
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        int q = u.indexOf('?');
        return q < 0 ? "" : u.substring(q + 1);
    }

    /** 예외의 파이썬 {@code repr(e)} 모양 ({@code 이름('메시지')}). */
    public static String exceptionRepr(Throwable e) {
        String name = e instanceof PyNamed p ? p.pythonName() : e.getClass().getSimpleName();
        String msg = e.getMessage();
        return name + "(" + (msg == null ? "" : reprString(msg)) + ")";
    }

    /** 파이썬 예외 이름을 가진 예외 (repr 표기용). */
    public interface PyNamed {
        String pythonName();
    }
}
