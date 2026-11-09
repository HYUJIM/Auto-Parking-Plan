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

/** 테이블 verification_report (파이썬 app.db.models.VerificationReport). */
@Entity
@Table(name = "verification_report")
public class VerificationReport extends UuidEntity {
    @Column(name = "explanation_id")
    private UUID explanationId;

    @Column(name = "ledger_id", nullable = false)
    private UUID ledgerId;

    @Column(name = "attempt_no", nullable = false)
    private Short attemptNo;

    @Column(name = "candidate_source", nullable = false, length = 20)
    private String candidateSource;

    @Column(name = "verdict", nullable = false, length = 4)
    private String verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_codes", nullable = false)
    private JsonNode errorCodes = Json.array();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", nullable = false)
    private JsonNode reportJson;

    @Column(name = "verifier_version", nullable = false)
    private String verifierVersion;

    @Column(name = "judge_model")
    private String judgeModel;

    @Column(name = "ledger_hash", nullable = false, length = 64)
    private String ledgerHash;

    @Column(name = "explanation_hash", nullable = false, length = 64)
    private String explanationHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    public UUID getExplanationId() {
        return explanationId;
    }

    public void setExplanationId(UUID explanationId) {
        this.explanationId = explanationId;
    }

    public UUID getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(UUID ledgerId) {
        this.ledgerId = ledgerId;
    }

    public Short getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Short attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getCandidateSource() {
        return candidateSource;
    }

    public void setCandidateSource(String candidateSource) {
        this.candidateSource = candidateSource;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public JsonNode getErrorCodes() {
        return errorCodes;
    }

    public void setErrorCodes(JsonNode errorCodes) {
        this.errorCodes = errorCodes;
    }

    public JsonNode getReportJson() {
        return reportJson;
    }

    public void setReportJson(JsonNode reportJson) {
        this.reportJson = reportJson;
    }

    public String getVerifierVersion() {
        return verifierVersion;
    }

    public void setVerifierVersion(String verifierVersion) {
        this.verifierVersion = verifierVersion;
    }

    public String getJudgeModel() {
        return judgeModel;
    }

    public void setJudgeModel(String judgeModel) {
        this.judgeModel = judgeModel;
    }

    public String getLedgerHash() {
        return ledgerHash;
    }

    public void setLedgerHash(String ledgerHash) {
        this.ledgerHash = ledgerHash;
    }

    public String getExplanationHash() {
        return explanationHash;
    }

    public void setExplanationHash(String explanationHash) {
        this.explanationHash = explanationHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
