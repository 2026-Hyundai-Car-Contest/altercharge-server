package com.server.altercharger_server.history.entity;

import com.server.altercharger_server.history.HistoryDtos.EventType;
import com.server.altercharger_server.history.HistoryDtos.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "charging_events")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChargingEvent implements Persistable<String> {
    @Id @Column(name = "event_id", length = 100)
    private String eventId;
    @Column(name = "vehicle_id", nullable = false, length = 100)
    private String vehicleId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 20)
    private EventType eventType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Source source;
    @Column(nullable = false)
    private boolean verified;
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;
    @Column(nullable = false)
    private double lat;
    @Column(nullable = false)
    private double lng;
    @Column(name = "soc_percent", nullable = false)
    private int socPercent;
    @Column(name = "odometer_km")
    private Double odometerKm;
    @Column(name = "matched_station_id", length = 100)
    private String matchedStationId;
    @Column(name = "visit_count_added", nullable = false)
    private int visitCountAdded;
    @Column(name = "daily_duplicate", nullable = false)
    private boolean dailyDuplicate;

    @Transient @Builder.Default
    private boolean newEntity = true;

    @Override public String getId() { return eventId; }
    @Override public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist
    void markPersisted() { newEntity = false; }
}
