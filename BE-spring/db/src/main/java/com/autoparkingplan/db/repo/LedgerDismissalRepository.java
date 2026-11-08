package com.autoparkingplan.db.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.autoparkingplan.db.entity.LedgerDismissal;

public interface LedgerDismissalRepository extends JpaRepository<LedgerDismissal, LedgerDismissal.Key> {
}
