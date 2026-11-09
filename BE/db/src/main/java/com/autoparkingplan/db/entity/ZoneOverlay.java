package com.autoparkingplan.db.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.autoparkingplan.common.Json;

import tools.jackson.databind.JsonNode;

/** 테이블 zone_overlay (파이썬 app.db.models.ZoneOverlay). */
@Entity
@Table(name = "zone_overlay")
public class ZoneOverlay extends UuidEntity {
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "zone_type", nullable = false)
    private String zoneType;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "geom")
    private JsonNode geom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pnu_list", nullable = false)
    private JsonNode pnuList = Json.array();

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** 읽기 전용 연관 (document_id 로 저장한다). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", insertable = false, updatable = false)
    private SourceDocument document;

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getZoneType() {
        return zoneType;
    }

    public void setZoneType(String zoneType) {
        this.zoneType = zoneType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNode getGeom() {
        return geom;
    }

    public void setGeom(JsonNode geom) {
        this.geom = geom;
    }

    public JsonNode getPnuList() {
        return pnuList;
    }

    public void setPnuList(JsonNode pnuList) {
        this.pnuList = pnuList;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public SourceDocument getDocument() {
        return document;
    }

    /** 연관과 외래키(document_id)를 함께 설정한다. */
    public void setDocument(SourceDocument document) {
        this.document = document;
        this.documentId = document == null ? null : document.getId();
    }

}
