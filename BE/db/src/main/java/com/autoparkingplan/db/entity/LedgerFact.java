package com.autoparkingplan.db.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/** 테이블 ledger_fact (파이썬 app.db.models.LedgerFact). */
@Entity
@Table(name = "ledger_fact")
public class LedgerFact extends UuidEntity {
    @Column(name = "ledger_id", nullable = false)
    private UUID ledgerId;

    @Column(name = "fact_key", nullable = false, length = 40)
    private String factKey;

    @Column(name = "kind", nullable = false, length = 12)
    private String kind;

    @Column(name = "origin", nullable = false, length = 10)
    private String origin;

    @Column(name = "provision_id")
    private UUID provisionId;

    @Column(name = "quote", nullable = false)
    private String quote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical")
    private JsonNode canonical;

    @Column(name = "modality", length = 10)
    private String modality;

    @Column(name = "name")
    private String name;

    @Column(name = "status_value", length = 16)
    private String statusValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_terms", nullable = false)
    private JsonNode keyTerms = Json.array();

    @Column(name = "must_mention", nullable = false)
    private Boolean mustMention;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_ids", nullable = false)
    private JsonNode candidateIds = Json.array();

    public UUID getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(UUID ledgerId) {
        this.ledgerId = ledgerId;
    }

    public String getFactKey() {
        return factKey;
    }

    public void setFactKey(String factKey) {
        this.factKey = factKey;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public UUID getProvisionId() {
        return provisionId;
    }

    public void setProvisionId(UUID provisionId) {
        this.provisionId = provisionId;
    }

    public String getQuote() {
        return quote;
    }

    public void setQuote(String quote) {
        this.quote = quote;
    }

    public JsonNode getCanonical() {
        return canonical;
    }

    public void setCanonical(JsonNode canonical) {
        this.canonical = canonical;
    }

    public String getModality() {
        return modality;
    }

    public void setModality(String modality) {
        this.modality = modality;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatusValue() {
        return statusValue;
    }

    public void setStatusValue(String statusValue) {
        this.statusValue = statusValue;
    }

    public JsonNode getKeyTerms() {
        return keyTerms;
    }

    public void setKeyTerms(JsonNode keyTerms) {
        this.keyTerms = keyTerms;
    }

    public Boolean getMustMention() {
        return mustMention;
    }

    public void setMustMention(Boolean mustMention) {
        this.mustMention = mustMention;
    }

    public JsonNode getCandidateIds() {
        return candidateIds;
    }

    public void setCandidateIds(JsonNode candidateIds) {
        this.candidateIds = candidateIds;
    }

}
