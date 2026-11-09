package com.autoparkingplan.external;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.autoparkingplan.common.Py;

/**
 * 용도지역·지구·구역 코드 ↔ 법정 명칭 사전과 지정 종류(kind) 분류 (파이썬 {@code app.external.zoning_codes}).
 *
 * <p>설계서 11장 리스크 "토지이용계획 API 응답의 용도지역 명칭이 규칙 조건과 다름 → 명칭 정규화 사전(UQA122 ↔
 * 법정 명칭)" 대응용. DB 의 정규화 테이블을 채울 때 이 사전을 시드로 쓸 수 있다.
 *
 * <p>수록 원칙: 공식 출처에서 확인한 코드만 넣는다.
 * <pre>
 *   [S1] 행정표준코드관리시스템(행정안전부) 코드명 '용도지역지구구분'(코드종 00316), 2026-11-06 조회
 *        https://www.code.go.kr/stdcode/normalCodeL.do  (codeseId=00316)
 *        검색 화면: https://www.code.go.kr/stdcodesrch/codeNMSearchL.do  (예: 코드값의미 '제2종일반주거지역' → UQA122)
 *        브이월드 국가중점데이터 컬럼정의서도 용도지역지구코드는 "행정표준코드관리시스템에서 확인"하라고 안내한다.
 *   [S2] 브이월드 국가중점데이터 컬럼정의서(배포용 xlsx) 토지이용계획정보 샘플: 용도지역지구코드 'UQA01X' = '도시지역'
 *        https://www.vworld.kr/contents/국가중점데이터_컬럼정의서_배포용.xlsx
 *        (실제 getLandUseAttr 응답도 도시지역을 UQA01X 로 준다. [S1]의 UQA001 과 다르므로 둘 다 수록)
 *   [S3] 브이월드 토지이용계획속성조회(getLandUseAttr) 레퍼런스 출력 샘플: 'UQP110' = '아파트지구'
 *        https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=51
 * </pre>
 *
 * <p>[S1]의 1,467개 코드 가운데 국토계획법 용도지역(UQA~UQE)·용도지구(UQF~UQP)·용도구역 등(UQQ) 전부와,
 * 실제 응답에 자주 나오는 타 법령 지정(개발제한구역, 가축사육제한구역, 교육환경보호구역 등) 일부만 수록했다.
 * 사전에 없는 코드는 명칭 기준으로 분류하고, 그래도 모르면 'other' 로 둔다.
 *
 * <p>kind 분류 규칙 ({@link LandUseDesignation#kind()})
 * <ul>
 *   <li>'zoning'  : 국토계획법 시행령 제30조의 세분 용도지역 21개(도시지역 16 + 보전·생산·계획관리 + 농림 + 자연환경보전)와
 *                   세분 전 '관리지역'. 상위 묶음 명칭(도시지역·주거지역·일반주거지역·상업지역 등)은 건폐율·용적률을
 *                   정하지 않으므로 'zoning' 으로 보지 않고 'other' 로 둔다(한 필지에 'zoning' 이 둘 이상이면 실제 걸침).</li>
 *   <li>'district': 국토계획법 용도지구(경관·고도·방화·방재·보호·취락·개발진흥·특정용도제한·복합용도 등과
 *                   과거 미관·시설보호지구, 조례 지구). [S1] UQF~UQP 코드 전체.</li>
 *   <li>'zone'    : 용도구역(개발제한구역, 도시자연공원구역, 시가화조정구역, 수산자원보호구역, 입지규제최소구역,
 *                   2024년 신설 도시혁신구역·복합용도구역·도시·군계획시설입체복합구역)과 지구단위계획구역.</li>
 *   <li>'other'   : 그 밖의 모든 것(타 법령 지역·지구, 도시·군계획시설, 토지거래허가구역 등).</li>
 * </ul>
 *
 * <p>공백 판정은 파이썬 정규식 {@code \s}(= {@code str.isspace()})와 같다.
 */
