package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

/** 테이블 parcel_building (파이썬 app.db.models.ParcelBuilding). */
@Entity
@Table(name = "parcel_building")
public class ParcelBuilding extends UuidEntity {
    @Column(name = "pnu", nullable = false, length = 19)
    private String pnu;

    @Column(name = "main_use")
    private String mainUse;

    @Column(name = "gross_floor_area")
    private Double grossFloorArea;

    @Column(name = "building_area")
    private Double buildingArea;

    @Column(name = "approval_date")
    private LocalDate approvalDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw")
    private JsonNode raw;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public String getPnu() {
        return pnu;
    }

    public void setPnu(String pnu) {
        this.pnu = pnu;
    }

    public String getMainUse() {
        return mainUse;
    }

    public void setMainUse(String mainUse) {
        this.mainUse = mainUse;
    }

    public Double getGrossFloorArea() {
        return grossFloorArea;
    }

    public void setGrossFloorArea(Double grossFloorArea) {
        this.grossFloorArea = grossFloorArea;
    }

    public Double getBuildingArea() {
        return buildingArea;
    }

    public void setBuildingArea(Double buildingArea) {
        this.buildingArea = buildingArea;
    }

    public LocalDate getApprovalDate() {
        return approvalDate;
    }

    public void setApprovalDate(LocalDate approvalDate) {
        this.approvalDate = approvalDate;
    }

    public JsonNode getRaw() {
        return raw;
    }

    public void setRaw(JsonNode raw) {
        this.raw = raw;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

}
