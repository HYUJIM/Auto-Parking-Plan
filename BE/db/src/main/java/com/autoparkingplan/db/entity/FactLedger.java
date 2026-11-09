package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Times;

import tools.jackson.databind.JsonNode;

/** 테이블 fact_ledger (파이썬 app.db.models.FactLedger). */
@Entity
@Table(name = "fact_ledger")
public class FactLedger extends UuidEntity {
    @Column(name = "kind", nullable = false, length = 10)
    private String kind = "rule";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_ids", nullable = false)
    private JsonNode ruleIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provision_ids", nullable = false)
    private JsonNode provisionIds;

    @Column(name = "review_status", nullable = false, length = 12)
    private String reviewStatus = "draft";

    @Column(name = "ledger_hash", nullable = false, length = 64)
    private String ledgerHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ledger_json", nullable = false)
    private JsonNode ledgerJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completeness_gaps", nullable = false)
    private JsonNode completenessGaps = Json.array();

    @Column(name = "gap_count", nullable = false)
    private Integer gapCount = 0;

    @Column(name = "status_value", length = 16)
    private String statusValue;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    /** 자식 목록 (cascade·orphanRemoval). 자식의 ledger_id 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ledger_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("id") // 저장 순서 (DB 실행 계획과 무관하게)
    private List<LedgerFact> facts = new ArrayList<>();

    /** 자식 목록 (cascade·orphanRemoval). 자식의 ledger_id 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ledger_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("candidateId") // 후보 id 순 (ledger_to_dict 와 같은 순서)
    private List<LedgerDismissal> dismissals = new ArrayList<>();

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public JsonNode getRuleIds() {
        return ruleIds;
    }

    public void setRuleIds(JsonNode ruleIds) {
        this.ruleIds = ruleIds;
    }

    public JsonNode getProvisionIds() {
        return provisionIds;
    }

    public void setProvisionIds(JsonNode provisionIds) {
        this.provisionIds = provisionIds;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getLedgerHash() {
        return ledgerHash;
    }

    public void setLedgerHash(String ledgerHash) {
        this.ledgerHash = ledgerHash;
    }

    public JsonNode getLedgerJson() {
        return ledgerJson;
    }

    public void setLedgerJson(JsonNode ledgerJson) {
        this.ledgerJson = ledgerJson;
    }

    public JsonNode getCompletenessGaps() {
        return completenessGaps;
    }

    public void setCompletenessGaps(JsonNode completenessGaps) {
        this.completenessGaps = completenessGaps;
    }

    public Integer getGapCount() {
        return gapCount;
    }

    public void setGapCount(Integer gapCount) {
        this.gapCount = gapCount;
    }

    public String getStatusValue() {
        return statusValue;
    }

    public void setStatusValue(String statusValue) {
        this.statusValue = statusValue;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<LedgerFact> getFacts() {
        return facts;
    }

    /** 자식 추가: 외래키(ledger_id)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public LedgerFact addFact(LedgerFact c) {
        c.setLedgerId(getId());
        facts.add(c);
        return c;
    }

    public List<LedgerDismissal> getDismissals() {
        return dismissals;
    }

    /** 자식 추가: 외래키(ledger_id)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public LedgerDismissal addDismissal(LedgerDismissal c) {
        c.setLedgerId(getId());
        dismissals.add(c);
        return c;
    }

}
