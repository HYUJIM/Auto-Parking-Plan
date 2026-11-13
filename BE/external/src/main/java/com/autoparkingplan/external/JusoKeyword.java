package com.autoparkingplan.external;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 도로명주소 검색어 사전 검사. 상위 API 로 보내기 전에 서버가 먼저 걸러, 비정상 검색어가 밖으로 나가지 않게 한다.
 *
 * <p>배경: 2026-11-12 운영 서버 보안 점검 때 공격형 검색어가 그대로 중계되었고, 그 뒤 도로명주소 API 쪽에서 서버 IP 의
 * 접속이 막혔다. 그때까지는 가이드의 금지 문자 6개만 지우고 나머지는 그대로 보냈다.
 *
 * <pre>
 * 1. 허용 문자만 받는다: 한글, 영문, 숫자, 공백, 그리고 - . , ( ) [ ] ·
 *    그 밖의 문자(따옴표, 꺾쇠, 세미콜론, 슬래시, 제어 문자 등)가 하나라도 있으면 보내지 않는다 (E0013).
 * 2. 구두점 . , ( ) [ ] · 은 공백으로 바꾼다. 상위로 나가는 검색어는 한글·영문·숫자·공백·하이픈뿐이다.
 * 3. 하이픈 연속(--)과 가이드의 SQL 예약어가 있으면 보내지 않는다 (E0013).
 * 4. 가이드가 정한 형식 오류(E0005 빈 검색어, E0008 한 글자, E0009 숫자만, E0010 너무 김, E0011 긴 숫자)는
 *    결과가 정해져 있으므로 보내지 않고 같은 안내를 돌려준다.
 * </pre>
 *
 * <p>검사를 통과한 검색어를 다시 검사해도 같은 값이 나온다(컨트롤러와 클라이언트 두 곳에서 불러도 된다).
 */
public final class JusoKeyword {
    /** 허용하지 않는 문자·단어가 있을 때의 안내. */
    public static final String REJECT_MESSAGE = "검색어에 쓸 수 없는 문자나 단어가 들어 있습니다. 한글, 영문, 숫자와 기호 - . , ( ) 만 사용해 주세요.";

    /** 가이드 E0010: 한글 40자, 영문·숫자 80자 이하 (한글을 2로 센 길이 80). */
    static final int MAX_WEIGHTED_LENGTH = 80;

    /** 가이드의 SQL 예약어 (공식 예제 코드와 같이 부분 일치·대소문자 무시). */
    private static final List<String> SQL_WORDS =
            List.of("SELECT", "INSERT", "DELETE", "UPDATE", "CREATE", "DROP", "EXEC", "UNION", "FETCH", "DECLARE", "TRUNCATE");
    /** OR 는 낱말로 쓰였을 때만 막는다 (KOREA·FORUM 같은 건물명은 통과). */
    private static final Pattern OR_WORD = Pattern.compile("(?<![A-Z])OR(?![A-Z])");
    /** 가이드 E0011: 숫자 10자 이하. */
    private static final Pattern LONG_DIGITS = Pattern.compile("[0-9]{11,}");
    private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9 -]+");

    /**
     * 검사 결과.
     *
     * @param keyword 상위 API 로 보낼 검색어 (통과했을 때만, 아니면 null)
     * @param code    거절 사유의 가이드 오류코드 (통과하면 null)
     * @param message 사용자에게 보여줄 안내 (통과하면 null)
     */
    public record Check(String keyword, String code, String message) {
        public boolean ok() {
            return code == null;
        }
    }

    private JusoKeyword() {
    }

    public static Check check(String raw) {
        if (raw == null) {
            return reject("E0005");
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int letters = 0;   // 공백을 뺀 글자 수
        int weighted = 0;  // 한글을 2로 센 길이
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length();) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (isSeparator(cp)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            boolean hangul = isHangul(cp);
            if (!hangul && !isAsciiWordChar(cp)) {
                return new Check(null, "E0013", REJECT_MESSAGE);
            }
            if (pendingSpace) {
                sb.append(' ');
                weighted++;
                pendingSpace = false;
            }
            sb.appendCodePoint(cp);
            letters++;
            weighted += hangul ? 2 : 1;
        }
        if (letters == 0) {
            return reject("E0005");
        }
        String kw = sb.toString();
        String upper = kw.toUpperCase(Locale.ROOT);
        if (kw.contains("--") || OR_WORD.matcher(upper).find() || SQL_WORDS.stream().anyMatch(upper::contains)) {
            return new Check(null, "E0013", REJECT_MESSAGE);
        }
        if (letters < 2) {
            return reject("E0008");
        }
        if (DIGITS_ONLY.matcher(kw).matches()) {
            return reject("E0009");
        }
        if (weighted > MAX_WEIGHTED_LENGTH) {
            return reject("E0010");
        }
        if (LONG_DIGITS.matcher(kw).find()) {
            return reject("E0011");
        }
        return new Check(kw, null, null);
    }

    private static Check reject(String code) {
        return new Check(null, code, Juso.QUERY_ERROR_MESSAGES.get(code));
    }

    /** 공백, 그리고 공백으로 바꿔 보내는 구두점. */
    private static boolean isSeparator(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || Character.getType(cp) == Character.SPACE_SEPARATOR
                || cp == '.' || cp == ',' || cp == '(' || cp == ')' || cp == '[' || cp == ']'
                || cp == 0x00B7 || cp == 0x318D; // 가운뎃점 ·, 아래아 ㆍ
    }

    /** 한글 음절과 호환 자모(입력 중간 상태의 낱자). */
    private static boolean isHangul(int cp) {
        return (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0x3131 && cp <= 0x318E);
    }

    private static boolean isAsciiWordChar(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9') || cp == '-';
    }
}
