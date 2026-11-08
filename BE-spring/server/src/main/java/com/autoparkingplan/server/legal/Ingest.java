package com.autoparkingplan.server.legal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Py;
import com.autoparkingplan.common.PyJson;
import com.autoparkingplan.db.entity.Delegation;
import com.autoparkingplan.db.entity.DocumentVersion;
import com.autoparkingplan.db.entity.Jurisdiction;
import com.autoparkingplan.db.entity.Provision;
import com.autoparkingplan.db.entity.ReviewLog;
import com.autoparkingplan.db.entity.Rule;
import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.legal.ArticleText;
import com.autoparkingplan.legal.DocumentMeta;
import com.autoparkingplan.legal.SourceBundle;
import com.autoparkingplan.legal.Structure;
import com.autoparkingplan.legal.VersionMeta;
import com.autoparkingplan.server.storage.BlobStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 원문 적재기: SourceBundle → source_document / document_version / provision (+ 파서 기반 위임 관계) — 파이썬 app.legal.ingest.
 *
 * <p>수집 원칙 (설계서 4장)
 * <ul>
 *   <li>원문은 받은 그대로 객체 저장소에 두고 sha256 으로 변경을 감지한다.</li>
 *   <li>해시가 바뀌면 새 document_version 을 만든다. 그 버전의 규칙은 다시 추출·검수 대기가 된다.</li>
 *   <li>시행일이 미래인 버전도 저장하되 effective_from 으로 걸러 검토에 쓰지 않는다.</li>
 *   <li>같은 문서의 버전들은 시행일 순으로 effective_to(다음 버전 시행 전날)를 채운다.</li>
 * </ul>
 */
public final class Ingest {
    private static final Logger log = LoggerFactory.getLogger(Ingest.class);
    static final Map<String, String> CONTENT_TYPES = Map.of("xml", "application/xml", "html", "text/html; charset=utf-8", "pdf",
            "application/pdf", "json", "application/json");
    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private Ingest() {
    }

    /**
     * 적재 결과.
     *
     * @param created       새 버전(시행일·공포번호가 다른 버전)을 만들었는지
     * @param provisions    추가한 조문 수
     * @param textMismatch  같은 버전을 다시 받았는데 본문이 다른 조문 path
     * @param textConfirmed 다른 출처(예: Open API XML)로 받은 본문이 기존 본문과 일치
     */
    public record IngestResult(UUID documentId, UUID versionId, boolean created, int provisions, List<String> textMismatch,
            boolean textConfirmed) {
        IngestResult(UUID documentId, UUID versionId, boolean created, int provisions) {
            this(documentId, versionId, created, provisions, List.of(), false);
        }
    }

    static LocalDate d(String s) {
        return s == null || s.isEmpty() ? null : LocalDate.parse(s);
    }

    public static SourceBundle loadBundleFile(Path path) {
        return SourceBundle.load(path);
    }

