package com.autoparkingplan.ai.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.ai.pycompat.PyRepr;
import com.autoparkingplan.ai.verification.PostVerifier;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 결정적 가짜 LLM — 오프라인 개발·테스트용 (외부 호출 없음). {@code app/llm/fake.py}.
 *
 * <ul>
 *   <li>explanation_writer: 원장 사실만 인용해 문장을 만든다 (render_fallback 과 다른 문체, source='ai')</li>
 *   <li>semantic_judge : 모든 문장 ENTAILED, 모든 사실 COVERED</li>
 *   <li>rule_extraction : scripted 응답이 없으면 LLMError (실제 추출은 사람이 준비한 기록 또는 실제 LLM 사용)</li>
 * </ul>
 * {@link #scripted()} 에 task 별 응답({@link Scripted})을 넣어 실패 시나리오를 주입할 수 있다 (앞에서부터 하나씩 꺼내 쓴다).
 * 같은 입력이면 파이썬판과 바이트 단위로 같은 출력을 낸다.
 */
public final class FakeLLM implements LLMClient {
    public static final Map<String, String> ROLE_BY_KIND = Map.of("EXCEPTION", "exception", "CONDITION", "condition", "SUBJECT", "summary");

    /** scripted 응답 하나: 고정 JSON, 입력(payload)으로 만드는 함수, 또는 던질 예외. */
    public sealed interface Scripted permits Reply, Compute, Fail {
        static Scripted reply(JsonNode data) {
            return new Reply(data);
        }

        static Scripted compute(Function<JsonNode, JsonNode> fn) {
            return new Compute(fn);
        }

        static Scripted fail(RuntimeException error) {
            return new Fail(error);
        }
    }

    public record Reply(JsonNode data) implements Scripted {
    }

    public record Compute(Function<JsonNode, JsonNode> fn) implements Scripted {
    }

    public record Fail(RuntimeException error) implements Scripted {
    }

    /** 호출 기록 (task, 파싱한 payload). */
    public record Call(Task task, JsonNode payload) {
    }

    private final String model;
    private final Map<Task, List<Scripted>> scripted = new EnumMap<>(Task.class);
    private final List<Call> calls = new ArrayList<>();

    public FakeLLM() {
        this("fake-deterministic-1", null);
    }

    public FakeLLM(String model) {
        this(model, null);
    }

    public FakeLLM(String model, Map<Task, List<Scripted>> scripted) {
        this.model = model;
        if (scripted != null) {
            scripted.forEach((k, v) -> this.scripted.put(k, new ArrayList<>(v)));
        }
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String model() {
        return model;
    }

    /** task → 남은 scripted 응답 (변경 가능; 바꿀 때는 이 객체로 synchronized). */
    public Map<Task, List<Scripted>> scripted() {
        return scripted;
    }

    /** task 의 scripted 응답을 바꾼다 (파이썬 {@code llm.scripted[task] = [...]}). */
    public synchronized void script(Task task, Scripted... steps) {
        scripted.put(task, new ArrayList<>(Arrays.asList(steps)));
    }

    /** 지금까지의 호출 기록 (복사본). */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public LLMResult generateJson(Task task, String system, String user, JsonNode schema, int maxTokens) {
        JsonNode payload = Json.parse(user);
        Scripted next = null;
        synchronized (this) {
            calls.add(new Call(task, payload));
            List<Scripted> queue = scripted.get(task);
            if (queue != null && !queue.isEmpty()) {
                next = queue.remove(0);
            }
        }
        if (next != null) {
            return switch (next) {
                case Fail f -> throw f.error();
                case Reply r -> new LLMResult(r.data(), model);
                case Compute c -> new LLMResult(c.fn().apply(payload), model);
            };
        }
        if (task == Task.EXPLANATION_WRITER) {
            return new LLMResult(write(payload), model);
        }
        if (task == Task.SEMANTIC_JUDGE) {
            return new LLMResult(judge(payload), model);
        }
        throw new LLMError("FakeLLM: " + task + " 에 대한 scripted 응답이 없습니다");
    }

    /** 파이썬 {@code _write(payload)}. */
    static ObjectNode write(JsonNode payload) {
        ObjectNode ledger = Json.object();
        ledger.set("facts", PyObj.item(payload, "facts"));
        JsonNode category = PyObj.item(payload, "category");
        JsonNode audience = PyObj.item(payload, "audience");
        // 템플릿의 category·audience 는 아래에서 쓰지 않는다 (문장 구성만 빌린다)
        ObjectNode base = PostVerifier.renderFallback(ledger, PyRepr.str(category), PyRepr.str(audience));
        boolean owner = PyObj.eq(audience, "owner");
        Map<Object, JsonNode> facts = new LinkedHashMap<>();
        for (JsonNode f : payload.get("facts").values()) {
            facts.put(PyObj.hashKey(PyObj.item(f, "fact_id")), f);
        }
        ArrayNode sentences = Json.array();
        int i = 0;
        for (JsonNode s : base.get("sentences").values()) {
            JsonNode f = facts.get(PyObj.hashKey(s.get("fact_ids").get(0)));
            if (f == null) {
                f = Json.object();
            }
            String text;
            String role;
            if (s.get("role").stringValue().equals("caution")) {
                text = owner ? "개별 조건에 따라 달라질 수 있어 확인이 필요해요." : "개별 조건에 따라 달라질 수 있어 확인 필요.";
                role = "caution";
            } else {
                JsonNode q = f.get("quote");
                String quote = q == null ? "" : PyRepr.str(q);
                text = owner ? "원문 기준은 “" + quote + "”예요." : "근거: “" + quote + "”";
                if (i == 0) {
                    role = "headline";
                } else {
                    JsonNode kind = f.get("kind");
                    if (kind != null && (kind.isArray() || kind.isObject())) {
                        throw new IllegalArgumentException("TypeError: unhashable type");
                    }
                    String mapped = kind != null && kind.isString() ? ROLE_BY_KIND.get(kind.stringValue()) : null;
                    role = mapped == null ? "meaning" : mapped;
                }
            }
            ObjectNode out = Json.object();
            out.put("sid", "s" + (i + 1));
            out.put("role", role);
            out.put("text", Py.slice(text, null, 200));
            out.set("fact_ids", s.get("fact_ids"));
            sentences.add(out);
            i++;
        }
        ObjectNode r = Json.object();
        r.put("schema_version", "explanation@2.0");
        r.set("category", category);
        r.set("audience", audience);
        r.put("source", "ai");
        r.set("sentences", sentences);
        r.putArray("glossary");
        return r;
    }

    /** 파이썬 {@code _judge(payload)}. */
    static ObjectNode judge(JsonNode payload) {
        ObjectNode r = Json.object();
        r.put("schema_version", "judge_verdict@1.0");
        ArrayNode sents = r.putArray("sentences");
        for (JsonNode s : PyObj.item(payload, "sentences").values()) {
            ObjectNode o = sents.addObject();
            o.set("sid", PyObj.item(s, "sid"));
            o.put("verdict", "ENTAILED");
            o.put("note", "가짜 판정기: 원문 인용 문장");
        }
        ArrayNode cov = r.putArray("coverage");
        for (JsonNode f : PyObj.item(payload, "facts").values()) {
            if (!PyObj.eq(f.get("kind"), "STATUS")) {
                ObjectNode o = cov.addObject();
                o.set("fact_id", PyObj.item(f, "fact_id"));
                o.put("verdict", "COVERED");
            }
        }
        return r;
    }
}
