package com.server.altercharger_server.station.persistence;

import java.util.List;
import java.util.Optional;

public interface StationQueries {
    List<StationEntity> findActiveWithChargers();

    List<StationEntity> findActiveWithinBounds(double south, double north, double west, double east,
                                              boolean wrapsLongitude);

    Optional<StationEntity> findWithChargers(String stationId);
}
