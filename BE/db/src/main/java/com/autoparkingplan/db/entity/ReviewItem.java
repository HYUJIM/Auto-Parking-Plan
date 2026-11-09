package com.autoparkingplan.db.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/** 테이블 review_item (파이썬 app.db.models.ReviewItem). */
@Entity
@Table(name = "review_item")
public class ReviewItem extends UuidEntity {
    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_rule_ids", nullable = false)
    private JsonNode appliedRuleIds = Json.array();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false)
    private JsonNode resultJson;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getAppliedRuleIds() {
        return appliedRuleIds;
    }

    public void setAppliedRuleIds(JsonNode appliedRuleIds) {
        this.appliedRuleIds = appliedRuleIds;
    }

    public JsonNode getResultJson() {
        return resultJson;
    }

    public void setResultJson(JsonNode resultJson) {
        this.resultJson = resultJson;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

}
