package com.autoparkingplan.ai.verification;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.ai.pycompat.PyRe;
import com.autoparkingplan.ai.pycompat.PyRepr;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.ai.schema.SchemaError;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 출력 검증기 (법규 서버 6장 '검증 장치' 구현 예시) — {@code app/verification/ai_output.py}.
 *
 * <ol>
 *   <li>rule_extraction 출력 검증
 *     <ul>
 *       <li>JSON Schema 검사</li>
 *       <li>근거(evidence.quote)가 해당 조문 원문에 '그대로' 있는지 → char_start/char_end 계산</li>
 *       <li>값(num_*)이 그 근거 인용문 안에 숫자로 실제 등장하는지</li>
 *       <li>같은 rule_key 중복 여부</li>
 *     </ul>
 *   </li>
 *   <li>explanation 출력 검증 → {@link PostVerifier#verify} (날조·세부 왜곡·누락 전부)</li>
 * </ol>
 *
 * <p>사용 ({@link #main}):
 * <pre>
 *   extraction &lt;output.json&gt; &lt;provisions.json&gt;
 *   explanation &lt;output.json&gt; &lt;fact_ledger.json&gt; [provisions.json]
 * </pre>
 * provisions.json = {"&lt;provision_id&gt;": "&lt;조문 원문&gt;", ...}
 */
public final class AiOutput {
    private AiOutput() {
    }

    static final String DOC = """

            AI 출력 검증기 (법규 서버 6장 '검증 장치' 구현 예시)

            1) rule_extraction 출력 검증
               - JSON Schema 검사
               - 근거(evidence.quote)가 해당 조문 원문에 '그대로' 있는지 → char_start/char_end 계산
               - 값(num_*)이 그 근거 인용문 안에 숫자로 실제 등장하는지
               - 같은 rule_key 중복 여부
            2) explanation 출력 검증 → post_verifier.verify (날조·세부 왜곡·누락 전부)

            사용:
              python validate_ai_output.py extraction <output.json> <provisions.json>
              python validate_ai_output.py explanation <output.json> <fact_ledger.json> [provisions.json]

            provisions.json = {"<provision_id>": "<조문 원문>", ...}
            """;

    public static final Pattern NUM_RE = PyRe.compile("\\d+(?:\\.\\d+)?");
    public static final Pattern KOREAN_THOUSANDS_RE = PyRe.compile("(\\d+)천\\s?(\\d{3})?(?!\\d)");

    /** 파이썬 {@code _schema_errors}: 경로 순으로 정렬한 "schema: path: message" 목록 (레지스트리 + format 검사). */
    static List<String> schemaErrors(JsonNode instance, String schemaFile) {
        List<String> out = new ArrayList<>();
        for (SchemaError e : JsonSchemas.sortedErrors(instance, schemaFile)) {
            out.add("schema: " + e.describe());
        }
        return out;
    }

    /**
     * 인용문 안의 숫자 집합. '1천500'(=1500) 같은 법령 표기도 함께 넣는다 (파이썬 {@code _numbers}).
     * 값은 -0.0 이 없도록 넣으므로 {@link #containsNumber} 로 찾는다 (파이썬 set 은 0.0 == -0.0).
     */
    public static Set<Double> numbers(String text) {
        String t = text.replace(",", "");
        Set<Double> nums = new HashSet<>();
        Matcher m = NUM_RE.matcher(t);
        while (m.find()) {
            nums.add(PyObj.parseFloat(m.group()) + 0.0);
        }
        m = KOREAN_THOUSANDS_RE.matcher(t);
        while (m.find()) {
            BigInteger a = PyObj.parseInt(m.group(1));
            String b = m.group(2);
            BigInteger v = a.multiply(BigInteger.valueOf(1000)).add(b == null || b.isEmpty() ? BigInteger.ZERO : PyObj.parseInt(b));
            nums.add(PyObj.bigIntToFloat(v) + 0.0);
        }
        return nums;
    }

    /** 파이썬 {@code x in nums} (float 비교, 0.0 == -0.0). */
    public static boolean containsNumber(Set<Double> nums, double x) {
        return nums.contains(x + 0.0);
    }

    /** 인용문 위치 (코드포인트 단위 [start, end)). */
    public record Span(int start, int end) {
    }

    /** 인용문 위치. 정확히 1회 이상 등장해야 함 (첫 위치). 없으면 empty. */
    public static Optional<Span> locate(String quote, String provisionText) {
        int i = Py.find(provisionText, quote);
        return i < 0 ? Optional.empty() : Optional.of(new Span(i, i + Py.len(quote)));
    }

    /**
     * 추출 검증 결과.
     *
     * @param errors 오류 문구 (스키마 오류는 "schema: " 로 시작). 호출 측이 덧붙일 수 있게 변경 가능한 목록
     * @param spans  근거 위치 [{"where", "provision_id", "char_start", "char_end"}]
     */
    public record ExtractionCheck(List<String> errors, List<ObjectNode> spans) {
    }

    /** rule_extraction@1.0 출력 검증. provisions = provision_id → 조문 원문. */
    public static ExtractionCheck validateExtraction(JsonNode output, Map<String, String> provisions) {
        List<String> errors = schemaErrors(output, "rule_extraction.schema.json");
        if (!errors.isEmpty()) {
            return new ExtractionCheck(errors, new ArrayList<>());
        }
        List<ObjectNode> spans = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode rule : output.get("rules").values()) {
            String key = rule.get("rule_key").stringValue();
            if (seen.contains(key)) {
                errors.add("rules[" + i + "]: rule_key 중복 " + key);
            }
            seen.add(key);
            int j = 0;
            for (JsonNode val : rule.get("values").values()) {
                String where = "rules[" + i + "].values[" + j + "]";
                j++;
                String quote = checkEv(val.get("evidence"), where, provisions, errors, spans);
                if (quote == null) {
                    continue;
                }
                Set<Double> nums = numbers(quote);
                for (String k : List.of("num_min", "num_max", "num_exact")) {
                    JsonNode v = val.get(k);
                    if (!PyObj.isNone(v) && !containsNumber(nums, PyObj.toFloat(v))) {
                        errors.add(where + "." + k + "=" + PyRepr.str(v) + ": 근거 인용문에 이 숫자가 없음");
                    }
                }
                JsonNode ev = val.get("enum_value");
                if (PyObj.truthy(ev) && !quote.replace(" ", "").contains(ev.stringValue().replace(" ", ""))) {
                    errors.add(where + ".enum_value=" + PyRepr.repr(ev) + ": 근거 인용문에 이 용도명이 없음");
                }
            }
            JsonNode exc = rule.get("exceptions_mentioned");
            if (exc != null) {
                int k = 0;
                for (JsonNode ev : exc.values()) {
                    checkEv(ev, "rules[" + i + "].exceptions_mentioned[" + k + "]", provisions, errors, spans);
                    k++;
                }
            }
            i++;
        }
        i = 0;
        for (JsonNode d : output.get("delegations").values()) {
            checkEv(d.get("evidence"), "delegations[" + i + "]", provisions, errors, spans);
            i++;
        }
        return new ExtractionCheck(errors, spans);
    }

    private static String checkEv(JsonNode ev, String where, Map<String, String> provisions, List<String> errors, List<ObjectNode> spans) {
        String pid = ev.get("provision_id").stringValue();
        String text = provisions.get(pid);
        if (text == null) {
            errors.add(where + ": 입력에 없는 provision_id " + pid);
            return null;
        }
        String quote = ev.get("quote").stringValue();
        Optional<Span> span = locate(quote, text);
        if (span.isEmpty()) {
            errors.add(where + ": 인용문이 원문에 없음 → " + PyRepr.reprString(quote));
            return null;
        }
        ObjectNode s = Json.object();
        s.put("where", where);
        s.put("provision_id", pid);
        s.put("char_start", span.get().start());
        s.put("char_end", span.get().end());
        spans.add(s);
        return quote;
    }

    /** 쉬운 설명 검증은 사후 검증기(post_verifier)로 위임한다. */
    public static ObjectNode validateExplanation(JsonNode output, JsonNode ledger, Map<String, String> provisions) {
        return PostVerifier.verify(output, ledger, provisions);
    }

    /** 명령줄: {@code extraction <output.json> <provisions.json>} | {@code explanation <output.json> <fact_ledger.json> [provisions.json]}. */
    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.exit(run(args, out));
    }

    /** {@link #main} 본체 — 종료 코드를 돌려준다 (0 = 오류 없음, 1 = 오류 있음, 2 = 사용법). */
    public static int run(String[] args, PrintStream out) throws IOException {
        if ((args.length != 3 && args.length != 4) || !(args[0].equals("extraction") || args[0].equals("explanation"))) {
            out.println(DOC);
            return 2;
        }
        String mode = args[0];
        JsonNode output = Json.parse(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        JsonNode ctx = Json.parse(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8));
        JsonNode result;
        boolean hasErrors;
        if (mode.equals("extraction")) {
            ExtractionCheck check = validateExtraction(output, toStringMap(ctx));
            ObjectNode r = Json.object();
            r.put("status", check.errors().isEmpty() ? "ok" : "rejected");
            check.errors().forEach(r.putArray("errors")::add);
            check.spans().forEach(r.putArray("evidence_spans")::add);
            result = r;
            hasErrors = !check.errors().isEmpty();
        } else {
            Map<String, String> provs = args.length == 4
                    ? toStringMap(Json.parse(Files.readString(Path.of(args[3]), StandardCharsets.UTF_8))) : null;
            ObjectNode r = validateExplanation(output, ctx, provs);
            result = r;
            hasErrors = !r.get("errors").isEmpty();
        }
        out.println(PyJson.dumps(result, false, false, 2));
        return hasErrors ? 1 : 0;
    }

    private static Map<String, String> toStringMap(JsonNode obj) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            m.put(e.getKey(), PyObj.requireStr(e.getValue()));
        }
        return m;
    }
}
