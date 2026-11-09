package com.autoparkingplan.db.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 테이블 parcel_designation (파이썬 app.db.models.ParcelDesignation). */
@Entity
@Table(name = "parcel_designation")
public class ParcelDesignation extends UuidEntity {
    @Column(name = "pnu", nullable = false, length = 19)
    private String pnu;

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code")
    private String code;

    @Column(name = "conflict", length = 24)
    private String conflict;

    @Column(name = "basis_law")
    private String basisLaw;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public String getPnu() {
        return pnu;
    }

    public void setPnu(String pnu) {
        this.pnu = pnu;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getConflict() {
        return conflict;
    }

    public void setConflict(String conflict) {
        this.conflict = conflict;
    }

    public String getBasisLaw() {
        return basisLaw;
    }

    public void setBasisLaw(String basisLaw) {
        this.basisLaw = basisLaw;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

}