public final class ZoningCodes {
    /** 코드 → 법정(공식) 명칭 (삽입 순서 유지). 출처는 클래스 설명 [S1]~[S3]. */
    public static final Map<String, String> LAND_USE_CODES;

    /** 국토의 계획 및 이용에 관한 법률 제36조, 같은 법 시행령 제30조의 세분 용도지역 21개 + 세분 전 관리지역. */
    public static final Set<String> ZONING_NAMES = orderedSet(
            "제1종전용주거지역",
            "제2종전용주거지역",
            "제1종일반주거지역",
            "제2종일반주거지역",
            "제3종일반주거지역",
            "준주거지역",
            "중심상업지역",
            "일반상업지역",
            "근린상업지역",
            "유통상업지역",
            "전용공업지역",
            "일반공업지역",
            "준공업지역",
            "보전녹지지역",
            "생산녹지지역",
            "자연녹지지역",
            "보전관리지역",
            "생산관리지역",
            "계획관리지역",
            "농림지역",
            "자연환경보전지역",
            "관리지역");

    /**
     * 용도구역(국토계획법 제38조~제40조의5)과 지구단위계획구역(제49조~).
     * 도시혁신구역·복합용도구역·도시·군계획시설입체복합구역은 2024.8.7 시행 개정법의 법정 명칭이며 [S1] 코드는 미확인.
     */
    public static final Set<String> ZONE_NAMES = orderedSet(
            "개발제한구역",
            "도시자연공원구역",
            "시가화조정구역",
            "수산자원보호구역",
            "입지규제최소구역",
            "도시혁신구역",
            "복합용도구역",
            "도시" + (char) 0xB7 + "군계획시설입체복합구역",
            "지구단위계획구역",
            "제1종지구단위계획구역",
            "제2종지구단위계획구역");

    private static final List<String> DISTRICT_CODE_PREFIXES =
            List.of("UQF", "UQG", "UQH", "UQI", "UQJ", "UQK", "UQL", "UQM", "UQN", "UQO", "UQP");
    private static final Set<String> ZONING_CODES = Set.of(
            "UQA111", "UQA112", "UQA121", "UQA122", "UQA123", "UQA130",
            "UQA210", "UQA220", "UQA230", "UQA240",
            "UQA310", "UQA320", "UQA330",
            "UQA410", "UQA420", "UQA430",
            "UQB001", "UQB100", "UQB200", "UQB300",
            "UQC001", "UQD001");
    private static final Set<String> ZONE_CODES =
            Set.of("UDV100", "UQQ200", "UQQ300", "UQQ310", "UQQ320", "UQQ500", "UQQ810", "UQQ901");

    /** 용도지구 명칭: [S1] UQF~UQP 코드 명칭 + 현행 법률상 상위 명칭. */
    public static final Set<String> DISTRICT_NAMES;

    // 괄호 부기: \s*[(\[（][^)\]）]*[)\]）]  (전각 괄호 포함)
    private static final Pattern PAREN_RE =
            Pattern.compile(PyStr.WS_CLASS + "*[(\\[\\uFF08][^)\\]\\uFF09]*[)\\]\\uFF09]");
    // 가운뎃점 변형 → '·'(U+00B7): ㆍ(U+318D) ‧(U+2027) ・(U+30FB) ･(U+FF65) ∙(U+2219) •(U+2022) ．(U+FF0E)
    private static final int[] DOT_VARIANTS = {0x318D, 0x2027, 0x30FB, 0xFF65, 0x2219, 0x2022, 0xFF0E};
    private static final int MIDDLE_DOT = 0xB7;

    private static final Map<String, String> CANONICAL_BY_KEY;
    private static final Set<String> ZONING_KEYS;
    private static final Set<String> ZONE_KEYS;
    private static final Set<String> DISTRICT_KEYS;

