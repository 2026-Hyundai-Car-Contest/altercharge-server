package com.server.altercharger_server.station;

import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationDecisionResponses.NavigationReason;
import com.server.altercharger_server.station.StationDecisionResponses.RecommendationReason;
import com.server.altercharger_server.station.StationStore.Charger;
import com.server.altercharger_server.station.StationStore.Station;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StationDecisionServiceTests {
    private final StationStore store = mock(StationStore.class);
    private final VisitService visits = mock(VisitService.class);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-11T10:00:00Z");
    private final StationQueryService query = new StationQueryService(store, visits);
    private final StationDecisionService service = new StationDecisionService(query, visits, Clock.fixed(now.toInstant(), ZoneOffset.UTC), 15);

    @BeforeEach
    void ready() {
        when(store.hasCollectedData()).thenReturn(true);
        when(visits.realVisitCounts("mock-user-001")).thenReturn(Map.of("visited", 2));
    }

    @Test
    void ranksVisitsThenDistanceButRequiresAnAvailableFastChargerWhenRecommended() {
        when(store.findAll()).thenReturn(List.of(station("closest", 37.55, "2", 100.0),
                station("visited", 37.551, "2", 7.0)));
        assertThat(service.recommend(37.55, 127.14, false).station().stationId()).isEqualTo("visited");
        assertThat(service.recommend(37.55, 127.14, true).station().stationId()).isEqualTo("closest");
        var busyFast = new Charger("02", "04", 250.0, "3", now, now, false);
        var onlySlowAvailable = new Station("visited", "예시", "서울", 37.55, 127.14,
                List.of(charger("2", 7.0, now), busyFast));
        when(store.findAll()).thenReturn(List.of(onlySlowAvailable));
        assertThat(service.recommend(37.55, 127.14, true).fallbackUsed()).isTrue();
    }

    @Test
    void distinguishesNearestFallbackAndNoStationsWithoutFabricatingUnknownVisits() {
        when(store.findAll()).thenReturn(List.of(station("busy", 37.55, "4", null)));
        var fallback = service.recommend(37.55, 127.14, false);
        assertThat(fallback.reasonCode()).isEqualTo(RecommendationReason.NEAREST_FALLBACK);
        assertThat(fallback.station().visitCount()).isNull();
        assertThat(fallback.station().availableCount()).isZero();
        when(store.findAll()).thenReturn(List.of(station("outside", 38, "2", 100.0)));
        var none = service.recommend(37.55, 127.14, false);
        assertThat(none.reasonCode()).isEqualTo(RecommendationReason.NO_STATIONS_IN_RADIUS);
        assertThat(none.station()).isNull();
        assertThat(none.fallbackUsed()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"4,OUT_OF_SERVICE", "5,MAINTENANCE", "1,COMM_ERROR", "9,STATUS_UNKNOWN", "0,STATUS_UNKNOWN", "3,NO_AVAILABLE_CHARGER", "6,NO_AVAILABLE_CHARGER"})
    void blocksNonAvailableStates(String raw, NavigationReason reason) {
        when(store.findById("A")).thenReturn(Optional.of(station("A", 37.55, raw, 100.0)));
        var result = service.navigationCheck("A");
        assertThat(result.navigationAllowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(reason);
    }

    @Test
    void staleAvailabilityNeverAllowsNavigationButFreshAvailableTakesPriority() {
        var stale = charger("2", 100.0, now.minusMinutes(16));
        when(store.findById("A")).thenReturn(Optional.of(new Station("A", "예시", "서울", 37.55, 127.14, List.of(stale))));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
        var fresh = new Charger("02", "09", 100.0, "2", now, now, false);
        when(store.findById("A")).thenReturn(Optional.of(new Station("A", "예시", "서울", 37.55, 127.14, List.of(stale, fresh))));
        assertThat(service.navigationCheck("A").navigationAllowed()).isTrue();
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.OK);
        assertThat(ChargerRules.connectorTypes("10")).containsExactly("DC_COMBO", "NACS");
    }

    private Station station(String id, double lat, String stat, Double output) {
        return new Station(id, "예시", "서울", lat, 127.14, List.of(charger(stat, output, now)));
    }

    private Charger charger(String stat, Double output, OffsetDateTime observed) {
        return new Charger("01", "04", output, stat, now, observed, false);
    }
}
