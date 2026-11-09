package com.autoparkingplan.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 테이블 jurisdiction (파이썬 app.db.models.Jurisdiction). */
@Entity
@Table(name = "jurisdiction")
public class Jurisdiction extends AssignedIdEntity<String> {
    @Id
    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "level", nullable = false)
    private Short level;

    @Column(name = "parent_code", length = 10)
    private String parentCode;

    @Column(name = "is_supported", nullable = false)
    private Boolean isSupported = false;

    @Override
    public String getId() {
        return code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Short getLevel() {
        return level;
    }

    public void setLevel(Short level) {
        this.level = level;
    }

    public String getParentCode() {
        return parentCode;
    }

    public void setParentCode(String parentCode) {
        this.parentCode = parentCode;
    }

    public Boolean getIsSupported() {
        return isSupported;
    }

    public void setIsSupported(Boolean isSupported) {
        this.isSupported = isSupported;
    }

}