    private static final Map<String, String> CONFLICT_BY_CODE =
            Map.of("1", Conflict.INCLUDED, "2", Conflict.PARTIALLY_INCLUDED, "3", Conflict.ADJACENT);
    private static final Map<String, String> CONFLICT_BY_NAME = Map.of(
            "포함", Conflict.INCLUDED,
            "저촉", Conflict.PARTIALLY_INCLUDED,
            "접합", Conflict.ADJACENT, // getLandUseAttr 레퍼런스 표기
            "접함", Conflict.ADJACENT); // 국가중점데이터 컬럼정의서·실응답 표기

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // --- [S1] 타 법령 지정 일부 (실제 응답에 자주 나오는 것) ---
        m.put("UBA100", "과밀억제권역");
        m.put("UBA200", "성장관리권역");
        m.put("UBA300", "자연보전권역");
        m.put("UDV000", "개발제한미분류");
        m.put("UDV100", "개발제한구역");
        m.put("UDV999", "개발제한구역기타");
        m.put("UMZ100", "가축사육제한구역");
        m.put("UNE200", "대공방어협조구역");
        m.put("UNE400", "비행안전구역");
        m.put("UOA100", "교육환경보호구역");
        m.put("UOA110", "절대보호구역");
        m.put("UOA120", "상대보호구역");
        // --- [S1] 용도지역 ---
        m.put("UQA000", "도시지역미분류");
        m.put("UQA001", "도시지역");
        m.put("UQA002", "도시관리계획 입안중");
        m.put("UQA100", "주거지역");
        m.put("UQA110", "전용주거지역");
        m.put("UQA111", "제1종전용주거지역");
        m.put("UQA112", "제2종전용주거지역");
        m.put("UQA120", "일반주거지역");
        m.put("UQA121", "제1종일반주거지역");
        m.put("UQA122", "제2종일반주거지역");
        m.put("UQA123", "제3종일반주거지역");
        m.put("UQA130", "준주거지역");
        m.put("UQA200", "상업지역");
        m.put("UQA210", "중심상업지역");
        m.put("UQA220", "일반상업지역");
        m.put("UQA230", "근린상업지역");
        m.put("UQA240", "유통상업지역");
        m.put("UQA300", "공업지역");
        m.put("UQA310", "전용공업지역");
        m.put("UQA320", "일반공업지역");
        m.put("UQA330", "준공업지역");
        m.put("UQA400", "녹지지역");
        m.put("UQA410", "보전녹지지역");
        m.put("UQA420", "생산녹지지역");
        m.put("UQA430", "자연녹지지역");
        m.put("UQA500", "도시지역미지정");
        m.put("UQA999", "도시지역기타");
        m.put("UQB000", "관리지역미분류");
        m.put("UQB001", "관리지역");
        m.put("UQB100", "계획관리지역");
        m.put("UQB200", "생산관리지역");
        m.put("UQB300", "보전관리지역");
        m.put("UQB999", "관리지역기타");
        m.put("UQC000", "농림지역미분류");
        m.put("UQC001", "농림지역");
        m.put("UQC999", "농림지역기타");
        m.put("UQD000", "자연환경보전지역미분류");
        m.put("UQD001", "자연환경보전지역");
        m.put("UQD999", "자연환경보전지역기타");
        m.put("UQE000", "국토이용용도지역미분류");
        m.put("UQE999", "국토이용용도지역기타");
        // --- [S1] 용도지구 ---
        m.put("UQF000", "경관지구미분류");
        m.put("UQF100", "경관지구");
        m.put("UQF110", "자연경관지구");
        m.put("UQF120", "특화경관지구");
        m.put("UQF130", "시가지경관지구");
        m.put("UQF131", "시가지경관지구(중심)");
        m.put("UQF132", "시가지경관지구(일반)");
        m.put("UQF999", "경관지구기타");
        m.put("UQG000", "미관지구미분류");
        m.put("UQG100", "미관지구");
        m.put("UQG110", "중심지미관지구");
        m.put("UQG120", "역사문화미관지구");
        m.put("UQG130", "일반미관지구");
        m.put("UQG999", "미관지구기타");
        m.put("UQH000", "고도지구미분류");
        m.put("UQH100", "고도지구");
        m.put("UQH110", "최고고도지구");
        m.put("UQH120", "최저고도지구");
        m.put("UQH999", "고도지구기타");
        m.put("UQI000", "방화지구미분류");
        m.put("UQI100", "방화지구");
        m.put("UQI999", "방화지구기타");
        m.put("UQJ000", "방재지구미분류");
        m.put("UQJ100", "방재지구");
        m.put("UQJ200", "시가지방재지구");
        m.put("UQJ300", "자연방재지구");
        m.put("UQJ999", "방재지구기타");
        m.put("UQK000", "보존지구미분류");
        m.put("UQK100", "보존지구");
        m.put("UQK110", "역사문화환경보호지구");
        m.put("UQK120", "중요시설물보존지구");
        m.put("UQK121", "중요시설물보호지구(공항)");
        m.put("UQK122", "중요시설물보호지구(항만)");
        m.put("UQK123", "중요시설물보호지구(공용)");
        m.put("UQK130", "생태계보호지구");
        m.put("UQK999", "보존지구기타");
        m.put("UQL000", "시설보호지구미분류");
        m.put("UQL100", "시설보호지구");
        m.put("UQL110", "공항시설보호지구");
        m.put("UQL120", "학교시설보호지구");
        m.put("UQL130", "공용시설보호지구");
        m.put("UQL140", "항만시설보호지구");
        m.put("UQL999", "시설보호지구기타");
        m.put("UQM000", "용도지구취락미분류");
        m.put("UQM100", "용도지구취락지구");
        m.put("UQM110", "자연취락지구");
        m.put("UQM120", "집단취락지구");
        m.put("UQM999", "용도지구취락기타");
        m.put("UQN000", "개발진흥미분류");
        m.put("UQN100", "개발진흥지구");
        m.put("UQN110", "주거개발진흥지구");
        m.put("UQN120", "산업개발진흥지구");
        m.put("UQN130", "유통개발진흥지구");
        m.put("UQN140", "관광ㆍ휴양개발진흥지구");
        m.put("UQN150", "복합개발진흥지구");
        m.put("UQN160", "특정개발진흥지구");
        m.put("UQN170", "산업ㆍ유통개발진흥지구");
        m.put("UQN999", "개발진흥기타");
        m.put("UQO000", "특정용도미분류");
        m.put("UQO100", "특정용도제한지구");
        m.put("UQO999", "특정용도기타");
        m.put("UQP000", "국토이용용도지구미분류");
        m.put("UQP100", "국토이용기타용도지구");
        m.put("UQP120", "위락지구");
        m.put("UQP130", "리모델링지구");
        m.put("UQP500", "복합용도지구미분류");
        m.put("UQP501", "복합용도지구");
        m.put("UQP599", "복합용도지구기타");
        m.put("UQP999", "국토이용용도지구기타");
        // --- [S1] 용도구역·지구단위계획구역 등 ---
        m.put("UQQ000", "용도구역미분류");
        m.put("UQQ100", "도시계획구역");
        m.put("UQQ200", "시가화조정구역");
        m.put("UQQ300", "지구단위계획구역");
        m.put("UQQ310", "제1종지구단위계획구역");
        m.put("UQQ320", "제2종지구단위계획구역");
        m.put("UQQ321", "주거용지");
        m.put("UQQ322", "상업용지");
        m.put("UQQ323", "공업용지");
        m.put("UQQ324", "녹지용지");
        m.put("UQQ325", "공공시설용지");
        m.put("UQQ329", "기타용지");
        m.put("UQQ400", "광역계획구역");
        m.put("UQQ500", "수산자원보호구역");
        m.put("UQQ600", "토지거래계약에관한허가구역");
        m.put("UQQ700", "개발밀도관리구역");
        m.put("UQQ800", "기반시설부담구역");
        m.put("UQQ810", "도시자연공원구역");
        m.put("UQQ900", "개발행위허가제한지역");
        m.put("UQQ901", "입지규제최소구역");
        m.put("UQQ902", "성장관리방안 수립지역");
        m.put("UQQ999", "용도구역기타");
        // --- [S1] 도시·군계획시설 일부 ---
        m.put("UQS100", "도로");
        m.put("UQS520", "도시철도");
        m.put("UQT200", "공원");
        m.put("UQT220", "근린공원");
        m.put("UQV200", "공공청사");
        m.put("UQV300", "학교");
        // --- [S2] 브이월드 컬럼정의서·실응답 ---
        m.put("UQA01X", "도시지역");
        // --- [S3] 브이월드 getLandUseAttr 레퍼런스 샘플 ---
        m.put("UQP110", "아파트지구");
        LAND_USE_CODES = Collections.unmodifiableMap(m);

