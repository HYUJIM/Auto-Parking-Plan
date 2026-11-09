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

/** 테이블 review_log (파이썬 app.db.models.ReviewLog). */
@Entity
@Table(name = "review_log")
public class ReviewLog extends UuidEntity {
    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "reviewer", nullable = false)
    private String reviewer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_json")
    private JsonNode diffJson;

    @Column(name = "comment")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public JsonNode getDiffJson() {
        return diffJson;
    }

    public void setDiffJson(JsonNode diffJson) {
        this.diffJson = diffJson;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
