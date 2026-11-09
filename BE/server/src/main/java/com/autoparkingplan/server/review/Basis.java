package com.autoparkingplan.server.review;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.db.entity.Delegation;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.RuleEvidence;
import com.autoparkingplan.db.entity.RuleValue;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.legal.Structure;
import com.autoparkingplan.server.security.Sanitize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 근거 트리 (설계서 7장 8단계, 기능정의서 12장): 적용 규칙의 rule_evidence → provision 에서 출발해 delegation 을 거꾸로 올라가며
 * L1 법률까지 노드를 만든다. 상위 → 하위 순서로 정렬한다 (파이썬 app.review.basis).
 */
public final class Basis {
    public static final Map<String, Integer> LAYER_ORDER = Map.of("L1_NATIONAL", 0, "L2_LAND_USE", 1, "L3_LOCAL", 2, "L4_SPECIAL_ZONE", 3);
    public static final Map<String, Integer> TYPE_ORDER = Map.of("law", 0, "decree", 1, "rule", 2, "ordinance", 3, "local_rule", 4,
            "notice", 5, "plan", 6);
    static final List<String> HEADLINE_VALUE_KEYS = List.of("non_parallel.general.width", "parallel.general.width",
            "perpendicular_90.one_gate", "ratio");

    private Basis() {
    }

    /** 근거 노드 하나와 정렬 키 (layer 순서, 문서 종류 순서). */
    public record Node(ObjectNode json, int layerOrder, int typeOrder) {
    }

    static Provision root(EntityManager em, Provision p) {
        Provision root = p;
        while (root.getParentId() != null) {
            root = em.find(Provision.class, root.getParentId());
        }
        return root;
    }

    /** '제84조제1항제4호' 같은 근거 표시 이름. 별표는 띄어쓰기를 뺀 이름을 기준으로 한다. */
    public static String provisionLabel(EntityManager em, Provision p) {
        Provision root = root(em, p);
        String base = root.getLabel();
        List<String> segs = new ArrayList<>();
        for (String seg : p.getPath().split("~", -1)[0].split("/", -1)) {
            if (!seg.startsWith("tbl") && !seg.startsWith("row")) {
                segs.add(seg);
            }
        }
        return Structure.articleLabelFor(String.join("/", segs), "annex".equals(root.getKind()) ? base.replace(" ", "") : base);
    }

    private static Provision transcriptionTable(EntityManager em, Provision p) {
        Provision cur = p;
        while (cur != null) {
            JsonNode t = cur.getTableJson();
            if (t != null && t.isObject() && "image_table".equals(Json.text(t, "supplement"))) {
                return cur;
            }
            cur = cur.getParentId() != null ? em.find(Provision.class, cur.getParentId()) : null;
        }
        return null;
    }

    static String verbatimPrefix(String text, int limit) {
        String t = Py.strip(text);
        if (Py.len(t) <= limit) {
            return t;
        }
        String cut = Py.slice(t, 0, limit);
        for (String mark : new String[] {"다. ", "다.\n", ". "}) {
            int i = cut.lastIndexOf(mark);
            if (i >= 0 && Py.cpIndex(cut, i) > 40) {
                return cut.substring(0, i + Py.rstrip(mark).length());
            }
        }
        return cut;
    }

    public static Node nodeFor(EntityManager em, Provision p, String quote, UUID ruleId) {
        DocumentVersion ver = em.find(DocumentVersion.class, p.getVersionId());
        SourceDocument doc = em.find(SourceDocument.class, ver.getDocumentId());
        String label = provisionLabel(em, p);
        String url = ver.getSourceUrl() != null && !ver.getSourceUrl().isEmpty() ? ver.getSourceUrl() : doc.getHomepageUrl();
        Provision table = transcriptionTable(em, p);
        if (table != null) {
            // 원문 화면에 텍스트가 없어 표 이미지를 옮겨 적은 전사본임을 표시하고, 원본 이미지로 연결한다
            JsonNode tr = table.getTableJson().get("transcription");
            String who = tr != null && tr.isObject() ? Json.text(tr, "verified_by") : null;
            if (who == null || who.isEmpty()) {
                who = "사람 대조 전";
            }
            label = provisionLabel(em, table) + " [표 이미지 전사본 · 대조: " + who + "]";
            String su = Json.text(table.getTableJson(), "source_url");
            if (su != null && !su.isEmpty()) {
                url = su;
            }
        }
        ObjectNode n = Json.object();
        n.put("layer", doc.getLayer());
        n.put("document_title", doc.getOfficialTitle());
        n.put("article_label", label);
        n.put("effective_from", ver.getEffectiveFrom() != null ? ver.getEffectiveFrom().toString() : null);
        n.put("quote", Py.slice(quote != null ? quote : verbatimPrefix(p.getText(), 400), 0, 400));
        n.put("url", Sanitize.safeUrl(url)); // 화면 링크 → http(s) 만 (javascript: 등 저장형 XSS 차단)
        n.put("rule_id", ruleId != null ? ruleId.toString() : null);
        return new Node(n, LAYER_ORDER.get(doc.getLayer()), TYPE_ORDER.getOrDefault(doc.getSourceType(), 9));
    }

