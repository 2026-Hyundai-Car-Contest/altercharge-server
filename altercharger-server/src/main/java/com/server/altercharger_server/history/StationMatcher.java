package com.server.altercharger_server.history;

import com.server.altercharger_server.common.Geo;
import com.server.altercharger_server.station.StationStore;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class StationMatcher {
    private final StationStore stations;

    public StationMatcher(StationStore stations) {
        this.stations = stations;
    }

    public String nearestWithin100Meters(double lat, double lng) {
        if (!stations.hasCollectedData()) return null;
        return stations.findAll().stream()
                .filter(station -> Geo.validCoordinates(station.lat(), station.lng()))
                .map(station -> new Match(station.stationId(), Geo.distanceMeters(lat, lng, station.lat(), station.lng())))
                .filter(match -> match.distance() <= 100)
                .min(Comparator.comparingDouble(Match::distance).thenComparing(Match::stationId))
                .map(Match::stationId).orElse(null);
    }

    private record Match(String stationId, double distance) {}
}
