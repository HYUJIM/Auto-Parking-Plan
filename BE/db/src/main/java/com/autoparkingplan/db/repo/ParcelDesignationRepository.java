package com.autoparkingplan.db.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.ParcelDesignation;

public interface ParcelDesignationRepository extends JpaRepository<ParcelDesignation, UUID> {
}
