package com.autoparkingplan.db.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.databind.JsonNode;

/** 테이블 provision (파이썬 app.db.models.Provision). */
@Entity
@Table(name = "provision")
public class Provision extends UuidEntity {
    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "heading")
    private String heading;

    @Column(name = "text", nullable = false)
    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "table_json")
    private JsonNode tableJson;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 읽기 전용 연관 (version_id 로 저장한다). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", insertable = false, updatable = false)
    private DocumentVersion version;

    public UUID getVersionId() {
        return versionId;
    }

    public void setVersionId(UUID versionId) {
        this.versionId = versionId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public JsonNode getTableJson() {
        return tableJson;
    }

    public void setTableJson(JsonNode tableJson) {
        this.tableJson = tableJson;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public DocumentVersion getVersion() {
        return version;
    }

    /** 연관과 외래키(version_id)를 함께 설정한다. */
    public void setVersion(DocumentVersion version) {
        this.version = version;
        this.versionId = version == null ? null : version.getId();
    }

}
