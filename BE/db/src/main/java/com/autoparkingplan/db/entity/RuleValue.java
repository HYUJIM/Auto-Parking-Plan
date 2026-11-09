package com.autoparkingplan.db.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 테이블 rule_value (파이썬 app.db.models.RuleValue). */
@Entity
@Table(name = "rule_value")
public class RuleValue extends UuidEntity {
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "value_key", nullable = false)
    private String valueKey;

    @Column(name = "value_type", nullable = false, length = 32)
    private String valueType;

    @Column(name = "num_min")
    private Double numMin;

    @Column(name = "num_max")
    private Double numMax;

    @Column(name = "num_exact")
    private Double numExact;

    @Column(name = "enum_value")
    private String enumValue;

    @Column(name = "formula")
    private String formula;

    @Column(name = "unit")
    private String unit;

    @Column(name = "condition_note")
    private String conditionNote;

    public UUID getRuleId() {
        return ruleId;
    }

    public void setRuleId(UUID ruleId) {
        this.ruleId = ruleId;
    }

    public String getValueKey() {
        return valueKey;
    }

    public void setValueKey(String valueKey) {
        this.valueKey = valueKey;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public Double getNumMin() {
        return numMin;
    }

    public void setNumMin(Double numMin) {
        this.numMin = numMin;
    }

    public Double getNumMax() {
        return numMax;
    }

    public void setNumMax(Double numMax) {
        this.numMax = numMax;
    }

    public Double getNumExact() {
        return numExact;
    }

    public void setNumExact(Double numExact) {
        this.numExact = numExact;
    }

    public String getEnumValue() {
        return enumValue;
    }

    public void setEnumValue(String enumValue) {
        this.enumValue = enumValue;
    }

    public String getFormula() {
        return formula;
    }

    public void setFormula(String formula) {
        this.formula = formula;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getConditionNote() {
        return conditionNote;
    }

    public void setConditionNote(String conditionNote) {
        this.conditionNote = conditionNote;
    }

}
