package com.autoparkingplan.external;

import java.math.BigInteger;
import java.util.Map;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;

/**
 * PNU(필지고유번호, 19자리) 생성·해석 (파이썬 {@code app.external.pnu}).
 *
 * <p>구조 (브이월드 연속지적도 레퍼런스 pnu 속성 설명과 동일):
 * <pre>
 *     시도(2) + 시군구(3) + 읍면동(3) + 리(2) + 필지구분(1) + 본번(4) + 부번(4) = 19자리
 *     - 앞 10자리 = 법정동코드 (도로명주소 검색 API 의 admCd)
 *     - 필지구분: '1' = 일반(대지), '2' = 산
 * </pre>
 * 출처: https://www.vworld.kr/dev/v4dv_2ddataguide2_s002.do?svcIde=cadastral (연속지적도 속성정보 pnu)
 *
 * <p>도로명주소 검색 API 의 산여부(mtYn)는 '0'=대지, '1'=산 이므로 PNU 의 필지구분으로 바꿀 때
 * '0' -&gt; '1', '1' -&gt; '2' 로 변환한다.
 * 출처: business.juso.go.kr 개발자센터 &gt; 도로명주소 검색 API 출력결과 (mtYn: 산여부(0:대지, 1:산))
 *
 * <p>참고: 국가중점데이터 컬럼정의서는 "특수지구분(가지번, 블럭지번 등)에 따라 19자리를 초과할 수 있음"이라고
 * 적고 있다. 이 클래스는 MVP 대상인 일반·산 지번(19자리)만 다루며, 그 외는 {@link IllegalArgumentException}
 * (파이썬 ValueError)으로 거절한다.
 *
 * <p>파이썬과 같이 숫자 판정은 {@code str.isdigit()} 기준이다 (전각 숫자 등 유니코드 숫자도 숫자로 본다).
 */
public final class Pnu {
    private static final Map<String, String> MT_YN_TO_LAND_TYPE = Map.of("0", "1", "1", "2");

    private Pnu() {
    }

    /**
     * 19자리 PNU 의 구성 요소 (파이썬 {@code parse_pnu} 의 dict).
     *
     * @param sidoCode    2자리
     * @param sigunguCode 5자리(시도+시군구)
     * @param emdCode     3자리
     * @param riCode      2자리
     * @param admCode     법정동코드 10자리
     * @param bjdongCode  법정동코드 뒤 5자리 (건축물대장 bjdongCd)
     * @param landType    '1'|'2'
     * @param jibun       '123-4' / '산 12-3'
     * @param mtYn        juso 형식 산여부 '0'|'1'
     * @param platGbCd    건축물대장 대지구분코드 '0'(대지)|'1'(산)
     * @param bun         본번 4자리 문자열
     * @param ji          부번 4자리 문자열
     */
    public record ParsedPnu(
            String pnu,
            String sidoCode,
            String sigunguCode,
            String emdCode,
            String riCode,
            String admCode,
            String bjdongCode,
            String landType,
            boolean isMountain,
            int mainNo,
            int subNo,
            String jibun,
            String mtYn,
            String platGbCd,
            String bun,
            String ji) {
    }

