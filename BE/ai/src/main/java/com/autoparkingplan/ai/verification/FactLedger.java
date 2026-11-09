package com.autoparkingplan.ai.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.autoparkingplan.ai.pycompat.PyObj;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사실 원장(Fact Ledger) — 사후 검증의 '정답지' ({@code app/verification/fact_ledger.py}).
 *
 * <p>원장은 규칙 하나가 근거로 삼는 원문 조문에서 '설명에 반드시 들어가야 할 사실'을
 * 원자 단위로 적어 둔 목록이다. AI 답변은 이 원장과만 대조된다.
 * <ol>
 *   <li>{@link #extractCandidates}: 원문에서 기계적으로 후보를 뽑는다 (숫자·기간·날짜·비교어·예외·의무/금지 문장).</li>
 *   <li>사람이 검수 화면에서 후보를 원장 사실(fact)로 승격하거나, 사유를 적어 제외(dismissal)한다.</li>
 *   <li>{@link #checkCompleteness}: 원문의 모든 후보가 원장에 들어갔거나 사유와 함께 제외됐는지 확인한다.
 *       → 원장 자체가 원문을 빠뜨리는 것을 막는다 (원문 누락 방지의 1단계).</li>
 * </ol>
 * 사실(fact) 형식은 schemas/fact_ledger.schema.json 참고.
 */
public final class FactLedger {
    public static final Set<String> NUMERIC_KINDS = Set.of("PERIOD", "QUANTITY", "DATE");

    private FactLedger() {
    }

    /** 파이썬 {@code _cid}: sha1("pid|kind|start|end") 앞 12자리. */
    static String cid(String provisionId, String kind, int start, int end) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                    .digest((provisionId + "|" + kind + "|" + start + "|" + end).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 원문에서 사실 후보를 결정적으로 추출 (AI 미사용). 위치는 코드포인트 단위. */
    public static List<ObjectNode> extractCandidates(String provisionId, String text) {
        List<ObjectNode> out = new ArrayList<>();
        for (KoQuantity.Q q : KoQuantity.parse(text)) {
            if (NUMERIC_KINDS.contains(q.kind()) || q.kind().equals("REFERENCE")) {
                ObjectNode c = Json.object();
                c.put("candidate_id", cid(provisionId, q.kind(), q.start(), q.end()));
                c.put("provision_id", provisionId);
                c.put("kind", q.kind());
                c.put("quote", q.raw());
                c.put("char_start", q.start());
                c.put("char_end", q.end());
                ObjectNode canon = c.putObject("canonical");
                canon.put("dim", q.dim());
                canon.set("value", q.valueNode());
                canon.put("unit", q.unit());
                canon.put("comparator", q.comparator());
                out.add(c);
            }
        }
        for (KoQuantity.Sentence st : KoQuantity.splitSentences(text)) {
            int start = st.start();
            String sent = st.text();
            int sentEnd = start + Py.len(sent);
            String mod = KoQuantity.modality(sent);
            if (KoQuantity.EXCEPTION_RE.matcher(sent).find()) {
                out.add(sentenceCandidate(provisionId, "EXCEPTION", sent, start, sentEnd, mod));
            } else if (mod != null) {
                out.add(sentenceCandidate(provisionId, "MODALITY", sent, start, sentEnd, mod));
            }
            for (String z : KoQuantity.zoneNames(sent)) {
                int i = text.contains(z) ? Py.find(text, z, start) : start;
                ObjectNode c = Json.object();
                c.put("candidate_id", cid(provisionId, "SUBJECT:" + z, start, sentEnd));
                c.put("provision_id", provisionId);
                c.put("kind", "SUBJECT");
                c.put("quote", z);
                c.put("char_start", i);
                c.put("char_end", i + Py.len(z));
                c.put("name", z);
                out.add(c);
            }
        }
        // 같은 SUBJECT 이름은 한 번만
        Set<String> seen = new HashSet<>();
        List<ObjectNode> uniq = new ArrayList<>();
        for (ObjectNode c : out) {
            String kind = c.get("kind").stringValue();
            String k = kind.equals("SUBJECT") ? "S\0" + kind + "\0" + c.get("name").stringValue() : "C\0" + c.get("candidate_id").stringValue();
            if (seen.add(k)) {
                uniq.add(c);
            }
        }
        return uniq;
    }

    private static ObjectNode sentenceCandidate(String pid, String kind, String sent, int start, int end, String mod) {
        ObjectNode c = Json.object();
        c.put("candidate_id", cid(pid, kind, start, end));
        c.put("provision_id", pid);
        c.put("kind", kind);
        c.put("quote", sent);
        c.put("char_start", start);
        c.put("char_end", end);
        c.put("modality", mod);
        return c;
    }

    /**
     * 원문 후보 중 원장에 반영도, 제외 사유도 없는 것을 돌려준다. 빈 목록이어야 원장을 승인할 수 있다.
     *
     * @param provisions provision_id → 조문 원문 (파이썬 dict 순서대로 검사하므로 순서 있는 Map 을 넘긴다)
     */
    public static List<ObjectNode> checkCompleteness(Map<String, String> provisions, JsonNode ledger) {
        Set<String> covered = new HashSet<>();
        for (JsonNode f : PyObj.item(ledger, "facts").values()) {
            JsonNode ids = PyObj.get(f, "candidate_ids");
            if (ids == null) {
                continue;
            }
            for (JsonNode cid : iterable(ids)) {
                addHashable(covered, cid);
            }
        }
        Set<String> dismissed = new HashSet<>();
        JsonNode dis = PyObj.get(ledger, "dismissed");
        if (dis != null) {
            for (JsonNode d : iterable(dis)) {
                if (PyObj.truthy(PyObj.get(d, "reason"))) {
                    addHashable(dismissed, PyObj.item(d, "candidate_id"));
                }
            }
        }
        Set<String> provisionIds = new LinkedHashSet<>();
        JsonNode pids = PyObj.get(ledger, "provision_ids");
        if (pids != null) {
            for (JsonNode p : iterable(pids)) {
                if (p.isString()) {
                    provisionIds.add(p.stringValue());
                }
            }
        }
        List<ObjectNode> gaps = new ArrayList<>();
        for (Map.Entry<String, String> e : provisions.entrySet()) {
            String pid = e.getKey();
            if (!provisionIds.contains(pid)) {
                continue;
            }
            for (ObjectNode c : extractCandidates(pid, e.getValue())) {
                String id = c.get("candidate_id").stringValue();
                if (!covered.contains(id) && !dismissed.contains(id)) {
                    ObjectNode g = Json.object();
                    g.put("candidate_id", id);
                    g.set("kind", c.get("kind"));
                    g.set("quote", c.get("quote"));
                    g.put("provision_id", pid);
                    g.put("message", "원장에 반영되지도, 사유와 함께 제외되지도 않은 원문 사실");
                    gaps.add(g);
                }
            }
        }
        return gaps;
    }

    /** {@code sha256(json.dumps(ledger, ensure_ascii=False, sort_keys=True))}. */
    public static String ledgerHash(JsonNode ledger) {
        return Py.sha256Hex(PyJson.dumpsSorted(ledger));
    }

    /** 파이썬 set 에 넣기: 문자열만 후보 id 와 같을 수 있다. list·dict 는 unhashable(TypeError). */
    private static void addHashable(Set<String> set, JsonNode v) {
        if (v.isArray() || v.isObject()) {
            throw new IllegalArgumentException("TypeError: unhashable type");
        }
        if (v.isString()) {
            set.add(v.stringValue());
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode v) {
        if (v.isArray()) {
            return v.values();
        }
        throw new IllegalArgumentException("TypeError: 목록이 아닌 값을 순회: " + v);
    }
}
