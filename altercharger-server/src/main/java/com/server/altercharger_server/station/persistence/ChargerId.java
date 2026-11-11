package com.server.altercharger_server.station.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChargerId implements Serializable {
    @Column(name = "station_id", length = 100)
    private String stationId;

    @Column(name = "charger_id", length = 100)
    private String chargerId;
}
