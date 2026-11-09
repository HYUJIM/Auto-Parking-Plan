package com.autoparkingplan.db.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.DocumentVersion;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {
}
