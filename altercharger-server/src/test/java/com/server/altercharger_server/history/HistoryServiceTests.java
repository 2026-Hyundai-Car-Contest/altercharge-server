package com.server.altercharger_server.history;

import com.server.altercharger_server.common.error.ApiException;
import com.server.altercharger_server.history.HistoryDtos.*;
import com.server.altercharger_server.history.entity.ChargingEvent;
import com.server.altercharger_server.history.entity.Vehicle;
import com.server.altercharger_server.history.entity.VehicleSnapshot;
import com.server.altercharger_server.history.repository.ChargingEventRepository;
import com.server.altercharger_server.history.repository.VehicleRepository;
import com.server.altercharger_server.history.repository.VehicleSnapshotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoryServiceTests {
    private static final String VEHICLE = "mock-vehicle-001";
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final ChargingEventRepository events = mock(ChargingEventRepository.class);
    private final VehicleSnapshotRepository snapshots = mock(VehicleSnapshotRepository.class);
    private final StationMatcher matcher = mock(StationMatcher.class);
    private final Map<String, ChargingEvent> eventRows = new HashMap<>();
    private final ArrayList<VehicleSnapshot> snapshotRows = new ArrayList<>();
    private final HistoryPolicy policy = new HistoryPolicy(Clock.fixed(Instant.parse("2026-11-11T14:00:00Z"), ZoneOffset.UTC), 56, 5);
    private HistoryService history;

    @BeforeEach
    void setup() {
        history = new HistoryService(vehicles, events, snapshots, matcher, policy);
        when(vehicles.findLockedById(VEHICLE)).thenReturn(Optional.of(new Vehicle(VEHICLE, "mock-user-001")));
        when(vehicles.existsById(VEHICLE)).thenReturn(true);
        when(events.findById(anyString())).thenAnswer(call -> Optional.ofNullable(eventRows.get(call.getArgument(0))));
        when(events.saveAndFlush(any())).thenAnswer(call -> {
            ChargingEvent value = call.getArgument(0);
            eventRows.put(value.getEventId(), value);
            return value;
        });
        when(snapshots.findByVehicleIdAndRecordedAt(anyString(), any())).thenAnswer(call -> snapshotRows.stream()
                .filter(value -> value.getVehicleId().equals(call.getArgument(0)) && value.getRecordedAt().toInstant().equals(((OffsetDateTime) call.getArgument(1)).toInstant())).findFirst());
        when(snapshots.saveAndFlush(any())).thenAnswer(call -> {
            VehicleSnapshot value = call.getArgument(0);
            snapshotRows.add(value);
            return value;
        });
        when(snapshots.findByVehicleIdAndRecordedAtGreaterThanEqualAndRecordedAtLessThanOrderByRecordedAtAsc(anyString(), any(), any()))
                .thenAnswer(call -> snapshotRows.stream().filter(value -> value.getVehicleId().equals(call.getArgument(0))
                                && !value.getRecordedAt().isBefore(call.getArgument(1)) && value.getRecordedAt().isBefore(call.getArgument(2)))
                        .sorted(Comparator.comparing(VehicleSnapshot::getRecordedAt)).toList());
        when(events.findFirstByVehicleIdAndEventTypeOrderByOccurredAtDescEventIdDesc(anyString(), any()))
                .thenAnswer(call -> eventRows.values().stream().filter(value -> value.getVehicleId().equals(call.getArgument(0)) && value.getEventType() == call.getArgument(1))
                        .max(Comparator.comparing(ChargingEvent::getOccurredAt).thenComparing(ChargingEvent::getEventId)));
        when(snapshots.findFirstByVehicleIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDesc(anyString(), any()))
                .thenAnswer(call -> snapshotRows.stream().filter(value -> value.getVehicleId().equals(call.getArgument(0)) && !value.getRecordedAt().isBefore(call.getArgument(1)))
                        .max(Comparator.comparing(VehicleSnapshot::getRecordedAt)));
    }

    @Test
    void mockEventRetriesAreIdempotentWithoutFabricatingRealVisits() {
        when(matcher.nearestWithin100Meters(37.55, 127.14)).thenReturn("TEST-STATION");
        var request = event("evt-one", EventType.START, null, "2026-11-11T01:00:00.123456789+09:00", null);
        var first = history.saveEvent(request);
        assertTrue(first.created());
        assertEquals(0, first.body().visitCountAdded());
        assertFalse(first.body().dailyDuplicate());
        assertEquals("TEST-STATION", first.body().matchedStationId());
        assertEquals(Source.MOCK, eventRows.get("evt-one").getSource());
        assertFalse(eventRows.get("evt-one").isVerified());
        assertNull(eventRows.get("evt-one").getOdometerKm());
        assertEquals(123456000, eventRows.get("evt-one").getOccurredAt().getNano());

        var sameInstant = event("evt-one", EventType.START, Source.MOCK, "2026-11-10T16:00:00.123456001Z", null);
        assertEquals(first.body(), history.saveEvent(sameInstant).body());
        assertFalse(history.saveEvent(sameInstant).created());
        assertEquals(1, eventRows.size());
        assertError(409, "EVENT_ID_CONFLICT", () -> history.saveEvent(event("evt-one", EventType.COMPLETE, Source.MOCK, "2026-11-10T16:00:00.123456Z", null)));
        assertError(400, "INVALID_REQUEST_BODY", () -> history.saveEvent(event("evt-real", EventType.START, Source.REAL, "2026-11-11T01:00:00+09:00", 1.0)));
    }

    @Test
    void snapshotDuplicateConflictsAndTimeWindowUseNormalizedInstants() {
        var first = history.saveSnapshot(snapshot("2026-11-11T01:30:00.987654321+09:00", 100, null));
        assertTrue(first.created());
        var replay = history.saveSnapshot(snapshot("2026-11-10T16:30:00.987654999Z", 100, null));
        assertFalse(replay.created());
        assertEquals(first.body().snapshotId(), replay.body().snapshotId());
        assertError(409, "SNAPSHOT_CONFLICT", () -> history.saveSnapshot(snapshot("2026-11-10T16:30:00.987654Z", 101, null)));
        assertError(400, "INVALID_REQUEST_BODY", () -> history.saveSnapshot(snapshot("2026-11-11T23:05:01+09:00", 100, null)));
        assertError(400, "INVALID_REQUEST_BODY", () -> history.saveSnapshot(snapshot("2026-09-16T23:59:59+09:00", 100, null)));
        assertError(404, "VEHICLE_NOT_FOUND", () -> history.saveSnapshot(new SnapshotRequest("unknown", OffsetDateTime.parse("2026-11-11T01:30:00+09:00"), 62, 100.0, null)));
    }

    @Test
    void retainedHistoryCountsOnlyCoveredNondecreasingDaysAndPurgesExpiredMeasurements() {
        for (int hour : new int[]{0, 6, 12, 18, 23}) history.saveSnapshot(snapshot(String.format("2026-11-09T%02d:00:00+09:00", hour), hour == 12 ? 90 : 100, null));
        for (int hour : new int[]{0, 6, 12, 18, 23}) history.saveSnapshot(snapshot(String.format("2026-11-10T%02d:00:00+09:00", hour), 100, null));
        history.saveSnapshot(snapshot("2026-11-11T01:00:00+09:00", 100, null));
        history.saveSnapshot(snapshot("2026-11-11T23:00:00+09:00", 100, null));
        var result = history.snapshots(VEHICLE, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
        assertEquals(12, result.records().size());
        assertEquals(1, result.completeDayCount());
        assertEquals(56, result.retentionDays());
        assertTrue(result.records().getFirst().recordedAt().isBefore(result.records().getLast().recordedAt()));
        assertError(400, "INVALID_REQUEST", () -> history.snapshots(VEHICLE, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 11)));
        assertTrue(history.snapshots(VEHICLE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)).records().isEmpty());
        assertError(404, "VEHICLE_NOT_FOUND", () -> history.latestCharge("unknown"));
        history.purgeExpiredSnapshots();
        verify(snapshots).deleteByRecordedAtLessThan(policy.cutoff());
        assertEquals(OffsetDateTime.parse("2026-09-17T00:00:00+09:00").toInstant(), policy.cutoff().toInstant());
    }

    @Test
    void latestCompletionDistanceRequiresKnownOdometersAndLaterSnapshotAndRemainsMock() {
        assertNull(history.latestCharge(VEHICLE).lastChargeCompletedAt());
        history.saveEvent(event("complete", EventType.COMPLETE, Source.MOCK, "2026-11-11T01:00:00+09:00", 100.0));
        history.saveSnapshot(snapshot("2026-11-11T00:00:00+09:00", 120, null));
        assertNull(history.latestCharge(VEHICLE).distanceSinceChargeKm());
        history.saveSnapshot(snapshot("2026-11-11T02:00:00+09:00", 90, null));
        assertNull(history.latestCharge(VEHICLE).distanceSinceChargeKm());
        history.saveSnapshot(snapshot("2026-11-11T03:00:00+09:00", 140, null));
        var latest = history.latestCharge(VEHICLE);
        assertEquals(40.0, latest.distanceSinceChargeKm());
        assertEquals(Source.MOCK, latest.source());
        assertFalse(latest.trusted());
        history.saveEvent(event("complete-newer", EventType.COMPLETE, Source.MOCK, "2026-11-11T04:00:00+09:00", null));
        assertNull(history.latestCharge(VEHICLE).odometerAtLastChargeKm());
        assertNull(history.latestCharge(VEHICLE).distanceSinceChargeKm());
    }

    private EventRequest event(String id, EventType type, Source source, String time, Double odometer) {
        return new EventRequest(id, VEHICLE, type, source, OffsetDateTime.parse(time), 37.55, 127.14, 35, odometer);
    }
    private SnapshotRequest snapshot(String time, double odometer, Integer range) {
        return new SnapshotRequest(VEHICLE, OffsetDateTime.parse(time), 62, odometer, range);
    }
    private void assertError(int status, String error, Runnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(status, exception.status().value());
        assertEquals(error, exception.error());
    }
}
