package com.server.altercharger_server.station;

import com.server.altercharger_server.common.Geo;
import com.server.altercharger_server.common.error.ApiException;
import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationResponses.ChargerDetail;
import com.server.altercharger_server.station.StationResponses.StationDetail;
import com.server.altercharger_server.station.StationResponses.StationPage;
import com.server.altercharger_server.station.StationResponses.StationSummary;
import com.server.altercharger_server.station.StationStore.Station;
import com.server.altercharger_server.station.StationStore.StatusSync;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static com.server.altercharger_server.station.ChargerRules.*;

@Service
@RequiredArgsConstructor
public class StationQueryService {
    static final String DEMO_USER_ID = "mock-user-001";
    private final StationStore store;
    private final VisitService visits;

    public StationPage list(double lat, double lng, double radiusKm, String keyword, int page, int size) {
        List<NearbyStation> nearby = nearby(lat, lng, radiusKm, keyword);
        Map<String, Integer> visitCounts = visits.realVisitCounts(DEMO_USER_ID);
        long offset = (long) page * size;
        int from = (int) Math.min(offset, nearby.size());
        int to = (int) Math.min(offset + size, nearby.size());
        var items = nearby.subList(from, to).stream().map(result -> summary(result, visitCounts)).toList();
        return new StationPage(items, page, size, offset + size < nearby.size(), OffsetDateTime.now());
    }

    public StationDetail detail(String stationId) {
        Station station = requireStation(stationId);
        var chargers = validChargers(station);
        var details = chargers.stream().map(c -> {
            var types = connectorTypes(c.rawConnectorType());
            return new ChargerDetail(c.chargerId(), String.join("+", types), types, c.rawConnectorType(),
                    validOutput(c.outputKw()) ? c.outputKw() : null,
                    status(c.rawStatus()), c.rawStatus(), c.statusUpdatedAt(), c.statusObservedAt());
        }).toList();
        return new StationDetail(station.stationId(), station.name(), station.address(), station.lat(), station.lng(),
                availableCount(chargers), chargers.size(), details, observedAt(chargers));
    }

    Station requireStation(String id) {
        requireCollectedData();
        return store.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND", "해당 충전소를 찾을 수 없습니다."));
    }

    Optional<StatusSync> statusSyncForStation(String stationId) {
        return store.findStatusSyncForStation(stationId);
    }

    List<NearbyStation> nearby(double lat, double lng, double radiusKm, String keyword) {
        requireCollectedData();
        String search = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        return store.findNearby(lat, lng, radiusKm * 1000).stream()
                .filter(s -> Geo.validCoordinates(s.lat(), s.lng()))
                .filter(s -> search.isEmpty() || contains(s.name(), search) || contains(s.address(), search))
                .map(s -> new NearbyStation(s, Geo.distanceMeters(lat, lng, s.lat(), s.lng())))
                .filter(result -> result.distance() <= radiusKm * 1000)
                .sorted(Comparator.comparingDouble(NearbyStation::distance).thenComparing(r -> r.station().stationId()))
                .toList();
    }

    private StationSummary summary(NearbyStation nearby, Map<String, Integer> visits) {
        var station = nearby.station();
        var chargers = validChargers(station);
        var types = chargers.stream().flatMap(c -> connectorTypes(c.rawConnectorType()).stream()).distinct().sorted().toList();
        return new StationSummary(station.stationId(), station.name(), station.address(), station.lat(), station.lng(),
                (int) Math.round(nearby.distance()), availableCount(chargers), chargers.size(), types,
                maxOutput(chargers), visits.get(station.stationId()), observedAt(chargers));
    }

    private void requireCollectedData() {
        if (!store.hasCollectedData()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_DATA_UNAVAILABLE", "아직 수집된 충전소 데이터가 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private boolean contains(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search);
    }

    record NearbyStation(Station station, double distance) {}
}
