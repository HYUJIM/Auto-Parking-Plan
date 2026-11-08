package com.autoparkingplan.db;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.Times;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Jurisdiction;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.SourceDocument;

/**
 * 자주 쓰는 조회 (파이썬 app.db.repo). activeRulesAt 은 schema.sql 의 같은 이름 함수와 같은 의미 (이식 가능한 구현).
 * 호출하는 쪽의 트랜잭션 안에서 쓴다.
 */
@Component
public class RuleQueries {
    private final EntityManager em;

    public RuleQueries(EntityManager em) {
        this.em = em;
    }

    /** 문서와 그 버전 한 쌍. */
    public record DocVersion(SourceDocument document, DocumentVersion version) {
    }

    /** 기준일에 시행 중이고 승인된 규칙만. 시행일이 NULL 인 규칙은 검토에 쓰지 않는다. */
    public List<Rule> activeRulesAt(LocalDate on, Collection<String> jurisdictionCodes, Collection<String> categories) {
        StringBuilder q = new StringBuilder("select r from Rule r where r.reviewStatus = 'approved' and r.effectiveFrom is not null"
                + " and r.effectiveFrom <= :on and (r.effectiveTo is null or r.effectiveTo >= :on)");
        if (jurisdictionCodes != null && !jurisdictionCodes.isEmpty()) {
            q.append(" and r.jurisdictionCode in :jur");
        }
        if (categories != null && !categories.isEmpty()) {
            q.append(" and r.category in :cat");
        }
        q.append(" order by r.ruleKey, r.effectiveFrom");
        var query = em.createQuery(q.toString(), Rule.class).setParameter("on", on);
        if (jurisdictionCodes != null && !jurisdictionCodes.isEmpty()) {
            query.setParameter("jur", jurisdictionCodes);
        }
        if (categories != null && !categories.isEmpty()) {
            query.setParameter("cat", categories);
        }
        return query.getResultList();
    }

    public List<Rule> activeRulesAt(LocalDate on) {
        return activeRulesAt(on, null, null);
    }

    /** 사용한 approved 규칙 집합의 해시 (재현성). 규칙 id·키·버전·승인 시각으로 만든다 (파이썬과 같은 문자열). */
    public static String rulesetHash(List<Rule> rules) {
        List<String> parts = new ArrayList<>();
        for (Rule r : rules) {
            parts.add(r.getId() + "|" + r.getRuleKey() + "|" + r.getVersionId() + "|"
                    + (r.getApprovedAt() != null ? Times.isoformat(r.getApprovedAt()) : ""));
        }
        parts.sort(com.autoparkingplan.common.PyJson::compareCodePoints);
        return Py.sha256Hex(String.join("\n", parts));
    }

    /** 시·군·구 → 시·도 → 전국. */
    public static List<String> jurisdictionChain(String code) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        chain.add(code);
        if (code.length() >= 5) {
            chain.add(code.substring(0, 2));
        }
        if (!"00".equals(code)) {
            chain.add("00");
        }
        return List.copyOf(chain);
    }

    public boolean isSupported(String code) {
        Jurisdiction j = em.find(Jurisdiction.class, code);
        return j != null && Boolean.TRUE.equals(j.getIsSupported());
    }

    /** 기준일 이후 시행 예정인 버전 (PENDING_AMENDMENT 안내용). */
    public List<DocVersion> pendingAmendments(LocalDate on, Collection<String> jurisdictionCodes) {
        List<Object[]> rows = em.createQuery("select d, v from SourceDocument d join DocumentVersion v on v.documentId = d.id"
                        + " where d.jurisdictionCode in :jur and v.effectiveFrom > :on order by v.effectiveFrom", Object[].class)
                .setParameter("jur", jurisdictionCodes).setParameter("on", on).getResultList();
        List<DocVersion> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new DocVersion((SourceDocument) r[0], (DocumentVersion) r[1]));
        }
        return out;
    }

    /** 기준일에 시행 중인 버전에 승인 규칙이 없는데, 같은 문서의 이전 버전에는 승인 규칙이 있던 경우 (개정 후 재검수 대기). */
    public List<DocVersion> versionsInForceWithoutRules(LocalDate on, Collection<String> jurisdictionCodes) {
        List<DocVersion> out = new ArrayList<>();
        List<SourceDocument> docs = em.createQuery("select d from SourceDocument d where d.jurisdictionCode in :jur", SourceDocument.class)
                .setParameter("jur", jurisdictionCodes).getResultList();
        for (SourceDocument d : docs) {
            List<DocumentVersion> vs = d.getVersions().stream().filter(v -> v.getEffectiveFrom() != null)
                    .sorted(Comparator.comparing(DocumentVersion::getEffectiveFrom)).toList();
            List<DocumentVersion> cur = vs.stream()
                    .filter(v -> !v.getEffectiveFrom().isAfter(on) && (v.getEffectiveTo() == null || !v.getEffectiveTo().isBefore(on)))
                    .toList();
            if (cur.isEmpty()) {
                continue;
            }
            DocumentVersion curV = cur.getLast();
            boolean hasCur = !em.createQuery("select r.id from Rule r where r.versionId = :v and r.reviewStatus = 'approved'", UUID.class)
                    .setParameter("v", curV.getId()).setMaxResults(1).getResultList().isEmpty();
            List<UUID> older = vs.stream().filter(v -> v.getEffectiveFrom().isBefore(curV.getEffectiveFrom()))
                    .map(DocumentVersion::getId).toList();
            boolean hadOld = !older.isEmpty() && !em.createQuery(
                            "select r.id from Rule r where r.versionId in :vs and r.reviewStatus in ('approved', 'superseded')", UUID.class)
                    .setParameter("vs", older).setMaxResults(1).getResultList().isEmpty();
            if (!hasCur && hadOld) {
                out.add(new DocVersion(d, curV));
            }
        }
        return out;
    }
}
