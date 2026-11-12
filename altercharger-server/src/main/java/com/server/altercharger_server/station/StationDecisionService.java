package com.server.altercharger_server.station;

import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationDecisionResponses.*;
import com.server.altercharger_server.station.StationQueryService.NearbyStation;
import com.server.altercharger_server.station.StationStore.Charger;
import com.server.altercharger_server.station.StationStore.StatusSync;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.server.altercharger_server.station.ChargerRules.*;

@Service
public class StationDecisionService {
    private final StationQueryService stations;
    private final VisitService visits;
    private final Clock clock;
    private final Duration statusMaxAge;

    public StationDecisionService(StationQueryService stations, VisitService visits, Clock clock,
                                  @Value("${app.stations.status-max-age-minutes}") int statusMaxAgeMinutes) {
        if (statusMaxAgeMinutes < 1 || statusMaxAgeMinutes > 1440) throw new IllegalArgumentException("Status validity must be 1..1440 minutes");
        this.stations = stations;
        this.visits = visits;
        this.clock = clock;
        this.statusMaxAge = Duration.ofMinutes(statusMaxAgeMinutes);
    }

    public Recommendation recommend(double lat, double lng, boolean chargeRecommended) {
        List<NearbyStation> nearby = stations.nearby(lat, lng, 3, null);
        if (nearby.isEmpty()) return new Recommendation(null, RecommendationReason.NO_STATIONS_IN_RADIUS, false, null);
        Map<String, Integer> counts = visits.realVisitCounts(StationQueryService.DEMO_USER_ID);
        var candidates = nearby.stream().filter(result -> validChargers(result.station()).stream()
                .anyMatch(c -> "2".equals(c.rawStatus()) && (!chargeRecommended || validOutput(c.outputKw()) && c.outputKw() >= 50)));
        var selected = candidates.min(Comparator.<NearbyStation>comparingInt(r -> counts.getOrDefault(r.station().stationId(), 0)).reversed()
                .thenComparingDouble(NearbyStation::distance).thenComparing(r -> r.station().stationId()));
        boolean fallback = selected.isEmpty();
        var result = selected.orElse(nearby.getFirst());
        var station = result.station();
        var chargers = validChargers(station);
        Double output = maxOutput(chargers);
        var summary = new RecommendedStation(station.stationId(), station.name(), (int) Math.round(result.distance()),
                availableCount(chargers), counts.get(station.stationId()), speed(output), output);
        return new Recommendation(summary, fallback ? RecommendationReason.NEAREST_FALLBACK : RecommendationReason.VISITS_THEN_DISTANCE,
                fallback, observedAt(chargers));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NavigationCheck navigationCheck(String stationId) {
        var chargers = validChargers(stations.requireStation(stationId));
        OffsetDateTime now = OffsetDateTime.now(clock);
        var sync = stations.statusSyncForStation(stationId)
                .filter(s -> s.baselineAt() != null && recent(s.lastStatusSyncAt(), now)
                        && !s.baselineAt().isAfter(s.lastStatusSyncAt()))
                .orElse(null);
        boolean available = chargers.stream().anyMatch(c -> "2".equals(c.rawStatus()) && fresh(c, now, sync));
        var reason = available ? NavigationReason.OK : blockedReason(chargers, now, sync);
        return new NavigationCheck(stationId, available, reason,
                chargers.stream().map(c -> status(c.rawStatus())).distinct().toList(), now);
    }

    private NavigationReason blockedReason(List<Charger> chargers, OffsetDateTime now, StatusSync sync) {
        if (chargers.stream().anyMatch(c -> !fresh(c, now, sync))) return NavigationReason.STATUS_STALE;
        return NavigationReason.NO_AVAILABLE_CHARGER;
    }

    private boolean fresh(Charger charger, OffsetDateTime now, StatusSync sync) {
        var at = charger.statusObservedAt();
        if (at == null || at.isAfter(now)) return false;
        if (recent(at, now)) return true;
        // A complete, continuous change feed can confirm an unchanged baseline state;
        // it never manufactures a per-charger observation or an AVAILABLE status.
        return sync != null && !at.isBefore(sync.baselineAt()) && !at.isAfter(sync.lastStatusSyncAt());
    }

    private boolean recent(OffsetDateTime at, OffsetDateTime now) {
        return at != null && !at.isAfter(now) && !at.isBefore(now.minus(statusMaxAge));
    }

    private SpeedCategory speed(Double output) {
        return output == null ? null : output >= 200 ? SpeedCategory.ULTRA_FAST : output >= 50 ? SpeedCategory.FAST : SpeedCategory.SLOW;
    }
}
