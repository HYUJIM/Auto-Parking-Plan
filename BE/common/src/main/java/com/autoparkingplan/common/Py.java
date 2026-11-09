package com.autoparkingplan.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 파이썬판(파이썬판 app)과 같은 결과를 내기 위한 문자열·숫자 도우미.
 *
 * <p>자바 기본 동작과 파이썬이 다른 곳만 모았다.
 * <ul>
 *   <li>{@code str.strip()/split()} 의 공백 = 유니코드 공백 전부 (자바 {@code String.strip()} 은 NBSP(U+00A0)를 공백으로 보지 않는다)</li>
 *   <li>문자열 길이·위치 = 코드포인트 단위 (자바 {@code length()} 는 UTF-16 단위). 근거 인용 위치(char_start/char_end)가 파이썬과 같아야 한다</li>
 *   <li>{@code round()} = 이진 값 그대로의 은행원 반올림 (자바 {@code Math.round} 는 0.5 올림)</li>
 *   <li>{@code repr(float)} = 가장 짧은 표기 + 파이썬 지수 표기 규칙 (자바 {@code Double.toString} 은 1.0E-5 처럼 다르게 쓴다)</li>
 * </ul>
 */
public final class Py {
    private Py() {
    }

    // ── 공백 ──────────────────────────────────────────────────

    /** 파이썬 {@code str.isspace()} 와 같은 공백 판정 (코드포인트 하나). */
    public static boolean isSpace(int cp) {
        return switch (cp) {
            case '\t', '\n', 0x0B, '\f', '\r', 0x1C, 0x1D, 0x1E, 0x1F, ' ', 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F,
                 0x3000 -> true;
            default -> cp >= 0x2000 && cp <= 0x200A;
        };
    }

    /** 파이썬 {@code s.isspace()}: 비어 있지 않고 모두 공백. */
    public static boolean isSpace(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(Py::isSpace);
    }

    public static String strip(String s) {
        return rstrip(lstrip(s));
    }

