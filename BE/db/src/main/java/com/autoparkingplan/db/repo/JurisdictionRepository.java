package com.autoparkingplan.db.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.Jurisdiction;

public interface JurisdictionRepository extends JpaRepository<Jurisdiction, String> {
}
