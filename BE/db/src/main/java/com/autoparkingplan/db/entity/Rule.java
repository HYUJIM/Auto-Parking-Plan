package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import com.autoparkingplan.common.Times;

/** 테이블 rule (파이썬 app.db.models.Rule). */
@Entity
@Table(name = "rule")
public class Rule extends UuidEntity {
    @Column(name = "rule_key", nullable = false)
    private String ruleKey;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "layer", nullable = false, length = 32)
    private String layer;

    @Column(name = "jurisdiction_code", nullable = false, length = 10)
    private String jurisdictionCode;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "automation_level", nullable = false, length = 16)
    private String automationLevel;

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = "draft";

    @Column(name = "extraction_run_id")
    private UUID extractionRunId;

    @Column(name = "supersedes_rule_id")
    private UUID supersedesRuleId;

    @Column(name = "confidence", length = 8)
    private String confidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** 읽기 전용 연관 (version_id 로 저장한다). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", insertable = false, updatable = false)
    private DocumentVersion version;

    /** 자식 목록 (cascade·orphanRemoval). 자식의 rule_id 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "rule_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("id") // 저장 순서 (DB 실행 계획과 무관하게)
    private List<RuleCondition> conditions = new ArrayList<>();

    /** 자식 목록 (cascade·orphanRemoval). 자식의 rule_id 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "rule_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("id") // 저장 순서 (DB 실행 계획과 무관하게)
    private List<RuleValue> values = new ArrayList<>();

    /** 자식 목록 (cascade·orphanRemoval). 자식의 rule_id 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "rule_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("id") // 저장 순서 (DB 실행 계획과 무관하게)
    private List<RuleEvidence> evidence = new ArrayList<>();

    public String getRuleKey() {
        return ruleKey;
    }

    public void setRuleKey(String ruleKey) {
        this.ruleKey = ruleKey;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    public String getJurisdictionCode() {
        return jurisdictionCode;
    }

    public void setJurisdictionCode(String jurisdictionCode) {
        this.jurisdictionCode = jurisdictionCode;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public void setVersionId(UUID versionId) {
        this.versionId = versionId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAutomationLevel() {
        return automationLevel;
    }

    public void setAutomationLevel(String automationLevel) {
        this.automationLevel = automationLevel;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public UUID getExtractionRunId() {
        return extractionRunId;
    }

    public void setExtractionRunId(UUID extractionRunId) {
        this.extractionRunId = extractionRunId;
    }

    public UUID getSupersedesRuleId() {
        return supersedesRuleId;
    }

    public void setSupersedesRuleId(UUID supersedesRuleId) {
        this.supersedesRuleId = supersedesRuleId;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    /** 연관과 외래키(version_id)를 함께 설정한다. */
    public void setVersion(DocumentVersion version) {
        this.version = version;
        this.versionId = version == null ? null : version.getId();
    }

    public List<RuleCondition> getConditions() {
        return conditions;
    }

    /** 자식 추가: 외래키(rule_id)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public RuleCondition addCondition(RuleCondition c) {
        c.setRuleId(getId());
        conditions.add(c);
        return c;
    }

    public List<RuleValue> getValues() {
        return values;
    }

    /** 자식 추가: 외래키(rule_id)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public RuleValue addValue(RuleValue c) {
        c.setRuleId(getId());
        values.add(c);
        return c;
    }

    public List<RuleEvidence> getEvidence() {
        return evidence;
    }

    /** 자식 추가: 외래키(rule_id)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public RuleEvidence addEvidence(RuleEvidence c) {
        c.setRuleId(getId());
        evidence.add(c);
        return c;
    }
}
