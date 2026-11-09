package com.autoparkingplan.db.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.ReviewRequest;

public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, UUID> {
}
