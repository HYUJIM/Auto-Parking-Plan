package com.autoparkingplan.db.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

/** 테이블 rule_condition (파이썬 app.db.models.RuleCondition). */
@Entity
@Table(name = "rule_condition")
public class RuleCondition extends UuidEntity {
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "field", nullable = false, length = 24)
    private String field;

    @Column(name = "operator", nullable = false, length = 8)
    private String operator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_json", nullable = false)
    private JsonNode valueJson;

    @Column(name = "unit")
    private String unit;

    public UUID getRuleId() {
        return ruleId;
    }

    public void setRuleId(UUID ruleId) {
        this.ruleId = ruleId;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public JsonNode getValueJson() {
        return valueJson;
    }

    public void setValueJson(JsonNode valueJson) {
        this.valueJson = valueJson;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

}
