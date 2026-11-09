package com.autoparkingplan.db.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.ParcelBuilding;

public interface ParcelBuildingRepository extends JpaRepository<ParcelBuilding, UUID> {
}
