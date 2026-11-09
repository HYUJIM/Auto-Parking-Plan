package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

import com.autoparkingplan.common.Times;

/** 테이블 explanation (파이썬 app.db.models.Explanation). */
@Entity
@Table(name = "explanation")
public class Explanation extends UuidEntity {
    @Column(name = "cache_key", nullable = false, length = 64)
    private String cacheKey;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "audience", nullable = false, length = 10)
    private String audience;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation_json")
    private JsonNode explanationJson;

    @Column(name = "extraction_run_id")
    private UUID extractionRunId;

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = "auto_checked";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    @Column(name = "ledger_id")
    private UUID ledgerId;

    @Column(name = "display_source", nullable = false, length = 20)
    private String displaySource = "basis_only";

    @Column(name = "passed_report_id")
    private UUID passedReportId;

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public JsonNode getExplanationJson() {
        return explanationJson;
    }

    public void setExplanationJson(JsonNode explanationJson) {
        this.explanationJson = explanationJson;
    }

    public UUID getExtractionRunId() {
        return extractionRunId;
    }

    public void setExtractionRunId(UUID extractionRunId) {
        this.extractionRunId = extractionRunId;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(UUID ledgerId) {
        this.ledgerId = ledgerId;
    }

    public String getDisplaySource() {
        return displaySource;
    }

    public void setDisplaySource(String displaySource) {
        this.displaySource = displaySource;
    }

    public UUID getPassedReportId() {
        return passedReportId;
    }

    public void setPassedReportId(UUID passedReportId) {
        this.passedReportId = passedReportId;
    }

}
