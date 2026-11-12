package com.server.altercharger_server.history.repository;

import com.server.altercharger_server.history.entity.VehicleSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleSnapshotRepository extends JpaRepository<VehicleSnapshot, String> {
    Optional<VehicleSnapshot> findByVehicleIdAndRecordedAt(String vehicleId, OffsetDateTime recordedAt);
    List<VehicleSnapshot> findByVehicleIdAndRecordedAtGreaterThanEqualAndRecordedAtLessThanOrderByRecordedAtAsc(
            String vehicleId, OffsetDateTime from, OffsetDateTime until);
    Optional<VehicleSnapshot> findFirstByVehicleIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDesc(String vehicleId, OffsetDateTime from);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from VehicleSnapshot snapshot where snapshot.recordedAt < :cutoff")
    int deleteByRecordedAtLessThan(@Param("cutoff") OffsetDateTime cutoff);
}
