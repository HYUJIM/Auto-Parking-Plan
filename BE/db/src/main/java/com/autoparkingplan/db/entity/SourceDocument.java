package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.autoparkingplan.common.Json;
import com.autoparkingplan.common.Times;

import tools.jackson.databind.JsonNode;

/** 테이블 source_document (파이썬 app.db.models.SourceDocument). */
@Entity
@Table(name = "source_document")
public class SourceDocument extends UuidEntity {
    @Column(name = "key", nullable = false, length = 80)
    private String key;

    @Column(name = "layer", nullable = false, length = 32)
    private String layer;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "official_title", nullable = false)
    private String officialTitle;

    @Column(name = "short_title")
    private String shortTitle;

    @Column(name = "jurisdiction_code", nullable = false, length = 10)
    private String jurisdictionCode;

    @Column(name = "external_system", nullable = false)
    private String externalSystem;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "parent_document_id")
    private UUID parentDocumentId;

    @Column(name = "homepage_url")
    private String homepageUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aliases", nullable = false)
    private JsonNode aliases = Json.object();

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.now();

    /** 자식 목록 (cascade·orphanRemoval). 자식의 document_id 는 add… 메서드가 채운다. */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "document_id", insertable = false, updatable = false)
    @OrderBy("id") // 저장 순서 (DB 실행 계획과 무관하게)
    private List<DocumentVersion> versions = new ArrayList<>();

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getOfficialTitle() {
        return officialTitle;
    }

    public void setOfficialTitle(String officialTitle) {
        this.officialTitle = officialTitle;
    }

    public String getShortTitle() {
        return shortTitle;
    }

    public void setShortTitle(String shortTitle) {
        this.shortTitle = shortTitle;
    }

    public String getJurisdictionCode() {
        return jurisdictionCode;
    }

    public void setJurisdictionCode(String jurisdictionCode) {
        this.jurisdictionCode = jurisdictionCode;
    }

    public String getExternalSystem() {
        return externalSystem;
    }

    public void setExternalSystem(String externalSystem) {
        this.externalSystem = externalSystem;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public UUID getParentDocumentId() {
        return parentDocumentId;
    }

    public void setParentDocumentId(UUID parentDocumentId) {
        this.parentDocumentId = parentDocumentId;
    }

    public String getHomepageUrl() {
        return homepageUrl;
    }

    public void setHomepageUrl(String homepageUrl) {
        this.homepageUrl = homepageUrl;
    }

    public JsonNode getAliases() {
        return aliases;
    }

    public void setAliases(JsonNode aliases) {
        this.aliases = aliases;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<DocumentVersion> getVersions() {
        return versions;
    }

    /** 자식 추가: 외래키(document_id)를 이 엔티티로 맞추고 목록에 넣는다 (저장은 cascade). */
    public DocumentVersion addVersion(DocumentVersion c) {
        c.setDocumentId(getId());
        versions.add(c);
        return c;
    }

}
