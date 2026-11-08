package com.autoparkingplan.server.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.ai.llm.LLMClient;
import com.autoparkingplan.ai.llm.LLMError;
import com.autoparkingplan.ai.llm.LLMResult;
import com.autoparkingplan.ai.llm.Task;
import com.autoparkingplan.ai.schema.JsonSchemas;
import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.SourceDocument;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 오프라인 배치: 조문 → 규칙 JSON (AI) → 기계 검증 → draft/auto_checked 저장 (설계서 6장 2–4단계, 재추출 6단계) — 파이썬 app.extraction.extractor.
 * 대상 선별(2단계): MVP 카테고리 키워드 + 이미 연결된 위임 조문. AI 는 조문 묶음(조 본문 + 하위 항·호·목)을 받아 rule_extraction@1.0
 * 형식으로만 답한다. 기계 검증 반려 시 반려 사유를 붙여 최대 2회 재추출한다.
 */
public final class Extractor {
    private static final Logger log = LoggerFactory.getLogger(Extractor.class);
    public static final Map<String, List<String>> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        CATEGORY_KEYWORDS.put("BUILDING_COVERAGE", List.of("건폐율"));
        CATEGORY_KEYWORDS.put("FLOOR_AREA_RATIO", List.of("용적률"));
        CATEGORY_KEYWORDS.put("ZONING_USE", List.of("건축할 수 있는 건축물", "건축제한", "건축할 수 없는 건축물"));
        CATEGORY_KEYWORDS.put("PARKING_STALL_SIZE", List.of("주차구획", "주차단위구획"));
        CATEGORY_KEYWORDS.put("DRIVE_AISLE_WIDTH", List.of("차로", "출입구"));
        CATEGORY_KEYWORDS.put("PARKING_REQUIREMENT", List.of("부설주차장의 설치기준", "설치기준", "주차대수"));
    }

    private Extractor() {
    }

    public record Bundle(Provision root, List<Provision> members, List<String> categories) {
    }

    public static List<Bundle> selectBundles(EntityManager em, DocumentVersion version, List<String> categories) {
        List<String> cats = categories == null || categories.isEmpty() ? List.copyOf(CATEGORY_KEYWORDS.keySet()) : categories;
        List<Provision> roots = em.createQuery("select p from Provision p where p.versionId = :v and p.parentId is null order by p.sortOrder",
                Provision.class).setParameter("v", version.getId()).getResultList();
        List<Bundle> out = new ArrayList<>();
        for (Provision r : roots) {
            String hay = (r.getHeading() == null ? "" : r.getHeading()) + "\n" + r.getText();
            List<String> hit = cats.stream().filter(c -> CATEGORY_KEYWORDS.get(c).stream().anyMatch(hay::contains)).toList();
            if (hit.isEmpty()) {
                continue;
            }
            List<Provision> members = em.createQuery("select p from Provision p where p.versionId = :v and p.path like :pre order by p.sortOrder",
                    Provision.class).setParameter("v", version.getId()).setParameter("pre", r.getPath() + "%").getResultList();
            out.add(new Bundle(r, members, hit));
        }
        return out;
    }

    public static List<ExtractionStore.StoreOutcome> extractVersion(EntityManager em, DocumentVersion version, LLMClient llm, List<String> categories,
            int maxRetries) {
        SourceDocument doc = em.find(SourceDocument.class, version.getDocumentId());
        List<ExtractionStore.StoreOutcome> outcomes = new ArrayList<>();
        for (Bundle b : selectBundles(em, version, categories)) {
            ObjectNode payload = Json.object();
            payload.put("document_version_id", version.getId().toString());
            ObjectNode d = payload.putObject("document");
            d.put("title", doc.getOfficialTitle());
            d.put("layer", doc.getLayer());
            d.put("jurisdiction_code", doc.getJurisdictionCode());
            d.put("effective_from", version.getEffectiveFrom() != null ? version.getEffectiveFrom().toString() : null);
            ArrayNode cats = payload.putArray("categories");
            b.categories().forEach(cats::add);
            ArrayNode provs = payload.putArray("provisions");
            for (Provision p : b.members()) {
                ObjectNode x = provs.addObject();
                x.put("provision_id", p.getId().toString());
                x.put("label", p.getLabel());
                x.put("path", p.getPath());
                x.put("text", p.getText());
            }
            List<String> feedback = new ArrayList<>();
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                ObjectNode body = payload.deepCopy();
                if (!feedback.isEmpty()) {
                    StringBuilder sb = new StringBuilder("[기계 검증 반려 사유]");
                    feedback.forEach(x -> sb.append("\n- ").append(x));
                    body.put("rejection_feedback", sb.toString());
                }
                LLMResult res;
                try {
                    res = llm.generateJson(Task.RULE_EXTRACTION, JsonSchemas.prompt("rule_extraction_prompt.md"), PyJson.dumps(body),
                            JsonSchemas.schema("rule_extraction.schema.json"), 16000);
                } catch (LLMError e) {
                    log.warn("추출 실패 {} {}: {}", doc.getKey(), b.root().getLabel(), e.getMessage());
                    break;
                }
                ExtractionStore.StoreOutcome out = ExtractionStore.storeExtraction(em, version, res.data(), llm.provider() + ":" + res.model(),
                        b.members().stream().map(Provision::getId).toList(), ExtractionStore.PROMPT_VERSION);
                outcomes.add(out);
                if (out.status.equals("ok") || out.status.equals("range_error")) {
                    break;
                }
                feedback = out.errors;
                log.info("재추출 {}/{} ({}): {}", attempt + 1, maxRetries, b.root().getLabel(), feedback.subList(0, Math.min(3, feedback.size())));
            }
        }
        return outcomes;
    }
}
