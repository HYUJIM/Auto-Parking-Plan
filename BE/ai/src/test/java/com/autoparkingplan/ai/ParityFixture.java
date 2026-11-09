package com.autoparkingplan.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

import com.autoparkingplan.ai.llm.FakeLLM;
import com.autoparkingplan.ai.llm.Task;
import com.autoparkingplan.ai.verification.PostVerifier;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 파이썬판으로 만든 기대값 ({@code src/test/resources/parity/parity.json.gz}, 생성기 {@code generate_parity.py}).
 */
public final class ParityFixture {
    private static volatile JsonNode data;

    private ParityFixture() {
    }

    public static JsonNode get() {
        JsonNode d = data;
        if (d == null) {
            synchronized (ParityFixture.class) {
                if (data == null) {
                    try (InputStream in = ParityFixture.class.getResourceAsStream("/parity/parity.json.gz")) {
                        if (in == null) {
                            throw new IllegalStateException("parity.json.gz 없음 — generate_parity.py 를 실행하세요");
                        }
                        data = Json.parse(new GZIPInputStream(in));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                d = data;
            }
        }
        return d;
    }

    public static List<JsonNode> list(String key) {
        List<JsonNode> out = new ArrayList<>();
        get().get(key).values().forEach(out::add);
        return out;
    }

    public static Map<String, String> stringMap(JsonNode obj) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            m.put(e.getKey(), e.getValue().stringValue());
        }
        return m;
    }

    /** 생성기의 {@code judge_fn(kind)} 와 같은 판정기. */
    public static PostVerifier.Judge judge(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "fake" -> (facts, sents) -> {
                ObjectNode payload = Json.object();
                ArrayNode fs = payload.putArray("facts");
                facts.forEach(fs::add);
                ArrayNode ss = payload.putArray("sentences");
                for (JsonNode s : sents) {
                    ObjectNode o = ss.addObject();
                    for (String k : List.of("sid", "text", "fact_ids")) {
                        o.set(k, s.get(k));
                    }
                }
                return new FakeLLM().generateJson(Task.SEMANTIC_JUDGE, "", PyJson.dumps(payload), Json.object()).data();
            };
            case "not_in_source_first" -> (facts, sents) -> {
                ObjectNode r = header();
                ArrayNode a = r.putArray("sentences");
                for (int i = 0; i < sents.size(); i++) {
                    ObjectNode o = a.addObject();
                    o.set("sid", sents.get(i).get("sid"));
                    o.put("verdict", i == 0 ? "NOT_IN_SOURCE" : "ENTAILED");
                }
                r.putArray("coverage");
                return r;
            };
            case "contradicted_last_with_note" -> (facts, sents) -> {
                ObjectNode r = header();
                ArrayNode a = r.putArray("sentences");
                for (int i = 0; i < sents.size(); i++) {
                    ObjectNode o = a.addObject();
                    o.set("sid", sents.get(i).get("sid"));
                    o.put("verdict", i == sents.size() - 1 ? "CONTRADICTED" : "ENTAILED");
                    o.put("note", "원문은 이하인데 미만으로 씀");
                }
                ArrayNode c = r.putArray("coverage");
                if (!facts.isEmpty()) {
                    ObjectNode o = c.addObject();
                    o.set("fact_id", facts.get(0).get("fact_id"));
                    o.put("verdict", "PARTIAL");
                }
                if (facts.size() > 1) {
                    ObjectNode o = c.addObject();
                    o.set("fact_id", facts.get(1).get("fact_id"));
                    o.put("verdict", "MISSING");
                    o.put("note", "빠짐");
                }
                return r;
            };
            case "raise" -> (facts, sents) -> {
                throw new TimeoutException("judge timeout");
            };
            case "partial" -> (facts, sents) -> {
                ObjectNode r = header();
                ObjectNode o = r.putArray("sentences").addObject();
                o.set("sid", sents.get(0).get("sid"));
                o.put("verdict", "ENTAILED");
                r.putArray("coverage");
                return r;
            };
            case "invalid" -> (facts, sents) -> {
                ObjectNode r = header();
                ArrayNode a = r.putArray("sentences");
                for (JsonNode s : sents) {
                    ObjectNode o = a.addObject();
                    o.set("sid", s.get("sid"));
                    o.put("verdict", "MAYBE");
                }
                r.putArray("coverage");
                return r;
            };
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static ObjectNode header() {
        ObjectNode r = Json.object();
        r.put("schema_version", "judge_verdict@1.0");
        return r;
    }
}