        Set<String> district = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : LAND_USE_CODES.entrySet()) {
            if (startsWithDistrictPrefix(e.getKey())) {
                district.add(e.getValue());
            }
        }
        district.addAll(List.of("보호지구", "중요시설물보호지구", "취락지구",
                "산업" + (char) MIDDLE_DOT + "유통개발진흥지구", "관광" + (char) MIDDLE_DOT + "휴양개발진흥지구"));
        DISTRICT_NAMES = Collections.unmodifiableSet(district);

        // 파이썬: for n in (*LAND_USE_CODES.values(), *ZONING_NAMES, *ZONE_NAMES, *DISTRICT_NAMES): setdefault(_key(n), n)
        // (사전 명칭이 먼저라서 '관광ㆍ휴양개발진흥지구'(U+318D) 표기가 이긴다. 집합끼리는 같은 키에 다른 표기가 없다.)
        Map<String, String> canonical = new LinkedHashMap<>();
        for (String n : LAND_USE_CODES.values()) {
            canonical.putIfAbsent(key(n), n);
        }
        for (Set<String> names : List.of(ZONING_NAMES, ZONE_NAMES, DISTRICT_NAMES)) {
            for (String n : names) {
                canonical.putIfAbsent(key(n), n);
            }
        }
        CANONICAL_BY_KEY = Collections.unmodifiableMap(canonical);
        ZONING_KEYS = keys(ZONING_NAMES);
        ZONE_KEYS = keys(ZONE_NAMES);
        DISTRICT_KEYS = keys(DISTRICT_NAMES);
    }

    private ZoningCodes() {
    }

    private static Set<String> orderedSet(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(names)));
    }

    private static Set<String> keys(Set<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            out.add(key(n));
        }
        return Collections.unmodifiableSet(out);
    }

    private static boolean startsWithDistrictPrefix(String code) {
        for (String p : DISTRICT_CODE_PREFIXES) {
            if (code.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 비교용 키: 공백 제거 + 가운뎃점 통일. */
    static String key(String name) {
        String s = PyStr.subWhitespace(name, "");
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            for (int d : DOT_VARIANTS) {
                if (cp == d) {
                    sb.append((char) MIDDLE_DOT);
                    return;
                }
            }
            sb.appendCodePoint(cp);
        });
        return sb.toString();
    }

    /** 괄호 부기 제거: '제2종일반주거지역(7층이하)' → '제2종일반주거지역'. */
    static String base(String name) {
        return Py.strip(PAREN_RE.matcher(name).replaceAll(""));
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** 사전에 있는 코드면 공식 명칭, 없으면 null. */
    public static String nameForCode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        return LAND_USE_CODES.get(upper(Py.strip(code)));
    }

    /**
     * 원천 명칭 정리: 앞뒤 공백 제거, 연속 공백 1칸. 공백·가운뎃점만 다른 공식 명칭이 있으면 그 표기로 바꾼다.
     *
     * <p>'제2종 일반주거지역' → '제2종일반주거지역'. 괄호 부기('(7층이하)' 등)는 정보이므로 남긴다.
     */
    public static String normalizeDesignationName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String s = Py.strip(PyStr.subWhitespace(name, " "));
        return CANONICAL_BY_KEY.getOrDefault(key(s), s);
    }

    /**
     * {@link LandUseDesignation#name()} 에 넣을 명칭.
     *
     * <p>코드가 사전에 있고 원천 명칭이 (공백·가운뎃점·괄호 부기 차이 외에) 같은 것을 가리키면 공식 명칭으로 통일한다.
     * 원천 명칭이 비어 있으면 코드의 공식 명칭을 쓴다. 다르면 원천 명칭을 그대로(정리만 해서) 둔다.
     */
    public static String canonicalDesignationName(String name, String code) {
        String n = normalizeDesignationName(name);
        String official = nameForCode(code);
        if (official == null) {
            return n;
        }
        if (n.isEmpty() || key(base(n)).equals(key(base(official))) || key(n).equals(key(official))) {
            return official;
        }
        return n;
    }

    public static String canonicalDesignationName(String name) {
        return canonicalDesignationName(name, null);
    }

    private static String kindFromCode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        String c = upper(Py.strip(code));
        if (ZONING_CODES.contains(c)) {
            return DesignationKind.ZONING;
        }
        if (ZONE_CODES.contains(c)) {
            return DesignationKind.ZONE;
        }
        if (startsWithDistrictPrefix(c)) { // [S1] UQF~UQP = 국토계획법 계열 용도지구 코드군
            return DesignationKind.DISTRICT;
        }
        if (LAND_USE_CODES.containsKey(c)) {
            return DesignationKind.OTHER;
        }
        return null;
    }

    private static String kindFromName(String name) {
        if (name == null || name.isEmpty()) {
            return DesignationKind.OTHER;
        }
        String k = key(base(Py.strip(PyStr.subWhitespace(name, " "))));
        if (ZONING_KEYS.contains(k)) {
            return DesignationKind.ZONING;
        }
        if (ZONE_KEYS.contains(k)) {
            return DesignationKind.ZONE;
        }
        if (DISTRICT_KEYS.contains(k)) {
            return DesignationKind.DISTRICT;
        }
        return DesignationKind.OTHER;
    }

    /**
     * 지정 종류 분류. 사전에 있는 코드가 있으면 코드 기준, 없으면 명칭 기준(정확 일치), 모르면 'other'.
     *
     * <pre>
     * classifyDesignation("제2종일반주거지역", "UQA122") → "zoning"
     * classifyDesignation("지구단위계획구역", null)      → "zone"
     * classifyDesignation("가축사육제한구역", "UMZ100")  → "other"
     * </pre>
     *
     * @return {@link DesignationKind} 값
     */
    public static String classifyDesignation(String name, String code) {
        String byCode = kindFromCode(code);
        if (byCode != null) {
            return byCode;
        }
        return kindFromName(name);
    }

    public static String classifyDesignation(String name) {
        return classifyDesignation(name, null);
    }

    /**
     * 저촉여부코드(1:포함, 2:저촉, 3:접함) 또는 저촉여부명 → {@link Conflict} 값. 모르면 'unknown'.
     *
     * <p>출처: getLandUseAttr 레퍼런스 cnflcAt 설명 "저촉여부코드(1:포함,2:저촉,3:접합)",
     * 국가중점데이터 컬럼정의서 참조코드 40 "저촉여부코드 1:포함, 2:저촉, 3:접함".
     *
     * @param code 원천 값 (문자열·숫자·원천 JSON 값, null = 없음). 파이썬 {@code str(code).strip()} 으로 비교한다.
     * @param name 원천 저촉여부명 (null = 없음). 공백을 모두 지우고 비교한다.
     */
    public static String conflictFromSource(Object code, Object name) {
        if (!PyStr.isNone(code)) {
            String c = Py.strip(PyStr.str(code));
            String hit = CONFLICT_BY_CODE.get(c);
            if (hit != null) {
                return hit;
            }
        }
        if (!PyStr.isNone(name)) {
            String n = PyStr.subWhitespace(PyStr.str(name), "");
            String hit = CONFLICT_BY_NAME.get(n);
            if (hit != null) {
                return hit;
            }
        }
        return Conflict.UNKNOWN;
    }
}
