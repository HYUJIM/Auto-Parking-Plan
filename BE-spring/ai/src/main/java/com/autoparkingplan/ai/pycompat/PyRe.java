package com.autoparkingplan.ai.pycompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파이썬 {@code re} 패턴(str 패턴, 플래그 없음)을 같은 뜻의 자바 {@link Pattern} 으로 바꾼다.
 *
 * <ul>
 *   <li>{@code \d} = 유니코드 Nd (자바 {@link Pattern#UNICODE_CHARACTER_CLASS} 와 같음)</li>
 *   <li>{@code \s} = 파이썬 {@code str.isspace()} 문자. 자바 유니코드 {@code \s} 에 U+001C~U+001F 를 더한다</li>
 *   <li>{@code .} 와 {@code $} 의 줄끝 = {@code \n} 만 ({@link Pattern#UNIX_LINES})</li>
 *   <li>{@code (?P<name>...)} → {@code (?<name>...)}, {@code (?P=name)} → {@code \k<name>}, {@code \Z} → {@code \z}</li>
 *   <li>{@code \w \W \b \B} 는 파이썬과 자바의 유니코드 정의가 달라(결합 문자·No 범주) 지원하지 않는다 — 쓰면 예외</li>
 * </ul>
 * 매치 위치는 UTF-16 단위다. 파이썬 위치(코드포인트)가 필요하면 {@code Py.cpIndex} 로 바꾼다.
 */
public final class PyRe {
    private static final String PY_SPACE_IN_CLASS = "\\s\\x{1c}-\\x{1f}";
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES;

    private PyRe() {
    }

    public static Pattern compile(String pythonPattern) {
        return Pattern.compile(translate(pythonPattern), FLAGS);
    }

    /** 파이썬 패턴 → 자바 패턴 문자열. */
    public static String translate(String p) {
        StringBuilder out = new StringBuilder(p.length() + 16);
        boolean inClass = false;
        int i = 0;
        int n = p.length();
        while (i < n) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char e = p.charAt(i + 1);
                switch (e) {
                    case 's' -> out.append(inClass ? PY_SPACE_IN_CLASS : "[" + PY_SPACE_IN_CLASS + "]");
                    case 'S' -> {
                        if (inClass) {
                            throw new UnsupportedOperationException("문자 집합 안의 \\S 는 지원하지 않습니다: " + p);
                        }
                        out.append("[^" + PY_SPACE_IN_CLASS + "]");
                    }
                    case 'w', 'W', 'b', 'B' ->
                            throw new UnsupportedOperationException("파이썬과 뜻이 다른 \\" + e + " 는 지원하지 않습니다: " + p);
                    case 'Z' -> out.append("\\z");
                    default -> out.append(c).append(e);
                }
                i += 2;
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                } else if (c == '[') {
                    out.append('\\'); // 파이썬은 집합 안의 '[' 를 글자로 본다 (자바는 중첩 집합)
                } else if (c == '&' && i + 1 < n && p.charAt(i + 1) == '&') {
                    out.append('\\'); // 자바의 교집합 연산자로 읽히지 않게
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                i++;
                // 집합 첫 글자 ']' (또는 '^' 다음 ']') 는 파이썬에서 글자
                if (i < n && p.charAt(i) == '^') {
                    out.append('^');
                    i++;
                }
                if (i < n && p.charAt(i) == ']') {
                    out.append("\\]");
                    i++;
                }
                continue;
            }
            if (c == '(' && p.startsWith("(?P<", i)) {
                out.append("(?<");
                i += 4;
                continue;
            }
            if (c == '(' && p.startsWith("(?P=", i)) {
                int close = p.indexOf(')', i);
                out.append("\\k<").append(p, i + 4, close).append('>');
                i = close + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 파이썬 {@code re.sub(pattern, repl, s)} — repl 은 그대로의 문자열 (역참조 없음). */
    public static String sub(Pattern pattern, String literalReplacement, String s) {
        return pattern.matcher(s).replaceAll(Matcher.quoteReplacement(literalReplacement));
    }

    /** 파이썬 {@code re.search(pattern, s) is not None}. */
    public static boolean search(Pattern pattern, String s) {
        return pattern.matcher(s).find();
    }
}
