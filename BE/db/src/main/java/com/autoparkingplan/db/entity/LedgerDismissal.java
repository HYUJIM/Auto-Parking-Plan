package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import com.autoparkingplan.common.Times;

/** 테이블 ledger_dismissal (파이썬 app.db.models.LedgerDismissal). */
@Entity
@Table(name = "ledger_dismissal")
@IdClass(LedgerDismissal.Key.class)
public class LedgerDismissal extends AssignedIdEntity<LedgerDismissal.Key> {
    @Id
    @Column(name = "ledger_id", nullable = false)
    private UUID ledgerId;

    @Id
    @Column(name = "candidate_id", nullable = false, length = 40)
    private String candidateId;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "reviewer", nullable = false)
    private String reviewer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    @Override
    public Key getId() {
        return new Key(ledgerId, candidateId);
    }

    /** 복합 기본키. */
    public record Key(UUID ledgerId, String candidateId) implements java.io.Serializable {
        public Key() {
            this(null, null);
        }
    }

    public UUID getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(UUID ledgerId) {
        this.ledgerId = ledgerId;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(String candidateId) {
        this.candidateId = candidateId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

}
