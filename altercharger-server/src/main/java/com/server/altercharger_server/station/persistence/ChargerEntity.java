package com.server.altercharger_server.station.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chargers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChargerEntity {
    @EmbeddedId
    private ChargerId id;

    @MapsId("stationId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private StationEntity station;

    @Column(name = "raw_connector_type", nullable = false, length = 100)
    private String rawConnectorType;

    @Column(name = "output_kw")
    private Double outputKw;

    @Column(name = "raw_status", nullable = false, length = 100)
    private String rawStatus;

    @Column(name = "status_updated_at")
    private OffsetDateTime statusUpdatedAt;

    @Column(name = "status_observed_at", nullable = false)
    private OffsetDateTime statusObservedAt;

    @Column(nullable = false)
    private boolean deleted;

    public ChargerEntity(StationEntity station, String chargerId) {
        this.id = new ChargerId(station.getStationId(), chargerId);
        this.station = station;
    }
}
