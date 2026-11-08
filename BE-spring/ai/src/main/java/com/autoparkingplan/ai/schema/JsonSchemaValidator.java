package com.autoparkingplan.ai.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.ai.pycompat.PyRe;
import com.autoparkingplan.ai.pycompat.PyRepr;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/**
 * JSON Schema Draft 2020-12 검증기 — 파이썬 {@code jsonschema.Draft202012Validator} (4.26) 와 같은 결과를 낸다.
 *
 * <p>파이썬판과 같아야 하는 것: 오류의 <b>순서</b>(스키마 키워드 순서대로 내려가며 만든다), <b>문구</b>(파이썬 repr 포함),
 * <b>format 검사 범위</b>. 파이썬판은 {@code FORMAT_CHECKER} 를 쓰지만 rfc3339-validator·rfc3987 패키지가 설치돼 있지 않아
 * {@code date-time}·{@code uri} 는 검사하지 않고, {@code uuid}·{@code date}·{@code email}·{@code idn-email}·{@code ipv4} 는 검사한다.
 * 여기서도 똑같이 한다. ({@code ipv6}·{@code idn-hostname}·{@code regex} 는 파이썬판에서 검사하지만 이 프로젝트 스키마에 없어
 * 옮기지 않았다 — format 검사를 켠 채 그 형식을 만나면 {@link UnsupportedOperationException}.)
 *
 * <p>{@code $ref} 는 같은 문서 안의 JSON 포인터({@code #/$defs/x})와 {@code $id} 로 등록한 다른 스키마(파이썬 referencing Registry 와 같음)를
 * 따라간다. {@code $dynamicRef}·{@code unevaluatedItems}·{@code unevaluatedProperties}·{@code $anchor} 참조는 쓰지 않으므로
 * 만나면 {@link UnsupportedOperationException}.
 *
 * <p>스레드 안전 (상태 없음, 정규식 캐시는 동시 맵).
 */
public final class JsonSchemaValidator {
    private final JsonNode schema;
    private final String baseUri;
    private final Map<String, JsonNode> registry;
    private final boolean formatCheck;
    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    /**
     * @param schema      검증 기준 스키마
     * @param registry    {@code $id} → 스키마 (다른 문서 {@code $ref} 용, 없으면 빈 맵)
     * @param formatCheck 파이썬 {@code format_checker=Draft202012Validator.FORMAT_CHECKER} 와 같은 format 검사
     */
    public JsonSchemaValidator(JsonNode schema, Map<String, JsonNode> registry, boolean formatCheck) {
        this.schema = schema;
        this.formatCheck = formatCheck;
        Map<String, JsonNode> reg = new LinkedHashMap<>(registry);
        String id = schema.isObject() && schema.has("$id") && schema.get("$id").isString() ? schema.get("$id").stringValue() : "";
        this.baseUri = stripFragment(id);
        reg.putIfAbsent(this.baseUri, schema);
        this.registry = Map.copyOf(reg);
    }

    /** 파이썬 {@code Draft202012Validator(schema)} (레지스트리·format 검사 없음). */
    public static JsonSchemaValidator of(JsonNode schema) {
        return new JsonSchemaValidator(schema, Map.of(), false);
    }

    public JsonNode schema() {
        return schema;
    }

    /** 파이썬 {@code list(validator.iter_errors(instance))} — 같은 순서. */
    public List<SchemaError> iterErrors(JsonNode instance) {
        List<SchemaError> out = new ArrayList<>();
        descend(instance, schema, baseUri, new ArrayList<>(), out, false);
        return out;
    }

    /** 파이썬 {@code validator.is_valid(instance)}. */
    public boolean isValid(JsonNode instance) {
        return valid(instance, schema, baseUri);
    }

    /** 파이썬 {@code sorted(errors, key=lambda e: list(e.absolute_path))} (안정 정렬). */
    public static List<SchemaError> sortedByPath(List<SchemaError> errors) {
        List<SchemaError> copy = new ArrayList<>(errors);
        copy.sort(PATH_ORDER);
        return copy;
    }

