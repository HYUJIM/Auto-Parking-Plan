package com.autoparkingplan.db.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.Parcel;

public interface ParcelRepository extends JpaRepository<Parcel, String> {
}
