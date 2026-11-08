package com.autoparkingplan.db.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 테이블 delegation (파이썬 app.db.models.Delegation). */
@Entity
@Table(name = "delegation")
public class Delegation extends UuidEntity {
    @Column(name = "from_provision_id", nullable = false)
    private UUID fromProvisionId;

    @Column(name = "to_provision_id", nullable = false)
    private UUID toProvisionId;

    @Column(name = "relation", nullable = false, length = 24)
    private String relation;

    @Column(name = "evidence_text")
    private String evidenceText;

    @Column(name = "created_by", nullable = false, length = 8)
    private String createdBy;

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = "draft";

    public UUID getFromProvisionId() {
        return fromProvisionId;
    }

    public void setFromProvisionId(UUID fromProvisionId) {
        this.fromProvisionId = fromProvisionId;
    }

    public UUID getToProvisionId() {
        return toProvisionId;
    }

    public void setToProvisionId(UUID toProvisionId) {
        this.toProvisionId = toProvisionId;
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation;
    }

    public String getEvidenceText() {
        return evidenceText;
    }

    public void setEvidenceText(String evidenceText) {
        this.evidenceText = evidenceText;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

}
