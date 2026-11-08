package com.autoparkingplan.legal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 법규 원문 수집 결과의 공통 자료형 (계약) — 파이썬 {@code app/legal/parsed.py}.
 *
 * <p>수집기(국가법령정보 Open API XML, 또는 개발용 law.go.kr HTML)는 원문을 받아
 * SourceBundle 하나를 만든다. 적재기(app.legal.ingest)는 SourceBundle 을 받아
 * source_document / document_version / provision 행을 만든다.
 *
 * <p>조문 구조(조·항·호·목)는 AI 가 아니라 결정적 파서({@link Structure})가 만든다.
 * 수집기는 '조 단위 원문 전체(text)'만 정확히 넘기면 되고, 하위 분해는 선택이다.
 *
 * <p>JSON 직렬화 형식: data/legal_sources/*.json  (format = "app-legal-source@1").
 * 자료형은 {@link DocumentMeta}, {@link VersionMeta}, {@link ArticleText}, {@link SourceBundle} 레코드다.
 * 파이썬의 {@code Literal} 값(layer·source_type·검증 상태)은 문자열 그대로 두고 허용 값은 아래 상수에 둔다.
 */
public final class Parsed {
    private Parsed() {
    }

    public static final String BUNDLE_FORMAT = "app-legal-source@1";

    /** Layer: L1_NATIONAL | L2_LAND_USE | L3_LOCAL | L4_SPECIAL_ZONE. */
    public static final List<String> LAYERS = List.of("L1_NATIONAL", "L2_LAND_USE", "L3_LOCAL", "L4_SPECIAL_ZONE");
    /** SourceType. */
    public static final List<String> SOURCE_TYPES = List.of("law", "decree", "rule", "ordinance", "local_rule", "notice", "plan");
    /** VerifyState. */
    public static final List<String> VERIFY_STATES = List.of("not_checked", "confirmed", "needs_review");
    /** VersionMeta.raw_format. */
    public static final List<String> RAW_FORMATS = List.of("xml", "html", "pdf", "json");
    /** ArticleText.kind. */
    public static final List<String> ARTICLE_KINDS = List.of("article", "annex", "addendum");

    public static final String NOT_CHECKED = "not_checked";
    public static final String CONFIRMED = "confirmed";
    public static final String NEEDS_REVIEW = "needs_review";

    // ── 파이썬 DocumentMeta(**d) 처럼: 모르는 키·빠진 필수 키는 오류 ──

    static void checkKeys(JsonNode d, String type, Set<String> required, Set<String> optional) {
        if (d == null || !d.isObject()) {
            throw new IllegalArgumentException(type + " 는 JSON 객체여야 합니다");
        }
        for (String k : d.propertyNames()) {
            if (!required.contains(k) && !optional.contains(k)) {
                throw new IllegalArgumentException(type + ".__init__() got an unexpected keyword argument " + LegalPy.reprString(k));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String k : required) {
            if (!d.has(k)) {
                missing.add(k);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(type + ".__init__() missing required argument(s): " + String.join(", ", missing));
        }
    }

    /** 문자열 또는 null. */
    static String text(JsonNode d, String field) {
        JsonNode v = d.get(field);
        if (Json.isNull(v)) {
            return null;
        }
        if (!v.isString()) {
            throw new IllegalArgumentException(field + " 는 문자열이어야 합니다: " + v);
        }
        return v.stringValue();
    }

    static String textOr(JsonNode d, String field, String dflt) {
        return d.has(field) ? text(d, field) : dflt;
    }

    static ObjectNode putText(ObjectNode o, String field, String value) {
        if (value == null) {
            o.putNull(field);
        } else {
            o.put(field, value);
        }
        return o;
    }

    static Map<String, String> stringMap(JsonNode v, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        if (Json.isNull(v)) {
            return out;
        }
        if (!v.isObject()) {
            throw new IllegalArgumentException(field + " 는 JSON 객체여야 합니다");
        }
        for (Map.Entry<String, JsonNode> e : v.properties()) {
            if (!e.getValue().isString()) {
                throw new IllegalArgumentException(field + "." + e.getKey() + " 는 문자열이어야 합니다");
            }
            out.put(e.getKey(), e.getValue().stringValue());
        }
        return out;
    }

    /** 파이썬 {@code bool(x)} (JSON 값). */
    static boolean truthy(JsonNode v) {
        if (Json.isNull(v)) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0.0;
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        return !v.isEmpty();
    }
}
