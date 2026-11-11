package com.server.altercharger_server.station;

import com.server.altercharger_server.common.error.ApiException;
import com.server.altercharger_server.station.StationResponses.ChargerDetail;
import com.server.altercharger_server.station.StationResponses.StationDetail;
import com.server.altercharger_server.station.StationResponses.StationPage;
import com.server.altercharger_server.station.StationResponses.StationSummary;
import com.server.altercharger_server.station.StationStore.Charger;
import com.server.altercharger_server.station.StationStore.Station;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StationQueryService {
    private final StationStore store;

    public StationQueryService(StationStore store) {
        this.store = store;
    }

    public StationPage list(double lat, double lng, double radiusKm, String keyword, int page, int size) {
        validate(lat, lng, radiusKm, page, size);
        requireCollectedData();
        String search = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        List<Nearby> nearby = store.findAll().stream()
                .filter(this::validCoordinates)
                .filter(station -> search.isEmpty() || contains(station.name(), search) || contains(station.address(), search))
                .map(station -> new Nearby(station, distanceMeters(lat, lng, station.lat(), station.lng())))
                .filter(result -> result.distance() <= radiusKm * 1000)
                .sorted(Comparator.comparingDouble(Nearby::distance).thenComparing(result -> result.station().stationId()))
                .toList();

        long offset = (long) page * size;
        int from = (int) Math.min(offset, nearby.size());
        int to = (int) Math.min(offset + size, nearby.size());
        List<StationSummary> items = nearby.subList(from, to).stream().map(this::summary).toList();
        return new StationPage(items, page, size, offset + size < nearby.size(), OffsetDateTime.now());
    }

    public StationDetail detail(String stationId) {
        requireCollectedData();
        Station station = store.findById(stationId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND", "해당 충전소를 찾을 수 없습니다."));
        List<Charger> chargers = validChargers(station);
        return new StationDetail(station.stationId(), station.name(), station.address(), station.lat(), station.lng(),
                availableCount(chargers), chargers.size(), chargers.stream().map(this::chargerDetail).toList(), observedAt(chargers));
    }

    private void validate(double lat, double lng, double radiusKm, int page, int size) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90
                || !Double.isFinite(lng) || lng < -180 || lng > 180
                || !Double.isFinite(radiusKm) || radiusKm <= 0 || radiusKm > 3
                || page < 0 || size < 1 || size > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "좌표, 반경(0 초과 3km 이하), 페이지(0 이상), 크기(1~200)를 확인해 주세요.");
        }
    }

    private void requireCollectedData() {
        if (!store.hasCollectedData()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_DATA_UNAVAILABLE", "아직 수집된 충전소 데이터가 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private StationSummary summary(Nearby nearby) {
        Station station = nearby.station();
        List<Charger> chargers = validChargers(station);
        List<String> types = chargers.stream().flatMap(charger -> connectorTypes(charger.rawConnectorType()).stream())
                .distinct().sorted().toList();
        Double maxOutput = chargers.stream().map(Charger::outputKw).filter(this::validOutput)
                .max(Double::compare).orElse(null);
        return new StationSummary(station.stationId(), station.name(), station.address(), station.lat(), station.lng(),
                (int) Math.round(nearby.distance()), availableCount(chargers), chargers.size(), types, maxOutput, null, observedAt(chargers));
    }

    private List<Charger> validChargers(Station station) {
        return station.chargers().stream()
                .filter(charger -> !charger.deleted() && charger.chargerId() != null && !charger.chargerId().isBlank())
                .filter(charger -> charger.rawConnectorType() != null && !charger.rawConnectorType().isBlank()
                        && charger.rawStatus() != null && !charger.rawStatus().isBlank())
                .sorted(Comparator.comparing(Charger::chargerId)).toList();
    }

    private int availableCount(List<Charger> chargers) {
        return (int) chargers.stream().filter(charger -> "2".equals(charger.rawStatus())).count();
    }

    private OffsetDateTime observedAt(List<Charger> chargers) {
        // A delta update to one charger must not make the whole station appear freshly observed.
        if (chargers.stream().anyMatch(charger -> charger.statusObservedAt() == null)) {
            return null;
        }
        return chargers.stream().map(Charger::statusObservedAt).min(OffsetDateTime::compareTo).orElse(null);
    }

    private ChargerDetail chargerDetail(Charger charger) {
        List<String> types = connectorTypes(charger.rawConnectorType());
        return new ChargerDetail(charger.chargerId(), String.join("+", types), types, charger.rawConnectorType(),
                validOutput(charger.outputKw()) ? charger.outputKw() : null,
                status(charger.rawStatus()), charger.rawStatus(), charger.statusUpdatedAt(), charger.statusObservedAt());
    }

    private List<String> connectorTypes(String rawType) {
        return switch (rawType == null ? "" : rawType) {
            case "01" -> List.of("DC_CHADEMO");
            case "02" -> List.of("AC_SLOW");
            case "03" -> List.of("DC_CHADEMO", "AC_3PHASE");
            case "04" -> List.of("DC_COMBO");
            case "05" -> List.of("DC_CHADEMO", "DC_COMBO");
            case "06" -> List.of("DC_CHADEMO", "AC_3PHASE", "DC_COMBO");
            case "07" -> List.of("AC_3PHASE");
            default -> List.of("UNKNOWN");
        };
    }

    private String status(String rawStatus) {
        return switch (rawStatus == null ? "" : rawStatus) {
            case "1" -> "COMMUNICATION_ERROR";
            case "2" -> "AVAILABLE";
            case "3" -> "CHARGING";
            case "4" -> "STOPPED";
            case "5" -> "MAINTENANCE";
            case "6" -> "RESERVED";
            case "9" -> "UNCONFIRMED";
            default -> "UNKNOWN";
        };
    }

    private boolean validOutput(Double output) {
        return output != null && Double.isFinite(output) && output > 0;
    }

    private boolean validCoordinates(Station station) {
        return Double.isFinite(station.lat()) && station.lat() >= -90 && station.lat() <= 90
                && Double.isFinite(station.lng()) && station.lng() >= -180 && station.lng() <= 180;
    }

    private boolean contains(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search);
    }

    private double distanceMeters(double lat, double lng, double stationLat, double stationLng) {
        double latDelta = Math.toRadians(stationLat - lat);
        double lngDelta = Math.toRadians(stationLng - lng);
        double a = Math.pow(Math.sin(latDelta / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(stationLat)) * Math.pow(Math.sin(lngDelta / 2), 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(Math.max(0, Math.min(1, a))), Math.sqrt(Math.max(0, 1 - a)));
    }

    private record Nearby(Station station, double distance) {}
}
