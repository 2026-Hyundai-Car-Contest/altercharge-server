package com.server.altercharger_server.station;

import com.server.altercharger_server.history.VisitService;
import com.server.altercharger_server.station.StationDecisionResponses.NavigationReason;
import com.server.altercharger_server.station.StationDecisionResponses.RecommendationReason;
import com.server.altercharger_server.station.StationStore.Charger;
import com.server.altercharger_server.station.StationStore.Station;
import com.server.altercharger_server.station.StationStore.StatusSync;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        when(store.findNearby(37.55, 127.14, 3000)).thenReturn(List.of(station("closest", 37.55, "2", 100.0),
                station("visited", 37.551, "2", 7.0)));
        assertThat(service.recommend(37.55, 127.14, false).station().stationId()).isEqualTo("visited");
        assertThat(service.recommend(37.55, 127.14, true).station().stationId()).isEqualTo("closest");
        var busyFast = new Charger("02", "04", 250.0, "3", now, now, false);
        var onlySlowAvailable = new Station("visited", "예시", "서울", 37.55, 127.14,
                List.of(charger("2", 7.0, now), busyFast));
        when(store.findNearby(37.55, 127.14, 3000)).thenReturn(List.of(onlySlowAvailable));
        assertThat(service.recommend(37.55, 127.14, true).fallbackUsed()).isTrue();
    }

    @Test
    void distinguishesNearestFallbackAndNoStationsWithoutFabricatingUnknownVisits() {
        when(store.findNearby(37.55, 127.14, 3000)).thenReturn(List.of(station("busy", 37.55, "4", null)));
        var fallback = service.recommend(37.55, 127.14, false);
        assertThat(fallback.reasonCode()).isEqualTo(RecommendationReason.NEAREST_FALLBACK);
        assertThat(fallback.station().visitCount()).isNull();
        assertThat(fallback.station().availableCount()).isZero();
        when(store.findNearby(37.55, 127.14, 3000)).thenReturn(List.of(station("outside", 38, "2", 100.0)));
        var none = service.recommend(37.55, 127.14, false);
        assertThat(none.reasonCode()).isEqualTo(RecommendationReason.NO_STATIONS_IN_RADIUS);
        assertThat(none.station()).isNull();
        assertThat(none.fallbackUsed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "5", "1", "9", "0", "3", "6", "unexpected"})
    void freshNonAvailableStatesShareOneReason(String raw) {
        when(store.findById("A")).thenReturn(Optional.of(station("A", 37.55, raw, 100.0)));
        var result = service.navigationCheck("A");
        assertThat(result.navigationAllowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(NavigationReason.NO_AVAILABLE_CHARGER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "5", "1", "9", "0", "3", "6", "unexpected"})
    void staleNonAvailableStateTakesPriorityOverFreshNonAvailableState(String raw) {
        var freshBusy = new Charger("02", "04", 100.0, "3", now, now, false);
        navigationStation(List.of(charger(raw, 100.0, now.minusMinutes(16)), freshBusy));

        var result = service.navigationCheck("A");

        assertThat(result.navigationAllowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
    }

    @Test
    void noValidChargersReturnsNoAvailableChargerWithoutInventingAStatus() {
        navigationStation(List.of(new Charger("01", "", 100.0, "2", now, now, false)));

        var result = service.navigationCheck("A");

        assertThat(result.navigationAllowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(NavigationReason.NO_AVAILABLE_CHARGER);
        assertThat(result.latestStatuses()).isEmpty();
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

    @Test
    void continuousRegionalSyncConfirmsUnchangedAvailabilityWithoutChangingActualObservation() {
        var observed = now.minusMinutes(16);
        var unchanged = charger("2", 100.0, observed);
        navigationStation(List.of(unchanged));
        when(store.findStatusSyncForStation("A"))
                .thenReturn(Optional.of(new StatusSync(observed, now.minusMinutes(1))));

        var result = service.navigationCheck("A");

        assertThat(result.navigationAllowed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(NavigationReason.OK);
        assertThat(result.latestStatuses()).containsExactly("AVAILABLE");
        assertThat(result.checkedAt()).isEqualTo(now);
        assertThat(unchanged.statusObservedAt()).isEqualTo(observed);
        verify(store).findStatusSyncForStation("A");
        verify(store, never()).saveStatuses(anyList(), any());
    }

    @Test
    void regionalSyncPreservesChargingAndUnknownStatesInsteadOfInventingAvailability() {
        var observed = now.minusMinutes(20);
        when(store.findStatusSyncForStation("A"))
                .thenReturn(Optional.of(new StatusSync(observed, now.minusMinutes(1))));

        navigationStation(List.of(charger("3", 100.0, observed)));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.NO_AVAILABLE_CHARGER);
        navigationStation(List.of(charger("0", 100.0, observed)));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.NO_AVAILABLE_CHARGER);
    }

    @Test
    void recentRegionalSyncCannotConfirmAnObservationBeforeItsBaselineOrMissingObservation() {
        var baseline = now.minusMinutes(20);
        when(store.findStatusSyncForStation("A"))
                .thenReturn(Optional.of(new StatusSync(baseline, now.minusMinutes(1))));

        navigationStation(List.of(charger("2", 100.0, baseline.minusNanos(1))));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
        navigationStation(List.of(charger("2", 100.0, null)));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
        navigationStation(List.of(charger("2", 100.0, now.plusNanos(1))));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
    }

    @Test
    void invalidMissingOrExpiredRegionalEvidenceCannotExtendAnOldObservation() {
        var observed = now.minusMinutes(20);
        navigationStation(List.of(charger("2", 100.0, observed)));
        var invalid = List.of(new StatusSync(null, now), new StatusSync(observed, null),
                new StatusSync(now, now.minusMinutes(1)), new StatusSync(observed, now.plusNanos(1)),
                new StatusSync(observed, now.minusMinutes(15).minusNanos(1)));
        for (var sync : invalid) {
            when(store.findStatusSyncForStation("A")).thenReturn(Optional.of(sync));
            assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
        }
        // A failed/partial collection cannot create a newer trusted sync; a gap invalidates it.
        when(store.findStatusSyncForStation("A")).thenReturn(Optional.empty());
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
    }

    @Test
    void includesExactObservationAndSyncAgeBoundariesButExcludesOlderAndFutureEvidence() {
        navigationStation(List.of(charger("2", 100.0, now.minusMinutes(15))));
        assertThat(service.navigationCheck("A").navigationAllowed()).isTrue();
        navigationStation(List.of(charger("2", 100.0, now.minusMinutes(15).minusNanos(1))));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);

        var baseline = now.minusMinutes(20);
        navigationStation(List.of(charger("2", 100.0, baseline)));
        when(store.findStatusSyncForStation("A"))
                .thenReturn(Optional.of(new StatusSync(baseline, now.minusMinutes(15))));
        assertThat(service.navigationCheck("A").navigationAllowed()).isTrue();
        when(store.findStatusSyncForStation("A"))
                .thenReturn(Optional.of(new StatusSync(baseline, now.minusMinutes(15).minusNanos(1))));
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
    }

    @Test
    void regionallyConfirmedAvailableTakesPriorityOverAnotherUnconnectedStaleCharger() {
        var baseline = now.minusMinutes(20);
        var disconnected = new Charger("02", "04", 100.0, "4", now, baseline.minusMinutes(1), false);
        navigationStation(List.of(charger("2", 100.0, baseline), disconnected));
        when(store.findStatusSyncForStation("A"))
                .thenReturn(Optional.of(new StatusSync(baseline, now.minusMinutes(1))));

        assertThat(service.navigationCheck("A").navigationAllowed()).isTrue();
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.OK);
    }

    @Test
    void recommendationRetainsStoredAvailabilityWhileNavigationRequiresFreshnessEvidence() {
        var cached = new Station("A", "예시", "서울", 37.55, 127.14,
                List.of(charger("2", 100.0, now.minusMinutes(20))));
        when(store.findNearby(37.55, 127.14, 3000)).thenReturn(List.of(cached));
        when(store.findById("A")).thenReturn(Optional.of(cached));

        assertThat(service.recommend(37.55, 127.14, false).station().availableCount()).isEqualTo(1);
        assertThat(service.navigationCheck("A").reasonCode()).isEqualTo(NavigationReason.STATUS_STALE);
    }

    private void navigationStation(List<Charger> chargers) {
        when(store.findById("A")).thenReturn(Optional.of(new Station("A", "예시", "서울", 37.55, 127.14, chargers)));
    }

    private Station station(String id, double lat, String stat, Double output) {
        return new Station(id, "예시", "서울", lat, 127.14, List.of(charger(stat, output, now)));
    }

    private Charger charger(String stat, Double output, OffsetDateTime observed) {
        return new Charger("01", "04", output, stat, now, observed, false);
    }
}
