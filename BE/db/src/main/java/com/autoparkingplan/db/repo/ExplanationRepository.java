package com.autoparkingplan.db.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.Explanation;

public interface ExplanationRepository extends JpaRepository<Explanation, UUID> {
}
