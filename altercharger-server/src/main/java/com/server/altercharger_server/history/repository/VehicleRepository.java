package com.server.altercharger_server.history.repository;

import com.server.altercharger_server.history.entity.Vehicle;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.vehicleId = :vehicleId")
    Optional<Vehicle> findLockedById(@Param("vehicleId") String vehicleId);
}
