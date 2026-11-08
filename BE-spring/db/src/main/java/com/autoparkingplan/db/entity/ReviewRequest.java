package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.time.LocalDate;
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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Times;

import tools.jackson.databind.JsonNode;

/** 테이블 review_request (파이썬 app.db.models.ReviewRequest). */
@Entity
@Table(name = "review_request")
public class ReviewRequest extends UuidEntity {
    @Column(name = "pnu", nullable = false, length = 19)
    private String pnu;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "audience", nullable = false, length = 10)
    private String audience = "owner";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", nullable = false)
    private JsonNode inputs = Json.object();

    @Column(name = "engine_version", nullable = false)
    private String engineVersion;

    @Column(name = "ruleset_hash", length = 64)
    private String rulesetHash;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress", nullable = false)
    private JsonNode progress = Json.array();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json")
    private JsonNode resultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error")
    private JsonNode error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 자식 목록 (cascade·orphanRemoval). 자식의 request_id 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "request_id", insertable = false, updatable = false)
    @OrderBy("sortOrder")
    private List<ReviewItem> items = new ArrayList<>();

    public String getPnu() {
        return pnu;
    }

    public void setPnu(String pnu) {
        this.pnu = pnu;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public JsonNode getInputs() {
        return inputs;
    }

    public void setInputs(JsonNode inputs) {
        this.inputs = inputs;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public void setEngineVersion(String engineVersion) {
        this.engineVersion = engineVersion;
    }

    public String getRulesetHash() {
        return rulesetHash;
    }

    public void setRulesetHash(String rulesetHash) {
        this.rulesetHash = rulesetHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getProgress() {
        return progress;
    }

    public void setProgress(JsonNode progress) {
        this.progress = progress;
    }

    public JsonNode getResultJson() {
        return resultJson;
    }

    public void setResultJson(JsonNode resultJson) {
        this.resultJson = resultJson;
    }

    public JsonNode getError() {
        return error;
    }

    public void setError(JsonNode error) {
        this.error = error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public List<ReviewItem> getItems() {
        return items;
    }

    /** 자식 추가: 외래키(request_id)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public ReviewItem addItem(ReviewItem c) {
        c.setRequestId(getId());
        items.add(c);
        return c;
    }

}