    static boolean inForce(DocumentVersion v, LocalDate on) {
        return v.getEffectiveFrom() != null && !v.getEffectiveFrom().isAfter(on) && (v.getEffectiveTo() == null || !v.getEffectiveTo().isBefore(on));
    }

    /** 위임 엣지가 가리키는 상위 조문이 기준일에 시행 중인 버전이 아니면, 같은 path 의 시행 중 버전 조문으로 바꾼다. */
    static Provision samePathInEffectiveVersion(EntityManager em, Provision p, LocalDate on) {
        DocumentVersion ver = em.find(DocumentVersion.class, p.getVersionId());
        if (inForce(ver, on)) {
            return p;
        }
        List<DocumentVersion> versions = em.createQuery("select v from DocumentVersion v where v.documentId = :d order by v.id", DocumentVersion.class)
                .setParameter("d", ver.getDocumentId()).getResultList();
        DocumentVersion cur = versions.stream().filter(v -> inForce(v, on)).findFirst().orElse(null);
        if (cur == null) {
            return p;
        }
        List<Provision> same = em.createQuery("select p from Provision p where p.versionId = :v and p.path = :path", Provision.class)
                .setParameter("v", cur.getId()).setParameter("path", p.getPath()).setMaxResults(1).getResultList();
        return same.isEmpty() ? p : same.getFirst();
    }

    /** 근거 트리에 보일 대표 인용: 화면 기준값(일반형·직각주차·비율)의 근거를 우선한다. */
    static RuleEvidence headlineEvidence(Rule rule) {
        Map<UUID, String> byValue = new LinkedHashMap<>();
        for (RuleValue v : rule.getValues()) {
            byValue.put(v.getId(), v.getValueKey());
        }
        List<RuleEvidence> vals = rule.getEvidence().stream().filter(e -> "value".equals(e.getRole())).toList();
        for (String key : HEADLINE_VALUE_KEYS) {
            for (RuleEvidence e : vals) {
                if (key.equals(byValue.get(e.getRuleValueId()))) {
                    return e;
                }
            }
        }
        return vals.isEmpty() ? null : vals.getFirst();
    }

    public static List<ObjectNode> basisForRules(EntityManager em, List<Rule> rules, LocalDate on) {
        return basisForRules(em, rules, on, 4);
    }

    public static List<ObjectNode> basisForRules(EntityManager em, List<Rule> rules, LocalDate on, int maxDepth) {
        Map<UUID, Node> nodes = new LinkedHashMap<>();
        for (Rule rule : rules) {
            RuleEvidence ev = headlineEvidence(rule);
            if (ev == null) {
                continue;
            }
            Provision start = em.find(Provision.class, ev.getProvisionId());
            nodes.computeIfAbsent(start.getId(), k -> nodeFor(em, start, ev.getQuotedText(), rule.getId()));
            List<Provision> frontier = List.of(start);
            for (int d = 0; d < maxDepth; d++) {
                List<Provision> nxt = new ArrayList<>();
                for (Provision p : frontier) {
                    List<UUID> ids = new ArrayList<>();
                    Provision c = p;
                    ids.add(c.getId());
                    while (c.getParentId() != null) {
                        c = em.find(Provision.class, c.getParentId());
                        ids.add(c.getId());
                    }
                    List<Delegation> edges = em.createQuery("select d from Delegation d where d.toProvisionId in :ids"
                            + " and d.reviewStatus in ('auto_checked', 'approved') order by d.id", Delegation.class).setParameter("ids", ids).getResultList();
                    for (Delegation e : edges) {
                        Provision up = samePathInEffectiveVersion(em, em.find(Provision.class, e.getFromProvisionId()), on);
                        if (!nodes.containsKey(up.getId())) {
                            nodes.put(up.getId(), nodeFor(em, up, null, null));
                            nxt.add(up);
                        }
                    }
                }
                frontier = nxt;
                if (frontier.isEmpty()) {
                    break;
                }
            }
        }
        List<Node> ordered = new ArrayList<>(nodes.values());
        ordered.sort(Comparator.comparingInt(Node::layerOrder).thenComparingInt(Node::typeOrder)); // 안정 정렬
        return ordered.stream().map(Node::json).toList();
    }
}
