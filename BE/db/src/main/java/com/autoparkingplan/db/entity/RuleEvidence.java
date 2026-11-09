package com.autoparkingplan.db.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 테이블 rule_evidence (파이썬 app.db.models.RuleEvidence). */
@Entity
@Table(name = "rule_evidence")
public class RuleEvidence extends UuidEntity {
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "rule_value_id")
    private UUID ruleValueId;

    @Column(name = "provision_id", nullable = false)
    private UUID provisionId;

    @Column(name = "char_start", nullable = false)
    private Integer charStart;

    @Column(name = "char_end", nullable = false)
    private Integer charEnd;

    @Column(name = "quoted_text", nullable = false)
    private String quotedText;

    @Column(name = "role", nullable = false, length = 12)
    private String role = "value";

    /** 읽기 전용 연관 (provision_id 로 저장한다). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "provision_id", insertable = false, updatable = false)
    private Provision provision;

    public UUID getRuleId() {
        return ruleId;
    }

    public void setRuleId(UUID ruleId) {
        this.ruleId = ruleId;
    }

    public UUID getRuleValueId() {
        return ruleValueId;
    }

    public void setRuleValueId(UUID ruleValueId) {
        this.ruleValueId = ruleValueId;
    }

    public UUID getProvisionId() {
        return provisionId;
    }

    public void setProvisionId(UUID provisionId) {
        this.provisionId = provisionId;
    }

    public Integer getCharStart() {
        return charStart;
    }

    public void setCharStart(Integer charStart) {
        this.charStart = charStart;
    }

    public Integer getCharEnd() {
        return charEnd;
    }

    public void setCharEnd(Integer charEnd) {
        this.charEnd = charEnd;
    }

    public String getQuotedText() {
        return quotedText;
    }

    public void setQuotedText(String quotedText) {
        this.quotedText = quotedText;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Provision getProvision() {
        return provision;
    }

    /** 연관과 외래키(provision_id)를 함께 설정한다. */
    public void setProvision(Provision provision) {
        this.provision = provision;
        this.provisionId = provision == null ? null : provision.getId();
    }

}
