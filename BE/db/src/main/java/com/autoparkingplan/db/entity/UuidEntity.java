package com.autoparkingplan.db.entity;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * UUID 기본키 엔티티의 공통 부분. id 는 만들 때 바로 정한다 (파이썬 default=uuid4 와 같이, 저장 전에도 자식이 참조할 수 있게).
 * 시간순 UUIDv7 이라 id 순서가 만든 순서와 같다.
 * {@link Persistable} 로 새 엔티티임을 알려서 저장소 save() 가 merge(추가 SELECT) 대신 persist 하게 한다.
 */
@MappedSuperclass
public abstract class UuidEntity implements Persistable<UUID> {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id = com.autoparkingplan.common.Uuids.v7(); // 시간순 → 자식 목록을 id 순(=저장 순서)으로 읽는다

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || org.hibernate.Hibernate.getClass(this) != org.hibernate.Hibernate.getClass(o)) {
            return false;
        }
        return Objects.equals(id, ((UuidEntity) o).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
