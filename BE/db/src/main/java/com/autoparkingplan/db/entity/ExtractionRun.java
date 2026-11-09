package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Times;

import tools.jackson.databind.JsonNode;

/** 테이블 extraction_run (파이썬 app.db.models.ExtractionRun). */
@Entity
@Table(name = "extraction_run")
public class ExtractionRun extends UuidEntity {
    @Column(name = "version_id")
    private UUID versionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provision_ids", nullable = false)
    private JsonNode provisionIds = Json.array();

    @Column(name = "task", nullable = false, length = 32)
    private String task;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    @Column(name = "input_sha256", nullable = false, length = 64)
    private String inputSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json")
    private JsonNode outputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation")
    private JsonNode validation;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    public UUID getVersionId() {
        return versionId;
    }

    public void setVersionId(UUID versionId) {
        this.versionId = versionId;
    }

    public JsonNode getProvisionIds() {
        return provisionIds;
    }

    public void setProvisionIds(JsonNode provisionIds) {
        this.provisionIds = provisionIds;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getInputSha256() {
        return inputSha256;
    }

    public void setInputSha256(String inputSha256) {
        this.inputSha256 = inputSha256;
    }

    public JsonNode getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(JsonNode outputJson) {
        this.outputJson = outputJson;
    }

    public JsonNode getValidation() {
        return validation;
    }

    public void setValidation(JsonNode validation) {
        this.validation = validation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
