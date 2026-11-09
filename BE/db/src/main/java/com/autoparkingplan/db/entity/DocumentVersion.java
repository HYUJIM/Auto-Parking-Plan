package com.autoparkingplan.db.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 테이블 document_version (파이썬 app.db.models.DocumentVersion). */
@Entity
@Table(name = "document_version")
public class DocumentVersion extends UuidEntity {
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "promulgation_number")
    private String promulgationNumber;

    @Column(name = "promulgation_date")
    private LocalDate promulgationDate;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "external_version_id")
    private String externalVersionId;

    @Column(name = "raw_object_key", nullable = false)
    private String rawObjectKey;

    @Column(name = "raw_format", nullable = false, length = 8)
    private String rawFormat;

    @Column(name = "raw_sha256", nullable = false, length = 64)
    private String rawSha256;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "v_source_access", nullable = false, length = 32)
    private String vSourceAccess = "not_checked";

    @Column(name = "v_version_header", nullable = false, length = 32)
    private String vVersionHeader = "not_checked";

    @Column(name = "v_text_match", nullable = false, length = 32)
    private String vTextMatch = "not_checked";

    @Column(name = "v_current_version", nullable = false, length = 32)
    private String vCurrentVersion = "not_checked";

    @Column(name = "current_checked_on")
    private LocalDate currentCheckedOn;

    @Column(name = "note")
    private String note;

    @Column(name = "is_example", nullable = false)
    private Boolean isExample = false;

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

    public String getPromulgationNumber() {
        return promulgationNumber;
    }

    public void setPromulgationNumber(String promulgationNumber) {
        this.promulgationNumber = promulgationNumber;
    }

    public LocalDate getPromulgationDate() {
        return promulgationDate;
    }

    public void setPromulgationDate(LocalDate promulgationDate) {
        this.promulgationDate = promulgationDate;
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

    public String getExternalVersionId() {
        return externalVersionId;
    }

    public void setExternalVersionId(String externalVersionId) {
        this.externalVersionId = externalVersionId;
    }

    public String getRawObjectKey() {
        return rawObjectKey;
    }

    public void setRawObjectKey(String rawObjectKey) {
        this.rawObjectKey = rawObjectKey;
    }

    public String getRawFormat() {
        return rawFormat;
    }

    public void setRawFormat(String rawFormat) {
        this.rawFormat = rawFormat;
    }

    public String getRawSha256() {
        return rawSha256;
    }

    public void setRawSha256(String rawSha256) {
        this.rawSha256 = rawSha256;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getVSourceAccess() {
        return vSourceAccess;
    }

    public void setVSourceAccess(String vSourceAccess) {
        this.vSourceAccess = vSourceAccess;
    }

    public String getVVersionHeader() {
        return vVersionHeader;
    }

    public void setVVersionHeader(String vVersionHeader) {
        this.vVersionHeader = vVersionHeader;
    }

    public String getVTextMatch() {
        return vTextMatch;
    }

    public void setVTextMatch(String vTextMatch) {
        this.vTextMatch = vTextMatch;
    }

    public String getVCurrentVersion() {
        return vCurrentVersion;
    }

    public void setVCurrentVersion(String vCurrentVersion) {
        this.vCurrentVersion = vCurrentVersion;
    }

    public LocalDate getCurrentCheckedOn() {
        return currentCheckedOn;
    }

    public void setCurrentCheckedOn(LocalDate currentCheckedOn) {
        this.currentCheckedOn = currentCheckedOn;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Boolean getIsExample() {
        return isExample;
    }

    public void setIsExample(Boolean isExample) {
        this.isExample = isExample;
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