    private static final Comparator<SchemaError> PATH_ORDER = (a, b) -> {
        List<Object> pa = a.path();
        List<Object> pb = b.path();
        for (int i = 0; i < Math.min(pa.size(), pb.size()); i++) {
            Object x = pa.get(i);
            Object y = pb.get(i);
            int c;
            if (x instanceof Integer xi && y instanceof Integer yi) {
                c = Integer.compare(xi, yi);
            } else if (x instanceof String xs && y instanceof String ys) {
                c = PyJson.compareCodePoints(xs, ys);
            } else {
                throw new IllegalStateException("TypeError: '<' not supported between str and int");
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.size(), pb.size());
    };

    // ── 내부 ────────────────────────────────────────────────

    private boolean valid(JsonNode instance, JsonNode subschema, String base) {
        List<SchemaError> errs = new ArrayList<>();
        descend(instance, subschema, base, new ArrayList<>(), errs, true);
        return errs.isEmpty();
    }

    private void error(List<SchemaError> out, List<Object> path, String keyword, String message) {
        out.add(new SchemaError(path, message, keyword));
    }

    private static List<Object> child(List<Object> path, Object step) {
        List<Object> p = new ArrayList<>(path.size() + 1);
        p.addAll(path);
        p.add(step);
        return p;
    }

    /**
     * 파이썬 {@code descend}/{@code iter_errors}: 스키마 키워드를 적힌 순서대로 적용한다.
     *
     * @param stopEarly true 면 첫 오류에서 멈춘다 ({@code is_valid} 용)
     */
    private void descend(JsonNode instance, JsonNode s, String base, List<Object> path, List<SchemaError> out, boolean stopEarly) {
        if (s.isBoolean()) {
            if (!s.booleanValue()) {
                error(out, path, null, "False schema does not allow " + PyRepr.repr(instance));
            }
            return;
        }
        if (!s.isObject()) {
            throw new IllegalArgumentException("스키마가 객체가 아닙니다: " + PyRepr.repr(s));
        }
        if (s.has("$id") && s.get("$id").isString() && s != schema) {
            base = resolveUri(base, s.get("$id").stringValue());
        }
        for (Map.Entry<String, JsonNode> kw : s.properties()) {
            if (stopEarly && !out.isEmpty()) {
                return;
            }
            String k = kw.getKey();
            JsonNode v = kw.getValue();
            switch (k) {
                case "$ref" -> {
                    Resolved r = lookup(base, v.stringValue());
                    descend(instance, r.schema, r.base, path, out, stopEarly);
                }
                case "additionalProperties" -> additionalProperties(instance, v, s, base, path, out, stopEarly);
                case "allOf" -> {
                    for (JsonNode sub : v.values()) {
                        descend(instance, sub, base, path, out, stopEarly);
                    }
                }
                case "anyOf" -> {
                    boolean any = false;
                    for (JsonNode sub : v.values()) {
                        if (valid(instance, sub, base)) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        error(out, path, k, PyRepr.repr(instance) + " is not valid under any of the given schemas");
                    }
                }
                case "const" -> {
                    if (!equal(instance, v)) {
                        error(out, path, k, PyRepr.repr(v) + " was expected");
                    }
                }
                case "contains" -> contains(instance, v, s, base, path, out);
                case "dependentRequired" -> {
                    if (instance.isObject()) {
                        for (Map.Entry<String, JsonNode> dep : v.properties()) {
                            if (!instance.has(dep.getKey())) {
                                continue;
                            }
                            for (JsonNode each : dep.getValue().values()) {
                                if (!instance.has(each.stringValue())) {
                                    error(out, path, k, PyRepr.repr(each) + " is a dependency of " + PyRepr.reprString(dep.getKey()));
                                }
                            }
                        }
                    }
                }
                case "dependentSchemas" -> {
                    if (instance.isObject()) {
                        for (Map.Entry<String, JsonNode> dep : v.properties()) {
                            if (instance.has(dep.getKey())) {
                                descend(instance, dep.getValue(), base, path, out, stopEarly);
                            }
                        }
                    }
                }
                case "enum" -> {
                    boolean found = false;
                    for (JsonNode each : v.values()) {
                        if (equal(each, instance)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        error(out, path, k, PyRepr.repr(instance) + " is not one of " + PyRepr.repr(v));
                    }
                }
                case "exclusiveMinimum" -> {
                    Integer c = isNumber(instance) ? PyObj.compareNumbers(instance, v) : null;
                    if (c != null && c <= 0) {
                        error(out, path, k, PyRepr.repr(instance) + " is less than or equal to the minimum of " + PyRepr.repr(v));
                    }
                }
                case "exclusiveMaximum" -> {
                    Integer c = isNumber(instance) ? PyObj.compareNumbers(instance, v) : null;
                    if (c != null && c >= 0) {
                        error(out, path, k, PyRepr.repr(instance) + " is greater than or equal to the maximum of " + PyRepr.repr(v));
                    }
                }
                case "minimum" -> {
                    Integer c = isNumber(instance) ? PyObj.compareNumbers(instance, v) : null;
                    if (c != null && c < 0) {
                        error(out, path, k, PyRepr.repr(instance) + " is less than the minimum of " + PyRepr.repr(v));
                    }
                }
                case "maximum" -> {
                    Integer c = isNumber(instance) ? PyObj.compareNumbers(instance, v) : null;
                    if (c != null && c > 0) {
                        error(out, path, k, PyRepr.repr(instance) + " is greater than the maximum of " + PyRepr.repr(v));
                    }
                }
                case "multipleOf" -> {
                    if (isNumber(instance) && !isMultiple(instance, v)) {
                        error(out, path, k, PyRepr.repr(instance) + " is not a multiple of " + PyRepr.str(v));
                    }
                }
                case "format" -> {
                    if (formatCheck && !checkFormat(instance, v.stringValue())) {
                        error(out, path, k, PyRepr.repr(instance) + " is not a " + PyRepr.repr(v));
                    }
                }
                case "if" -> {
                    if (valid(instance, v, base)) {
                        if (s.has("then")) {
                            descend(instance, s.get("then"), base, path, out, stopEarly);
                        }
                    } else if (s.has("else")) {
                        descend(instance, s.get("else"), base, path, out, stopEarly);
                    }
                }
                case "items" -> items(instance, v, s, base, path, out, stopEarly);
                case "prefixItems" -> {
                    if (instance.isArray()) {
                        int n = Math.min(instance.size(), v.size());
                        for (int i = 0; i < n; i++) {
                            descend(instance.get(i), v.get(i), base, child(path, i), out, stopEarly);
                        }
                    }
                }
                case "maxItems" -> {
                    if (instance.isArray() && instance.size() > v.doubleValue()) {
                        error(out, path, k, PyRepr.repr(instance) + (v.doubleValue() == 0 ? " is expected to be empty" : " is too long"));
                    }
                }
                case "minItems" -> {
                    if (instance.isArray() && instance.size() < v.doubleValue()) {
                        error(out, path, k, PyRepr.repr(instance) + (v.doubleValue() == 1 ? " should be non-empty" : " is too short"));
                    }
                }
                case "maxLength" -> {
                    if (instance.isString() && Py.len(instance.stringValue()) > v.doubleValue()) {
                        error(out, path, k, PyRepr.repr(instance) + (v.doubleValue() == 0 ? " is expected to be empty" : " is too long"));
                    }
                }
                case "minLength" -> {
                    if (instance.isString() && Py.len(instance.stringValue()) < v.doubleValue()) {
                        error(out, path, k, PyRepr.repr(instance) + (v.doubleValue() == 1 ? " should be non-empty" : " is too short"));
                    }
                }
                case "maxProperties" -> {
                    if (instance.isObject() && instance.size() > v.doubleValue()) {
                        error(out, path, k, PyRepr.repr(instance) + (v.doubleValue() == 0 ? " is expected to be empty" : " has too many properties"));
                    }
                }
                case "minProperties" -> {
                    if (instance.isObject() && instance.size() < v.doubleValue()) {
                        error(out, path, k, PyRepr.repr(instance)
                                + (v.doubleValue() == 1 ? " should be non-empty" : " does not have enough properties"));
                    }
                }
                case "not" -> {
                    if (valid(instance, v, base)) {
                        error(out, path, k, PyRepr.repr(instance) + " should not be valid under " + PyRepr.repr(v));
                    }
                }
                case "oneOf" -> oneOf(instance, v, base, path, out);
                case "pattern" -> {
                    if (instance.isString() && !pattern(v.stringValue()).matcher(instance.stringValue()).find()) {
                        error(out, path, k, PyRepr.repr(instance) + " does not match " + PyRepr.repr(v));
                    }
                }
                case "patternProperties" -> {
                    if (instance.isObject()) {
                        for (Map.Entry<String, JsonNode> pp : v.properties()) {
                            Pattern pat = pattern(pp.getKey());
                            for (Map.Entry<String, JsonNode> prop : instance.properties()) {
                                if (pat.matcher(prop.getKey()).find()) {
                                    descend(prop.getValue(), pp.getValue(), base, child(path, prop.getKey()), out, stopEarly);
                                }
                            }
                        }
                    }
                }
                case "properties" -> {
                    if (instance.isObject()) {
                        for (Map.Entry<String, JsonNode> prop : v.properties()) {
                            if (instance.has(prop.getKey())) {
                                descend(instance.get(prop.getKey()), prop.getValue(), base, child(path, prop.getKey()), out, stopEarly);
                            }
                        }
                    }
                }
                case "propertyNames" -> {
                    if (instance.isObject()) {
                        for (String name : instance.propertyNames()) {
                            descend(StringNode.valueOf(name), v, base, path, out, stopEarly);
                        }
                    }
                }
                case "required" -> {
                    if (instance.isObject()) {
                        for (JsonNode p : v.values()) {
                            if (!instance.has(p.stringValue())) {
                                error(out, path, k, PyRepr.repr(p) + " is a required property");
                            }
                        }
                    }
                }
                case "type" -> {
                    List<JsonNode> types = new ArrayList<>();
                    if (v.isArray()) {
                        v.values().forEach(types::add);
                    } else {
                        types.add(v);
                    }
                    boolean ok = false;
                    for (JsonNode t : types) {
                        if (isType(instance, t.stringValue())) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        String reprs = types.stream().map(PyRepr::repr).collect(Collectors.joining(", "));
                        error(out, path, k, PyRepr.repr(instance) + " is not of type " + reprs);
                    }
                }
                case "uniqueItems" -> {
                    if (PyObj.truthy(v) && instance.isArray() && !unique(instance)) {
                        error(out, path, k, PyRepr.repr(instance) + " has non-unique elements");
                    }
                }
                case "$dynamicRef", "unevaluatedItems", "unevaluatedProperties" ->
                        throw new UnsupportedOperationException("지원하지 않는 JSON Schema 키워드: " + k);
                default -> {
                    // 주석·메타 키워드($schema, $id, $defs, title, description, default, then, else, minContains ...)와 모르는 키워드는 무시
                }
            }
        }
    }

    private void additionalProperties(JsonNode instance, JsonNode ap, JsonNode s, String base, List<Object> path,
                                      List<SchemaError> out, boolean stopEarly) {
        if (!instance.isObject()) {
            return;
        }
        JsonNode props = s.get("properties");
        JsonNode pp = s.get("patternProperties");
        Pattern joined = null;
        if (pp != null && pp.isObject() && !pp.isEmpty()) {
            joined = pattern(String.join("|", pp.propertyNames()));
        }
        List<String> extras = new ArrayList<>();
        for (String prop : instance.propertyNames()) {
            if (props != null && props.isObject() && props.has(prop)) {
                continue;
            }
            if (joined != null && joined.matcher(prop).find()) {
                continue;
            }
            extras.add(prop);
        }
        if (ap.isObject()) {
            for (String extra : extras) {
                descend(instance.get(extra), ap, base, child(path, extra), out, stopEarly);
            }
        } else if (!PyObj.truthy(ap) && !extras.isEmpty()) {
            List<String> sorted = new ArrayList<>(extras);
            sorted.sort(PyJson::compareCodePoints);
            if (s.has("patternProperties")) {
                String verb = sorted.size() == 1 ? "does" : "do";
                List<String> pats = new ArrayList<>(s.get("patternProperties").propertyNames());
                pats.sort(PyJson::compareCodePoints);
                error(out, path, "additionalProperties", sorted.stream().map(PyRepr::reprString).collect(Collectors.joining(", "))
                        + " " + verb + " not match any of the regexes: "
                        + pats.stream().map(PyRepr::reprString).collect(Collectors.joining(", ")));
            } else {
                String verb = sorted.size() == 1 ? "was" : "were";
                error(out, path, "additionalProperties", "Additional properties are not allowed ("
                        + sorted.stream().map(PyRepr::reprString).collect(Collectors.joining(", ")) + " " + verb + " unexpected)");
            }
        }
    }

    private void items(JsonNode instance, JsonNode items, JsonNode s, String base, List<Object> path, List<SchemaError> out,
                       boolean stopEarly) {
        if (!instance.isArray()) {
            return;
        }
        int prefix = s.has("prefixItems") ? s.get("prefixItems").size() : 0;
        int total = instance.size();
        int extra = total - prefix;
        if (extra <= 0) {
            return;
        }
        if (items.isBoolean() && !items.booleanValue()) {
            String rest;
            if (extra != 1) {
                List<JsonNode> r = new ArrayList<>();
                for (int i = prefix; i < total; i++) {
                    r.add(instance.get(i));
                }
                rest = PyRepr.repr(r);
            } else {
                rest = PyRepr.repr(instance.get(prefix));
            }
            String item = prefix != 1 ? "items" : "item";
            error(out, path, "items", "Expected at most " + prefix + " " + item + " but found " + extra + " extra: " + rest);
        } else {
            for (int i = prefix; i < total; i++) {
                descend(instance.get(i), items, base, child(path, i), out, stopEarly);
            }
        }
    }

    private void contains(JsonNode instance, JsonNode contains, JsonNode s, String base, List<Object> path, List<SchemaError> out) {
        if (!instance.isArray()) {
            return;
        }
        int matches = 0;
        double minContains = s.has("minContains") ? s.get("minContains").doubleValue() : 1;
        double maxContains = s.has("maxContains") ? s.get("maxContains").doubleValue() : instance.size();
        for (JsonNode each : instance.values()) {
            if (valid(each, contains, base)) {
                matches++;
                if (matches > maxContains) {
                    error(out, path, "maxContains", "Too many items match the given schema (expected at most "
                            + (s.has("maxContains") ? PyRepr.repr(s.get("maxContains")) : String.valueOf(instance.size())) + ")");
                    return;
                }
            }
        }
        if (matches < minContains) {
            if (matches == 0) {
                error(out, path, "contains", PyRepr.repr(instance) + " does not contain items matching the given schema");
            } else {
                error(out, path, "minContains", "Too few items match the given schema (expected at least "
                        + PyRepr.repr(s.get("minContains")) + " but only " + matches + " matched)");
            }
        }
    }

    private void oneOf(JsonNode instance, JsonNode oneOf, String base, List<Object> path, List<SchemaError> out) {
        int firstValid = -1;
        for (int i = 0; i < oneOf.size(); i++) {
            if (valid(instance, oneOf.get(i), base)) {
                firstValid = i;
                break;
            }
        }
        if (firstValid < 0) {
            error(out, path, "oneOf", PyRepr.repr(instance) + " is not valid under any of the given schemas");
            return;
        }
        List<JsonNode> moreValid = new ArrayList<>();
        for (int i = firstValid + 1; i < oneOf.size(); i++) {
            if (valid(instance, oneOf.get(i), base)) {
                moreValid.add(oneOf.get(i));
            }
        }
        if (!moreValid.isEmpty()) {
            moreValid.add(oneOf.get(firstValid));
            String reprs = moreValid.stream().map(PyRepr::repr).collect(Collectors.joining(", "));
            error(out, path, "oneOf", PyRepr.repr(instance) + " is valid under each of " + reprs);
        }
    }

    private Pattern pattern(String p) {
        return patterns.computeIfAbsent(p, PyRe::compile);
    }

    // ── 타입·비교 (파이썬 jsonschema 규칙) ─────────────────────

    private static boolean isNumber(JsonNode v) {
        return v.isNumber();
    }

    static boolean isType(JsonNode v, String type) {
        return switch (type) {
            case "array" -> v.isArray();
            case "boolean" -> v.isBoolean();
            case "null" -> v.isNull();
            case "number" -> v.isNumber();
            case "object" -> v.isObject();
            case "string" -> v.isString();
            // draft6 이후: 정수, 또는 정수 값인 실수 (1.0)
            case "integer" -> v.isIntegralNumber()
                    || (v.isNumber() && !Double.isInfinite(v.doubleValue()) && !Double.isNaN(v.doubleValue())
                    && v.doubleValue() == Math.floor(v.doubleValue()));
            default -> throw new IllegalArgumentException("UnknownType: " + PyRepr.reprString(type));
        };
    }

    /** jsonschema {@code _utils.equal}: bool 은 bool 끼리만, 1 == 1.0, 문자열·배열·객체는 구조 비교. */
    static boolean equal(JsonNode a, JsonNode b) {
        if (a.isString() || b.isString()) {
            return a.isString() && b.isString() && a.stringValue().equals(b.stringValue());
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equal(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                if (!b.has(e.getKey()) || !equal(e.getValue(), b.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isBoolean() || b.isBoolean()) {
            return a.isBoolean() && b.isBoolean() && a.booleanValue() == b.booleanValue();
        }
        if (a.isNull() || b.isNull()) {
            return a.isNull() && b.isNull();
        }
        if (a.isNumber() && b.isNumber()) {
            Integer c = PyObj.compareNumbers(a, b);
            return c != null && c == 0;
        }
        return false;
    }

    private static boolean unique(JsonNode arr) {
        List<JsonNode> seen = new ArrayList<>();
        for (JsonNode e : arr.values()) {
            for (JsonNode s : seen) {
                if (equal(s, e)) {
                    return false;
                }
            }
            seen.add(e);
        }
        return true;
    }

    private static boolean isMultiple(JsonNode instance, JsonNode dB) {
        if (!dB.isIntegralNumber()) {
            double q = instance.doubleValue() / dB.doubleValue();
            if (Double.isInfinite(q)) {
                BigDecimal r = exact(instance).remainder(exact(dB));
                return r.signum() == 0;
            }
            if (Double.isNaN(q)) {
                throw new IllegalArgumentException("ValueError: cannot convert float NaN to integer");
            }
            return q == Math.floor(q); // 파이썬 int(quotient) == quotient
        }
        if (instance.isIntegralNumber()) {
            BigInteger d = dB.bigIntegerValue();
            if (d.signum() == 0) {
                throw new ArithmeticException("ZeroDivisionError: integer modulo by zero");
            }
            return instance.bigIntegerValue().mod(d.abs()).signum() == 0;
        }
        double d = dB.doubleValue();
        if (d == 0) {
            throw new ArithmeticException("ZeroDivisionError: float modulo");
        }
        return instance.doubleValue() % d == 0.0;
    }

    private static BigDecimal exact(JsonNode n) {
        return n.isIntegralNumber() ? new BigDecimal(n.bigIntegerValue()) : new BigDecimal(n.doubleValue());
    }

    // ── format (파이썬 FORMAT_CHECKER 중 설치 패키지로 켜지는 것) ────────

    private static final Pattern ASCII_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Set<String> UNPORTED_FORMATS = Set.of("ipv6", "idn-hostname", "regex");
    private static final Set<String> CHECKED_FORMATS = Set.of("uuid", "date", "email", "idn-email", "ipv4");

    static boolean checkFormat(JsonNode instance, String format) {
        if (!CHECKED_FORMATS.contains(format) && !UNPORTED_FORMATS.contains(format)) {
            return true; // 파이썬판에도 검사기가 없는 형식 (date-time, uri, ...)
        }
        if (!instance.isString()) {
            return true;
        }
        String s = instance.stringValue();
        return switch (format) {
            case "uuid" -> isUuid(s);
            case "date" -> isDate(s);
            case "email", "idn-email" -> s.contains("@");
            case "ipv4" -> isIpv4(s);
            default -> throw new UnsupportedOperationException("format " + format + " 검사는 옮기지 않았습니다");
        };
    }

    /** 파이썬 {@code UUID(s)} 가 성공하고 8·13·18·23 번째가 '-' 인가. */
    static boolean isUuid(String instance) {
        String hex = instance.replace("urn:", "").replace("uuid:", "");
        hex = Py.strip(hex, "{}").replace("-", "");
        if (Py.len(hex) != 32) {
            return false;
        }
        BigInteger value;
        try {
            value = parseIntBase16(hex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (value.signum() < 0 || value.bitLength() > 128) {
            return false;
        }
        int[] cps = instance.codePoints().toArray();
        for (int pos : new int[] {8, 13, 18, 23}) {
            if (cps[pos] != '-') {
                return false;
            }
        }
        return true;
    }

    /** 파이썬 {@code int(s, 16)}: 앞뒤 공백·부호·0x 접두어·숫자 사이 밑줄·유니코드 숫자 허용. */
    private static BigInteger parseIntBase16(String s) {
        String t = PyObj.numStrip(PyObj.toAsciiDigits(s));
        boolean neg = false;
        if (t.startsWith("+") || t.startsWith("-")) {
            neg = t.startsWith("-");
            t = t.substring(1);
        }
        if (t.length() >= 2 && t.charAt(0) == '0' && (t.charAt(1) == 'x' || t.charAt(1) == 'X')) {
            t = t.substring(2);
            if (t.startsWith("_")) {
                t = t.substring(1);
            }
        }
        if (!t.matches("[0-9a-fA-F](?:_?[0-9a-fA-F])*")) {
            throw new IllegalArgumentException("invalid literal for int() with base 16");
        }
        BigInteger v = new BigInteger(t.replace("_", ""), 16);
        return neg ? v.negate() : v;
    }

    /** {@code ^\d{4}-\d{2}-\d{2}$}(ASCII) 와 {@code date.fromisoformat}. */
    static boolean isDate(String s) {
        if (!ASCII_DATE.matcher(s).matches()) {
            return false;
        }
        int y = Integer.parseInt(s.substring(0, 4));
        int m = Integer.parseInt(s.substring(5, 7));
        int d = Integer.parseInt(s.substring(8, 10));
        if (y < 1) {
            return false;
        }
        try {
            LocalDate.of(y, m, d);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /** 파이썬 {@code ipaddress.IPv4Address(s)}. */
    static boolean isIpv4(String s) {
        if (s.isEmpty() || s.contains("/")) {
            return false;
        }
        String[] octets = s.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String o : octets) {
            if (o.isEmpty() || o.length() > 3 || !o.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return false;
            }
            if (!o.equals("0") && o.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(o) > 255) {
                return false;
            }
        }
        return true;
    }

    // ── $ref ────────────────────────────────────────────────

    private record Resolved(JsonNode schema, String base) {
    }

    private Resolved lookup(String base, String ref) {
        String target = resolveUri(base, ref);
        int hash = target.indexOf('#');
        String uri = hash < 0 ? target : target.substring(0, hash);
        String fragment = hash < 0 ? "" : URLDecoder.decode(target.substring(hash + 1).replace("+", "%2B"), StandardCharsets.UTF_8);
        JsonNode doc = registry.get(uri);
        if (doc == null) {
            throw new IllegalStateException("Unresolvable JSON pointer / reference: " + ref);
        }
        if (fragment.isEmpty()) {
            return new Resolved(doc, uri);
        }
        if (!fragment.startsWith("/")) {
            throw new UnsupportedOperationException("$anchor 참조는 지원하지 않습니다: " + ref);
        }
        JsonNode node = doc;
        String nodeBase = uri;
        for (String raw : fragment.substring(1).split("/", -1)) {
            String seg = raw.replace("~1", "/").replace("~0", "~");
            if (node.isArray()) {
                node = node.get(Integer.parseInt(seg));
            } else {
                node = node.get(seg);
            }
            if (node == null) {
                throw new IllegalStateException("Unresolvable JSON pointer: " + PyRepr.reprString(fragment));
            }
            if (node.isObject() && node.has("$id") && node.get("$id").isString()) {
                nodeBase = resolveUri(nodeBase, node.get("$id").stringValue());
            }
        }
        return new Resolved(node, stripFragment(nodeBase));
    }

    private static String resolveUri(String base, String ref) {
        if (ref.startsWith("#")) {
            return stripFragment(base) + ref;
        }
        if (base == null || base.isEmpty()) {
            return ref;
        }
        return URI.create(base).resolve(ref).toString();
    }

    private static String stripFragment(String uri) {
        int i = uri.indexOf('#');
        return i < 0 ? uri : uri.substring(0, i);
    }

    /** 레지스트리에 등록된 스키마 id 목록 (진단용). */
    public Set<String> registeredIds() {
        return new HashSet<>(registry.keySet());
    }
}