    static byte[] rawBytes(SourceBundle bundle, Path baseDir) {
        String rawFile = bundle.version().rawFile();
        if (rawFile != null && !rawFile.isEmpty() && baseDir != null) {
            Path p = baseDir;
            for (String part : rawFile.split("/")) {
                p = p.resolve(part);
            }
            if (Files.exists(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        // 원문 파일이 없으면 조문 텍스트 자체를 정규 JSON 으로 해시·보관
        ArrayNode arts = Json.array();
        bundle.articles().forEach(a -> arts.add(a.toJson()));
        return PyJson.dumpsSorted(arts).getBytes(StandardCharsets.UTF_8);
    }

    /** 관할이 없으면 만든다 (상위 관할도 함께). 새로 만든 관할은 지원 지역이 아니다. */
    public static Jurisdiction ensureJurisdiction(EntityManager em, String code, String name) {
        Jurisdiction j = em.find(Jurisdiction.class, code);
        if (j != null) {
            return j;
        }
        int level = code.equals("00") ? 0 : code.length() == 2 ? 1 : 2;
        String parent = level == 0 ? null : level == 1 ? "00" : code.substring(0, 2);
        if (parent != null && em.find(Jurisdiction.class, parent) == null) {
            ensureJurisdiction(em, parent, null);
        }
        j = new Jurisdiction();
        j.setCode(code);
        j.setName(name != null && !name.isEmpty() ? name : code);
        j.setLevel((short) level);
        j.setParentCode(parent);
        j.setIsSupported(false);
        em.persist(j);
        em.flush();
        return j;
    }

    private static SourceDocument docByKey(EntityManager em, String key) {
        List<SourceDocument> l = em.createQuery("select d from SourceDocument d where d.key = :k", SourceDocument.class).setParameter("k", key)
                .getResultList();
        return l.isEmpty() ? null : l.getFirst();
    }

    private static ObjectNode aliasesJson(Map<String, String> aliases) {
        ObjectNode o = Json.object();
        if (aliases != null) {
            aliases.forEach(o::put);
        }
        return o;
    }

    public static IngestResult ingestBundle(EntityManager em, SourceBundle bundle, BlobStore blobs, Path baseDir) {
        DocumentMeta dm = bundle.document();
        VersionMeta vm = bundle.version();
        ensureJurisdiction(em, dm.jurisdictionCode(), null);
        SourceDocument doc = docByKey(em, dm.key());
        UUID parentId = null;
        if (dm.parentKey() != null && !dm.parentKey().isEmpty()) {
            SourceDocument parent = docByKey(em, dm.parentKey());
            parentId = parent != null ? parent.getId() : null;
        }
        boolean hasAliases = dm.aliases() != null && !dm.aliases().isEmpty();
        if (doc == null) {
            doc = new SourceDocument();
            doc.setKey(dm.key());
            doc.setLayer(dm.layer());
            doc.setSourceType(dm.sourceType());
            doc.setOfficialTitle(dm.officialTitle());
            doc.setShortTitle(dm.shortTitle());
            doc.setJurisdictionCode(dm.jurisdictionCode());
            doc.setExternalSystem(dm.externalSystem());
            doc.setExternalId(dm.externalId());
            doc.setParentDocumentId(parentId);
            doc.setHomepageUrl(dm.homepageUrl());
            doc.setAliases(aliasesJson(dm.aliases()));
            em.persist(doc);
            em.flush();
        } else {
            doc.setOfficialTitle(dm.officialTitle());
            doc.setShortTitle(dm.shortTitle());
            if (hasAliases) {
                doc.setAliases(aliasesJson(dm.aliases()));
            }
            if (parentId != null) {
                doc.setParentDocumentId(parentId);
            }
        }

        byte[] raw = rawBytes(bundle, baseDir);
        String sha = Py.sha256Hex(raw);
        List<DocumentVersion> existing = em.createQuery("select v from DocumentVersion v where v.documentId = :d and v.rawSha256 = :s",
                DocumentVersion.class).setParameter("d", doc.getId()).setParameter("s", sha).getResultList();
        if (!existing.isEmpty()) {
            return new IngestResult(doc.getId(), existing.getFirst().getId(), false, 0);
        }
        // 같은 버전(시행일 + 공포번호, 또는 외부 버전 id)을 다른 출처·형식으로 다시 받은 경우: 새 버전을 만들지 않고 본문을 대조한다.
        // (예: 개발용 HTML 로 적재한 버전을 매일 국가법령정보 Open API XML 로 재수집)
        DocumentVersion same = sameVersion(em, doc.getId(), vm);
        if (same != null) {
            return reconcile(em, doc, same, bundle, raw, sha, blobs);
        }
        String eff = vm.effectiveFrom() != null && !vm.effectiveFrom().isEmpty() ? vm.effectiveFrom() : "unknown";
        String key = "law/" + dm.key() + "/" + eff + "/" + sha.substring(0, 16) + "." + vm.rawFormat();
        String objKey = blobs.put(key, raw, CONTENT_TYPES.getOrDefault(vm.rawFormat(), "application/octet-stream"));
        DocumentVersion ver = new DocumentVersion();
        ver.setDocumentId(doc.getId());
        ver.setPromulgationNumber(vm.promulgationNumber());
        ver.setPromulgationDate(d(vm.promulgationDate()));
        ver.setEffectiveFrom(d(vm.effectiveFrom()));
        ver.setEffectiveTo(d(vm.effectiveTo()));
        ver.setExternalVersionId(vm.externalVersionId());
        ver.setRawObjectKey(objKey);
        ver.setRawFormat(vm.rawFormat());
        ver.setRawSha256(sha);
        ver.setSourceUrl(vm.sourceUrl());
        ver.setFetchedAt(OffsetDateTime.parse(vm.fetchedAt()).toInstant());
        ver.setVSourceAccess(vm.vSourceAccess());
        ver.setVVersionHeader(vm.vVersionHeader());
        ver.setVTextMatch(vm.vTextMatch());
        ver.setVCurrentVersion(vm.vCurrentVersion());
        ver.setCurrentCheckedOn(d(vm.currentCheckedOn()));
        ver.setNote(vm.note());
        ver.setIsExample(bundle.isExample());
        em.persist(ver);
        em.flush();
        int n = addArticles(em, ver, bundle.articles(), 0);
        boolean hadRules = !em.createQuery("select r.id from Rule r, DocumentVersion v where r.versionId = v.id and v.documentId = :d"
                + " and r.reviewStatus = 'approved'", UUID.class).setParameter("d", doc.getId()).setMaxResults(1).getResultList().isEmpty();
        recomputeEffectiveTo(em, doc.getId());
        if (hadRules) {
            // 설계서 4장: 해시(버전)가 바뀌면 그 버전의 규칙은 다시 추출·검수 대기
            ReviewLog l = new ReviewLog();
            l.setTargetType("document_version");
            l.setTargetId(ver.getId());
            l.setAction("request_reextract");
            l.setReviewer("system");
            l.setComment(dm.officialTitle() + " 새 버전(" + pyStr(vm.effectiveFrom()) + " 시행, " + pyStr(vm.promulgationNumber())
                    + ") — 규칙 재추출·검수 필요");
            em.persist(l);
        }
        return new IngestResult(doc.getId(), ver.getId(), true, n);
    }

    private static String pyStr(String s) {
        return s == null ? "None" : s;
    }

    static String normText(String t) {
        return WS.matcher(t == null ? "" : t).replaceAll("");
    }

    static DocumentVersion sameVersion(EntityManager em, UUID documentId, VersionMeta vm) {
        if (vm.effectiveFrom() == null || vm.effectiveFrom().isEmpty()) {
            return null;
        }
        List<DocumentVersion> cands = em.createQuery("select v from DocumentVersion v where v.documentId = :d and v.effectiveFrom = :e",
                DocumentVersion.class).setParameter("d", documentId).setParameter("e", d(vm.effectiveFrom())).getResultList();
        for (DocumentVersion v : cands) {
            if (notEmpty(vm.promulgationNumber()) && notEmpty(v.getPromulgationNumber())
                    && normText(vm.promulgationNumber()).equals(normText(v.getPromulgationNumber()))) {
                return v;
            }
            if (notEmpty(vm.externalVersionId()) && notEmpty(v.getExternalVersionId()) && vm.externalVersionId().equals(v.getExternalVersionId())) {
                return v;
            }
        }
        return null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    static int addArticles(EntityManager em, DocumentVersion ver, List<ArticleText> articles, int startOrder) {
        int n = 0;
        int order = startOrder;
        for (ArticleText art : articles) {
            String kind = "annex".equals(art.kind()) ? "annex" : "addendum".equals(art.kind()) ? "addendum" : "article";
            Structure.Node tree = Structure.parseArticle(art.label(), art.path(), art.heading(), art.text(), kind);
            tree.setTableJson(art.tableJson());
            Map<String, Provision> byPath = new HashMap<>();
            for (Structure.ProvisionRow row : Structure.flatten(tree, art.text())) {
                order++;
                Provision p = new Provision();
                p.setVersionId(ver.getId());
                p.setParentId(row.parentPath() != null && !row.parentPath().isEmpty() ? byPath.get(row.parentPath()).getId() : null);
                p.setKind(row.kind());
                p.setLabel(row.label());
                p.setPath(row.path());
                p.setHeading(row.heading());
                p.setText(row.text());
                p.setTableJson(row.tableJson());
                p.setSortOrder(order);
                em.persist(p);
                em.flush();
                byPath.put(row.path(), p);
                n++;
            }
        }
        return n;
    }

    static IngestResult reconcile(EntityManager em, SourceDocument doc, DocumentVersion ver, SourceBundle bundle, byte[] raw, String sha,
            BlobStore blobs) {
        Map<String, Provision> roots = new LinkedHashMap<>();
        for (Provision p : em.createQuery("select p from Provision p where p.versionId = :v and p.parentId is null", Provision.class)
                .setParameter("v", ver.getId()).getResultList()) {
            roots.put(p.getPath(), p);
        }
        List<String> mismatch = new ArrayList<>();
        int compared = 0;
        List<ArticleText> newArticles = new ArrayList<>();
        for (ArticleText art : bundle.articles()) {
            Provision cur = roots.get(art.path());
            if (cur == null) {
                newArticles.add(art);
                continue;
            }
            compared++;
            if (!normText(art.text().replace("[[IMAGE", "")).equals(normText(cur.getText().replace("[[IMAGE", "")))) {
                mismatch.add(art.path());
            }
        }
        VersionMeta vm = bundle.version();
        blobs.put("law/" + doc.getKey() + "/" + pyStr(vm.effectiveFrom()) + "/alt-" + sha.substring(0, 16) + "." + vm.rawFormat(), raw,
                CONTENT_TYPES.getOrDefault(vm.rawFormat(), "application/octet-stream"));
        int order = em.createQuery("select p.sortOrder from Provision p where p.versionId = :v", Integer.class).setParameter("v", ver.getId())
                .getResultList().stream().max(Integer::compare).orElse(0);
        int added = mismatch.isEmpty() ? addArticles(em, ver, newArticles, order) : 0;
        String src = vm.sourceUrl();
        if (!mismatch.isEmpty()) {
            ver.setVTextMatch("needs_review");
            ver.setNote(Py.slice((ver.getNote() == null ? "" : ver.getNote()) + " | 재수집 본문 불일치(" + vm.rawFormat() + "): "
                    + String.join(", ", mismatch.subList(0, Math.min(20, mismatch.size()))), 0, 4000));
            ReviewLog l = new ReviewLog();
            l.setTargetType("document_version");
            l.setTargetId(ver.getId());
            l.setAction("comment");
            l.setReviewer("system");
            ObjectNode diff = Json.object();
            diff.set("mismatch_paths", Json.valueToTree(mismatch));
            diff.put("source_url", src);
            l.setDiffJson(diff);
            l.setComment("같은 버전을 다시 받았는데 본문이 다름 — 검수자가 원문을 확인하고 필요하면 재추출");
            em.persist(l);
        } else if (compared > 0 && !vm.rawFormat().equals(ver.getRawFormat())) {
            ver.setVTextMatch("confirmed");
            ver.setNote(Py.slice((ver.getNote() == null ? "" : ver.getNote()) + " | " + vm.rawFormat() + " 원문과 본문 일치 확인 (" + compared + "개 조)",
                    0, 4000));
        }
        em.flush();
        return new IngestResult(doc.getId(), ver.getId(), false, added, mismatch,
                compared > 0 && mismatch.isEmpty() && !vm.rawFormat().equals(ver.getRawFormat()));
    }

    /** 버전 시행일 순으로 effective_to 를 채우고, 그 버전 규칙의 effective_to 도 맞춘다. */
    public static void recomputeEffectiveTo(EntityManager em, UUID documentId) {
        List<DocumentVersion> dated = new ArrayList<>(em.createQuery("select v from DocumentVersion v where v.documentId = :d", DocumentVersion.class)
                .setParameter("d", documentId).getResultList().stream().filter(v -> v.getEffectiveFrom() != null).toList());
        dated.sort(Comparator.comparing(DocumentVersion::getEffectiveFrom));
        for (int i = 0; i < dated.size(); i++) {
            DocumentVersion cur = dated.get(i);
            DocumentVersion nxt = i + 1 < dated.size() ? dated.get(i + 1) : null;
            if (nxt != null && !nxt.getEffectiveFrom().isAfter(cur.getEffectiveFrom())) {
                continue; // 같은 시행일 버전은 만들지 않지만, 혹시 있으면 시행 기간을 뒤집지 않는다
            }
            cur.setEffectiveTo(nxt != null ? nxt.getEffectiveFrom().minusDays(1) : null);
            for (Rule r : em.createQuery("select r from Rule r where r.versionId = :v", Rule.class).setParameter("v", cur.getId()).getResultList()) {
                r.setEffectiveTo(cur.getEffectiveTo());
            }
        }
    }

    // ── 파서 기반 위임 관계: 하위 조문의 '법 제77조', '영 제84조' 같은 참조 → 상위 조문 ──
    static final Pattern REF_RE = Pattern.compile(
            "(?:「([^」]{2,60})」|(같은\\s*법\\s*시행령|같은\\s*법|법|영|시행령|규칙|시행규칙))\\s*제\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?(?:\\s*제\\s*(\\d+)\\s*항)?",
            Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern DELEGATING_WORDS = Pattern.compile("조례로\\s*정|대통령령으로\\s*정|국토교통부령으로\\s*정|부령으로\\s*정|규칙으로\\s*정",
            Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern ANNEX_REF_RE = Pattern.compile("(같은\\s*법\\s*시행령|법|영|시행령|규칙|시행규칙)\\s*별표\\s*(\\d+)(?:\\s*의\\s*(\\d+))?",
            Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern SELF_ARTICLE_REF_RE = Pattern.compile("\\(\\s*제\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?(?:\\s*제\\s*(\\d+)\\s*항)?[^)]{0,30}관련\\s*\\)",
            Pattern.UNICODE_CHARACTER_CLASS);

    static SourceDocument resolveAlias(EntityManager em, SourceDocument doc, String word, String title) {
        if (title != null && !title.isEmpty()) {
            List<SourceDocument> l = em.createQuery("select d from SourceDocument d where d.officialTitle = :t", SourceDocument.class)
                    .setParameter("t", Py.strip(title)).setMaxResults(1).getResultList();
            return l.isEmpty() ? null : l.getFirst();
        }
        String w = normText(word);
        Map<String, String> aliases = new HashMap<>();
        if (doc.getAliases() != null) {
            for (Map.Entry<String, JsonNode> e : doc.getAliases().properties()) {
                aliases.put(normText(e.getKey()), e.getValue().asString());
            }
        }
        if (aliases.containsKey(w)) {
            return docByKey(em, aliases.get(w));
        }
        if (w.equals("법") && doc.getParentDocumentId() != null) { // 시행령·시행규칙의 '법' = 모법
            return em.find(SourceDocument.class, doc.getParentDocumentId());
        }
        if ((w.equals("영") || w.equals("시행령") || w.equals("같은법시행령")) && doc.getParentDocumentId() != null) {
            SourceDocument parent = em.find(SourceDocument.class, doc.getParentDocumentId());
            if (parent == null) {
                return null;
            }
            List<SourceDocument> l = em.createQuery("select d from SourceDocument d where d.parentDocumentId = :p and d.sourceType = 'decree'",
                    SourceDocument.class).setParameter("p", parent.getId()).setMaxResults(1).getResultList();
            return l.isEmpty() ? null : l.getFirst();
        }
        return null;
    }

    static DocumentVersion versionAt(EntityManager em, UUID documentId, LocalDate on) {
        List<DocumentVersion> vs = em.createQuery("select v from DocumentVersion v where v.documentId = :d", DocumentVersion.class)
                .setParameter("d", documentId).getResultList();
        List<DocumentVersion> dated = new ArrayList<>(vs.stream().filter(v -> v.getEffectiveFrom() != null).toList());
        dated.sort(Comparator.comparing(DocumentVersion::getEffectiveFrom));
        if (on != null) {
            List<DocumentVersion> cands = dated.stream().filter(v -> !v.getEffectiveFrom().isAfter(on)).toList();
            if (!cands.isEmpty()) {
                return cands.getLast();
            }
        }
        return !dated.isEmpty() ? dated.getLast() : (!vs.isEmpty() ? vs.getFirst() : null);
    }

    static Provision target(EntityManager em, UUID versionId, String path, String para) {
        Provision t = null;
        if (para != null) {
            t = byPath(em, versionId, path + "/para" + para);
        }
        return t != null ? t : byPath(em, versionId, path);
    }

    private static Provision byPath(EntityManager em, UUID versionId, String path) {
        List<Provision> l = em.createQuery("select p from Provision p where p.versionId = :v and p.path = :p", Provision.class)
                .setParameter("v", versionId).setParameter("p", path).setMaxResults(1).getResultList();
        return l.isEmpty() ? null : l.getFirst();
    }

    static int edge(EntityManager em, Provision upper, Provision lower, String evidence) {
        String relation = DELEGATING_WORDS.matcher(upper.getText()).find() ? "delegates" : "implements";
        boolean exists = !em.createQuery("select d.id from Delegation d where d.fromProvisionId = :f and d.toProvisionId = :t and d.relation = :r",
                UUID.class).setParameter("f", upper.getId()).setParameter("t", lower.getId()).setParameter("r", relation).setMaxResults(1)
                .getResultList().isEmpty();
        if (exists || upper.getId().equals(lower.getId())) {
            return 0;
        }
        Delegation d = new Delegation();
        d.setFromProvisionId(upper.getId());
        d.setToProvisionId(lower.getId());
        d.setRelation(relation);
        d.setEvidenceText(Py.slice(evidence, 0, 300));
        d.setCreatedBy("parser");
        d.setReviewStatus("auto_checked");
        em.persist(d);
        em.flush(); // 같은 실행 안의 다음 존재 확인이 보도록 (파이썬 autoflush)
        return 1;
    }

    /**
     * 모든 버전의 조문에서 상위 법령 참조를 찾아 delegation 엣지를 만든다 (created_by='parser', 결정적).
     * <ul>
     *   <li>'법 제77조제1항', '영 제84조제1항', '「…」 제N조' → 그 조문(항)</li>
     *   <li>'영 별표 5', '시행규칙 별표 1' → 그 별표</li>
     *   <li>별표 머리의 '(제71조제1항제4호관련)' → 같은 문서의 그 조문 (별표는 조문을 구체화)</li>
     * </ul>
     */
    public static int linkDelegations(EntityManager em) {
        int created = 0;
        List<Provision> provisions = em.createQuery("select p from Provision p where p.kind in ('article', 'paragraph', 'annex') order by p.id", Provision.class)
                .getResultList();
        for (Provision p : provisions) {
            DocumentVersion ver = em.find(DocumentVersion.class, p.getVersionId());
            SourceDocument doc = em.find(SourceDocument.class, ver.getDocumentId());
            String scan = !"article".equals(p.getKind()) ? p.getText() : p.getText().split("\n", 2)[0];
            Matcher m = REF_RE.matcher(scan);
            while (m.find()) {
                SourceDocument targetDoc = resolveAlias(em, doc, m.group(2), m.group(1));
                if (targetDoc == null || targetDoc.getId().equals(doc.getId())) {
                    continue;
                }
                DocumentVersion tv = versionAt(em, targetDoc.getId(), ver.getEffectiveFrom());
                Provision t = tv != null ? target(em, tv.getId(), "art" + m.group(3) + (m.group(4) != null ? "-" + m.group(4) : ""), m.group(5)) : null;
                if (t != null) {
                    created += edge(em, t, p, m.group(0));
                }
            }
            Matcher a = ANNEX_REF_RE.matcher(scan);
            while (a.find()) {
                SourceDocument targetDoc = resolveAlias(em, doc, a.group(1), null);
                if (targetDoc == null || targetDoc.getId().equals(doc.getId())) {
                    continue;
                }
                DocumentVersion tv = versionAt(em, targetDoc.getId(), ver.getEffectiveFrom());
                Provision t = tv != null ? target(em, tv.getId(), "annex" + a.group(2) + (a.group(3) != null ? "-" + a.group(3) : ""), null) : null;
                if (t != null) {
                    created += edge(em, t, p, a.group(0));
                }
            }
            if ("annex".equals(p.getKind()) && p.getParentId() == null) {
                Matcher s = SELF_ARTICLE_REF_RE.matcher(Py.slice(p.getText(), 0, 400));
                if (s.find()) {
                    Provision t = target(em, p.getVersionId(), "art" + s.group(1) + (s.group(2) != null ? "-" + s.group(2) : ""), s.group(3));
                    if (t != null) {
                        created += edge(em, t, p, s.group(0));
                    }
                }
            }
        }
        em.flush();
        log.debug("위임 관계 {}개 생성", created);
        return created;
    }
}
