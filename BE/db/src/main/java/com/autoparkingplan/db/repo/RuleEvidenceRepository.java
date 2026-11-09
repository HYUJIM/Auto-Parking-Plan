package com.autoparkingplan.db.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.RuleEvidence;

public interface RuleEvidenceRepository extends JpaRepository<RuleEvidence, UUID> {
}
