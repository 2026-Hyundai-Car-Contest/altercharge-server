package com.server.altercharger_server.history.repository;

import com.server.altercharger_server.history.entity.VehicleSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleSnapshotRepository extends JpaRepository<VehicleSnapshot, String> {
    Optional<VehicleSnapshot> findByVehicleIdAndRecordedAt(String vehicleId, OffsetDateTime recordedAt);
    List<VehicleSnapshot> findByVehicleIdAndRecordedAtGreaterThanEqualAndRecordedAtLessThanOrderByRecordedAtAsc(
            String vehicleId, OffsetDateTime from, OffsetDateTime until);
    Optional<VehicleSnapshot> findFirstByVehicleIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDesc(String vehicleId, OffsetDateTime from);
    long deleteByRecordedAtLessThan(OffsetDateTime cutoff);
}
