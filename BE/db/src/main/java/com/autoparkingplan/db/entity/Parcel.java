package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

/** 테이블 parcel (파이썬 app.db.models.Parcel). */
@Entity
@Table(name = "parcel")
public class Parcel extends AssignedIdEntity<String> {
    @Id
    @Column(name = "pnu", nullable = false, length = 19)
    private String pnu;

    @Column(name = "jurisdiction_code", nullable = false, length = 10)
    private String jurisdictionCode;

    @Column(name = "road_address")
    private String roadAddress;

    @Column(name = "jibun_address", nullable = false)
    private String jibunAddress;

    @Column(name = "land_area_m2")
    private Double landAreaM2;

    @Column(name = "land_category")
    private String landCategory;

    @Column(name = "official_land_price")
    private Integer officialLandPrice;

    @Column(name = "road_side")
    private String roadSide;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geom")
    private JsonNode geom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "centroid")
    private JsonNode centroid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_meta", nullable = false)
    private JsonNode sourceMeta;

    @Column(name = "designations_ok", nullable = false)
    private Boolean designationsOk = false;

    @Column(name = "is_example", nullable = false)
    private Boolean isExample = false;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 자식 목록 (cascade·orphanRemoval). 자식의 pnu 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "pnu", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("id") // 저장 순서 (DB 실행 계획과 무관하게)
    private List<ParcelDesignation> designations = new ArrayList<>();

    /** 자식 목록 (cascade·orphanRemoval). 자식의 pnu 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "pnu", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("id") // 저장 순서 (DB 실행 계획과 무관하게)
    private List<ParcelBuilding> buildings = new ArrayList<>();

    @Override
    public String getId() {
        return pnu;
    }

    public String getPnu() {
        return pnu;
    }

    public void setPnu(String pnu) {
        this.pnu = pnu;
    }

    public String getJurisdictionCode() {
        return jurisdictionCode;
    }

    public void setJurisdictionCode(String jurisdictionCode) {
        this.jurisdictionCode = jurisdictionCode;
    }

    public String getRoadAddress() {
        return roadAddress;
    }

    public void setRoadAddress(String roadAddress) {
        this.roadAddress = roadAddress;
    }

    public String getJibunAddress() {
        return jibunAddress;
    }

    public void setJibunAddress(String jibunAddress) {
        this.jibunAddress = jibunAddress;
    }

    public Double getLandAreaM2() {
        return landAreaM2;
    }

    public void setLandAreaM2(Double landAreaM2) {
        this.landAreaM2 = landAreaM2;
    }

    public String getLandCategory() {
        return landCategory;
    }

    public void setLandCategory(String landCategory) {
        this.landCategory = landCategory;
    }

    public Integer getOfficialLandPrice() {
        return officialLandPrice;
    }

    public void setOfficialLandPrice(Integer officialLandPrice) {
        this.officialLandPrice = officialLandPrice;
    }

    public String getRoadSide() {
        return roadSide;
    }

    public void setRoadSide(String roadSide) {
        this.roadSide = roadSide;
    }

    public JsonNode getGeom() {
        return geom;
    }

    public void setGeom(JsonNode geom) {
        this.geom = geom;
    }

    public JsonNode getCentroid() {
        return centroid;
    }

    public void setCentroid(JsonNode centroid) {
        this.centroid = centroid;
    }

    public JsonNode getSourceMeta() {
        return sourceMeta;
    }

    public void setSourceMeta(JsonNode sourceMeta) {
        this.sourceMeta = sourceMeta;
    }

    public Boolean getDesignationsOk() {
        return designationsOk;
    }

    public void setDesignationsOk(Boolean designationsOk) {
        this.designationsOk = designationsOk;
    }

    public Boolean getIsExample() {
        return isExample;
    }

    public void setIsExample(Boolean isExample) {
        this.isExample = isExample;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public List<ParcelDesignation> getDesignations() {
        return designations;
    }

    /** 자식 추가: 외래키(pnu)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public ParcelDesignation addDesignation(ParcelDesignation c) {
        c.setPnu(getPnu());
        designations.add(c);
        return c;
    }

    public List<ParcelBuilding> getBuildings() {
        return buildings;
    }

    /** 자식 추가: 외래키(pnu)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public ParcelBuilding addBuilding(ParcelBuilding c) {
        c.setPnu(getPnu());
        buildings.add(c);
        return c;
    }

}
