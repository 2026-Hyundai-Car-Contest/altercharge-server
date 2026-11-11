package com.server.altercharger_server.history.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "vehicle_snapshots", uniqueConstraints = @UniqueConstraint(name = "uk_vehicle_snapshot_instant", columnNames = {"vehicle_id", "recorded_at"}))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VehicleSnapshot {
    @Id @Column(name = "snapshot_id", length = 36)
    private String snapshotId;
    @Column(name = "vehicle_id", nullable = false, length = 100)
    private String vehicleId;
    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;
    @Column(name = "soc_percent", nullable = false)
    private int socPercent;
    @Column(name = "odometer_km", nullable = false)
    private double odometerKm;
    @Column(name = "drivable_range_km")
    private Integer drivableRangeKm;
}
