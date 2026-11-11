package com.server.altercharger_server.history.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "station_visits", uniqueConstraints = @UniqueConstraint(name = "uk_real_station_visit_day", columnNames = {"user_id", "station_id", "visit_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StationVisit {
    @Id @Column(name = "visit_id", length = 36)
    private String visitId;
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;
    @Column(name = "station_id", nullable = false, length = 100)
    private String stationId;
    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;
    @Column(nullable = false, length = 20)
    private String source;
    @Column(nullable = false)
    private boolean verified;
}