    public static String lstrip(String s) {
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!isSpace(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return s.substring(i);
    }

    public static String rstrip(String s) {
        int j = s.length();
        while (j > 0) {
            int cp = s.codePointBefore(j);
            if (!isSpace(cp)) {
                break;
            }
            j -= Character.charCount(cp);
        }
        return s.substring(0, j);
    }

    /** 파이썬 {@code s.strip(chars)}: 주어진 문자들을 양끝에서 제거. */
    public static String strip(String s, String chars) {
        int i = 0;
        int j = s.length();
        while (i < j && chars.indexOf(s.codePointAt(i)) >= 0) {
            i += Character.charCount(s.codePointAt(i));
        }
        while (j > i && chars.indexOf(s.codePointBefore(j)) >= 0) {
            j -= Character.charCount(s.codePointBefore(j));
        }
        return s.substring(i, j);
    }

    /** 파이썬 {@code s.split()} (인자 없음): 연속 공백으로 나누고 빈 조각은 버린다. */
    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (isSpace(cp)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.appendCodePoint(cp);
            }
        });
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 파이썬 {@code s.splitlines()} (keepends=False). */
    public static List<String> splitlines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int eol = switch (c) {
                case '\n', 0x0B, '\f', 0x1C, 0x1D, 0x1E, 0x85, 0x2028, 0x2029 -> 1;
                case '\r' -> (i + 1 < n && s.charAt(i + 1) == '\n') ? 2 : 1;
                default -> 0;
            };
            if (eol > 0) {
                out.add(s.substring(start, i));
                i += eol;
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) {
            out.add(s.substring(start));
        }
        return out;
    }

    // ── 코드포인트 단위 길이·자르기 ─────────────────────────────

    /** 파이썬 {@code len(s)}. */
    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** 파이썬 {@code s[start:end]} (코드포인트 단위, 음수·범위 밖 허용). */
    public static String slice(String s, Integer start, Integer end) {
        int n = len(s);
        int a = clampIndex(start == null ? 0 : start, n);
        int b = clampIndex(end == null ? n : end, n);
        if (b <= a) {
            return "";
        }
        if (n == s.length()) { // 보조 평면 문자가 없으면 그대로
            return s.substring(a, b);
        }
        return s.substring(s.offsetByCodePoints(0, a), s.offsetByCodePoints(0, b));
    }

    private static int clampIndex(int i, int n) {
        if (i < 0) {
            i += n;
        }
        return Math.max(0, Math.min(i, n));
    }

    /** 파이썬 {@code s.find(sub, start)} — 코드포인트 위치를 돌려준다. 없으면 -1. */
    public static int find(String s, String sub, int start) {
        if (len(s) == s.length()) {
            return s.indexOf(sub, Math.max(0, start));
        }
        int from = s.offsetByCodePoints(0, Math.max(0, Math.min(start, len(s))));
        int idx = s.indexOf(sub, from);
        return idx < 0 ? -1 : s.codePointCount(0, idx);
    }

    public static int find(String s, String sub) {
        return find(s, sub, 0);
    }

    /** UTF-16 위치 → 코드포인트 위치 (정규식 매치 위치를 파이썬 위치로 바꿀 때). */
    public static int cpIndex(String s, int utf16Index) {
        return s.codePointCount(0, utf16Index);
    }

    // ── 숫자 ──────────────────────────────────────────────────

    /** 파이썬 {@code round(x, ndigits)}: 이진 값 그대로를 기준으로 한 은행원 반올림. */
    public static double round(double x, int ndigits) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        double r = new BigDecimal(x).setScale(ndigits, RoundingMode.HALF_EVEN).doubleValue();
        return r == 0.0 ? Math.copySign(0.0, x) : r;
    }

    /** 파이썬 {@code round(x)} (자릿수 없음 → 정수, 은행원 반올림). */
    public static long round(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            throw new ArithmeticException("cannot convert float " + x + " to integer");
        }
        return (long) Math.rint(x);
    }

    /** 파이썬 {@code repr(float)} / {@code str(float)}. */
    public static String floatRepr(double x) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        if (x == 0.0) {
            return (1.0 / x < 0) ? "-0.0" : "0.0";
        }
        // JDK 19+ 의 Double.toString 은 되돌릴 수 있는 가장 짧은 십진 표기를 고른다 (파이썬 repr 과 같은 자릿수)
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(x))).stripTrailingZeros();
        if (bd.precision() == 2) {
            // 자바는 가장 짧은 표기가 한 자리여도 두 자리로 쓴다 (예: 4.9E-324). 한 자리로 되돌아가면 파이썬처럼 한 자리.
            BigDecimal one = bd.round(new java.math.MathContext(1, RoundingMode.HALF_EVEN));
            if (one.doubleValue() == Math.abs(x)) {
                bd = one.stripTrailingZeros();
            }
        }
        String digits = bd.unscaledValue().toString();
        int decpt = digits.length() - bd.scale(); // 소수점이 digits 앞에서 몇 번째 자리 뒤에 오는가
        StringBuilder sb = new StringBuilder();
        if (x < 0) {
            sb.append('-');
        }
        if (decpt <= -4 || decpt > 16) {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) {
                sb.append('.').append(digits, 1, digits.length());
            }
            int exp = decpt - 1;
            sb.append('e').append(exp < 0 ? '-' : '+');
            String e = Integer.toString(Math.abs(exp));
            if (e.length() < 2) {
                sb.append('0');
            }
            sb.append(e);
        } else if (decpt <= 0) {
            sb.append("0.").append("0".repeat(-decpt)).append(digits);
        } else if (decpt >= digits.length()) {
            sb.append(digits).append("0".repeat(decpt - digits.length())).append(".0");
        } else {
            sb.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
        }
        return sb.toString();
    }

    /** 파이썬 {@code f"{x:g}"} (유효숫자 6자리, 불필요한 0 제거). 오류 문구의 숫자 표기용. */
    public static String formatG(double x) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        if (x == 0.0) {
            return (1.0 / x < 0) ? "-0" : "0";
        }
        BigDecimal bd = new BigDecimal(x).round(new java.math.MathContext(6, RoundingMode.HALF_EVEN));
        int exp = bd.precision() - bd.scale() - 1;
        if (exp < -4 || exp >= 6) {
            BigDecimal mant = bd.movePointLeft(exp).stripTrailingZeros();
            String m = mant.toPlainString();
            String e = Integer.toString(Math.abs(exp));
            return m + "e" + (exp < 0 ? "-" : "+") + (e.length() < 2 ? "0" + e : e);
        }
        String s = bd.stripTrailingZeros().toPlainString();
        return s;
    }

    /** 파이썬 {@code int(x)} (0 쪽으로 버림). */
    public static long truncate(double x) {
        return (long) x;
    }

    // ── 해시 ──────────────────────────────────────────────────

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
