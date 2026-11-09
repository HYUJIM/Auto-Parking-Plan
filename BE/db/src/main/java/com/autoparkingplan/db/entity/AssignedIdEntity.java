package com.autoparkingplan.db.entity;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/** 코드가 기본키를 정하는 엔티티 (관할 code, 필지 pnu, 원장 기각의 복합키). */
@MappedSuperclass
public abstract class AssignedIdEntity<K> implements Persistable<K> {
    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