    private static int toLotNumber(Object value, String field) {
        if (value instanceof JsonNode n) {
            value = Json.toJava(n);
        }
        if (value instanceof Boolean) {
            throw new IllegalArgumentException(field + ": 숫자가 아닙니다: " + PyStr.repr(value));
        }
        BigInteger n;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            n = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger bi) {
            n = bi;
        } else {
            String s = Py.strip(PyStr.str(value));
            if (s.isEmpty()) {
                n = BigInteger.ZERO;
            } else if (!PyStr.isDigit(s)) {
                throw new IllegalArgumentException(field + ": 숫자가 아닙니다: " + PyStr.repr(value));
            } else {
                n = PyStr.intOfDigits(s);
            }
        }
        if (n.signum() < 0 || n.compareTo(BigInteger.valueOf(9999)) > 0) {
            throw new IllegalArgumentException(field + ": 0~9999 범위를 벗어났습니다: " + n);
        }
        return n.intValue();
    }

    /**
     * 도로명주소 API 의 admCd·mtYn·lnbrMnnm·lnbrSlno 로 19자리 PNU 를 만든다.
     *
     * @param admCd    법정동코드 10자리 (juso admCd).
     * @param mtYn     juso 산여부. '0'/0/false = 대지(→'1'), '1'/1/true = 산(→'2').
     * @param lnbrMnnm 지번 본번 (0~9999). 문자열·정수·원천 JSON 값.
     * @param lnbrSlno 지번 부번 (0~9999, 없으면 0).
     * @throws IllegalArgumentException 형식이 맞지 않을 때 (파이썬 ValueError).
     */
    public static String buildPnu(Object admCd, Object mtYn, Object lnbrMnnm, Object lnbrSlno) {
        String adm = Py.strip(PyStr.str(admCd));
        if (Py.len(adm) != 10 || !PyStr.isDigit(adm)) {
            throw new IllegalArgumentException("법정동코드는 10자리 숫자여야 합니다: " + PyStr.repr(admCd));
        }
        if (mtYn instanceof JsonNode n) {
            mtYn = Json.toJava(n);
        }
        String mtKey = mtYn instanceof Boolean b ? (b ? "1" : "0") : Py.strip(PyStr.str(mtYn));
        if (!MT_YN_TO_LAND_TYPE.containsKey(mtKey)) {
            throw new IllegalArgumentException("산여부(mtYn)는 '0' 또는 '1' 이어야 합니다: " + PyStr.repr(mtYn));
        }
        int main = toLotNumber(lnbrMnnm, "본번");
        int sub = toLotNumber(lnbrSlno, "부번");
        return adm + MT_YN_TO_LAND_TYPE.get(mtKey) + String.format("%04d%04d", main, sub);
    }

    /** 부번 생략 = 0. */
    public static String buildPnu(Object admCd, Object mtYn, Object lnbrMnnm) {
        return buildPnu(admCd, mtYn, lnbrMnnm, 0);
    }

    /** 19자리 숫자이고 필지구분이 1(일반) 또는 2(산)인지. */
    public static boolean isValidPnu(String pnu) {
        if (pnu == null || Py.len(pnu) != 19 || !PyStr.isDigit(pnu)) {
            return false;
        }
        String t = Py.slice(pnu, 10, 11);
        return t.equals("1") || t.equals("2");
    }

    /** '123-4', '123', '산 12-3' 형태의 지번 문자열. */
    public static String formatJibun(long main, long sub, boolean isMountain) {
        String body = sub != 0 ? main + "-" + sub : Long.toString(main);
        return isMountain ? "산 " + body : body;
    }

    public static String formatJibun(long main, long sub) {
        return formatJibun(main, sub, false);
    }

    /**
     * 19자리 PNU 를 구성 요소로 나눈다.
     *
     * @throws IllegalArgumentException 19자리 숫자가 아니거나 필지구분이 1·2 가 아닐 때 (파이썬 ValueError).
     */
    public static ParsedPnu parsePnu(String pnu) {
        if (pnu == null) {
            throw new IllegalArgumentException("PNU 는 문자열이어야 합니다: None");
        }
        String p = Py.strip(pnu);
        if (Py.len(p) != 19 || !PyStr.isDigit(p)) {
            throw new IllegalArgumentException("PNU 는 19자리 숫자여야 합니다: " + PyStr.reprString(pnu));
        }
        String landType = Py.slice(p, 10, 11);
        if (!landType.equals("1") && !landType.equals("2")) {
            throw new IllegalArgumentException("PNU 필지구분은 1(일반) 또는 2(산)이어야 합니다: " + PyStr.reprString(pnu));
        }
        int main = PyStr.intOfDigits(Py.slice(p, 11, 15)).intValue();
        int sub = PyStr.intOfDigits(Py.slice(p, 15, 19)).intValue();
        boolean isMountain = landType.equals("2");
        return new ParsedPnu(
                p,
                Py.slice(p, 0, 2),
                Py.slice(p, 0, 5),
                Py.slice(p, 5, 8),
                Py.slice(p, 8, 10),
                Py.slice(p, 0, 10),
                Py.slice(p, 5, 10),
                landType,
                isMountain,
                main,
                sub,
                formatJibun(main, sub, isMountain),
                isMountain ? "1" : "0",
                isMountain ? "1" : "0",
                Py.slice(p, 11, 15),
                Py.slice(p, 15, 19));
    }
}
