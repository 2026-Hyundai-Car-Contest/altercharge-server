package com.server.altercharger_server.station.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StationEntity {
    @Id
    @Column(name = "station_id", length = 100)
    private String stationId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String address;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(nullable = false, length = 2)
    private String zcode;

    @Column(name = "metadata_observed_at", nullable = false)
    private OffsetDateTime metadataObservedAt;

    @OneToMany(mappedBy = "station")
    @Setter(AccessLevel.NONE)
    private List<ChargerEntity> chargers = new ArrayList<>();

    public StationEntity(String stationId) {
        this.stationId = stationId;
    }
}
