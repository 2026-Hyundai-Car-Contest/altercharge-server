package com.server.altercharger_server.history;

import com.server.altercharger_server.common.Geo;
import com.server.altercharger_server.common.error.ApiException;
import com.server.altercharger_server.history.HistoryDtos.*;
import com.server.altercharger_server.history.entity.ChargingEvent;
import com.server.altercharger_server.history.entity.VehicleSnapshot;
import com.server.altercharger_server.history.repository.ChargingEventRepository;
import com.server.altercharger_server.history.repository.VehicleRepository;
import com.server.altercharger_server.history.repository.VehicleSnapshotRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class HistoryService {
    private final VehicleRepository vehicles;
    private final ChargingEventRepository events;
    private final VehicleSnapshotRepository snapshots;
    private final StationMatcher matcher;
    private final HistoryPolicy policy;

    public HistoryService(VehicleRepository vehicles, ChargingEventRepository events, VehicleSnapshotRepository snapshots,
                          StationMatcher matcher, HistoryPolicy policy) {
        this.vehicles = vehicles;
        this.events = events;
        this.snapshots = snapshots;
        this.matcher = matcher;
        this.policy = policy;
    }

    @Transactional
    public Stored<EventAccepted> saveEvent(EventRequest request) {
        if (request.source() == Source.REAL) throw HistoryPolicy.invalidBody("현재 외부 충전 이벤트는 MOCK만 허용합니다. REAL은 검증 경로가 필요합니다.");
        if (request.eventType() == null || request.lat() == null || request.lng() == null
                || !Geo.validCoordinates(request.lat(), request.lng())) {
            throw HistoryPolicy.invalidBody("이벤트 종류와 유효한 위경도가 필요합니다.");
        }
        policy.validateNumbers(request.socPercent(), request.odometerKm(), false);
        OffsetDateTime occurredAt = policy.normalize(request.occurredAt());
        lockVehicle(request.vehicleId());
        var previous = events.findById(request.eventId());
        if (previous.isPresent()) {
            ChargingEvent saved = previous.get();
            if (!sameEvent(saved, request, occurredAt)) throw conflict("EVENT_ID_CONFLICT", "같은 eventId에 다른 이벤트 내용이 저장되어 있습니다.");
            return new Stored<>(accepted(saved), false);
        }
        policy.validateNewTimestamp(occurredAt);
        ChargingEvent event = ChargingEvent.builder().eventId(request.eventId()).vehicleId(request.vehicleId())
                .eventType(request.eventType()).source(Source.MOCK).verified(false).occurredAt(occurredAt)
                .lat(zero(request.lat())).lng(zero(request.lng())).socPercent(request.socPercent()).odometerKm(zero(request.odometerKm()))
                .matchedStationId(matcher.nearestWithin100Meters(request.lat(), request.lng()))
                .visitCountAdded(0).dailyDuplicate(false).build();
        events.saveAndFlush(event);
        return new Stored<>(accepted(event), true);
    }

    @Transactional
    public Stored<SnapshotSaved> saveSnapshot(SnapshotRequest request) {
        policy.validateNumbers(request.socPercent(), request.odometerKm(), true);
        if (request.drivableRangeKm() != null && request.drivableRangeKm() < 0) throw HistoryPolicy.invalidBody("주행 가능 거리는 0 이상 또는 null이어야 합니다.");
        OffsetDateTime recordedAt = policy.normalize(request.recordedAt());
        lockVehicle(request.vehicleId());
        var previous = snapshots.findByVehicleIdAndRecordedAt(request.vehicleId(), recordedAt);
        if (previous.isPresent()) {
            VehicleSnapshot saved = previous.get();
            if (saved.getSocPercent() != request.socPercent() || Double.compare(saved.getOdometerKm(), zero(request.odometerKm())) != 0
                    || !Objects.equals(saved.getDrivableRangeKm(), request.drivableRangeKm())) {
                throw conflict("SNAPSHOT_CONFLICT", "같은 차량과 측정 시각에 다른 값이 저장되어 있습니다.");
            }
            return new Stored<>(snapshotSaved(saved), false);
        }
        policy.validateNewTimestamp(recordedAt);
        VehicleSnapshot snapshot = VehicleSnapshot.builder().snapshotId(UUID.randomUUID().toString()).vehicleId(request.vehicleId())
                .recordedAt(recordedAt).socPercent(request.socPercent()).odometerKm(zero(request.odometerKm()))
                .drivableRangeKm(request.drivableRangeKm()).build();
        snapshots.saveAndFlush(snapshot);
        return new Stored<>(snapshotSaved(snapshot), true);
    }

    public SnapshotHistory snapshots(String vehicleId, LocalDate from, LocalDate to) {
        requireVehicle(vehicleId);
        LocalDate requestedFrom = from == null ? policy.cutoff().toLocalDate() : from;
        LocalDate requestedTo = to == null ? policy.today() : to;
        if (requestedFrom.isAfter(requestedTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "from 날짜는 to 날짜보다 늦을 수 없습니다.");
        }
        LocalDate start = requestedFrom.isBefore(policy.cutoff().toLocalDate()) ? policy.cutoff().toLocalDate() : requestedFrom;
        LocalDate end = requestedTo.isAfter(policy.today()) ? policy.today() : requestedTo;
        if (start.isAfter(end)) return new SnapshotHistory(vehicleId, List.of(), policy.retentionDays(), 0);
        List<VehicleSnapshot> values = snapshots.findByVehicleIdAndRecordedAtGreaterThanEqualAndRecordedAtLessThanOrderByRecordedAtAsc(
                vehicleId, start.atStartOfDay(HistoryPolicy.SEOUL).toOffsetDateTime(), end.plusDays(1).atStartOfDay(HistoryPolicy.SEOUL).toOffsetDateTime());
        List<SnapshotRecord> records = values.stream().map(value -> new SnapshotRecord(value.getSnapshotId(), value.getRecordedAt(),
                value.getSocPercent(), value.getOdometerKm(), value.getDrivableRangeKm())).toList();
        int completeDays = (int) values.stream().collect(Collectors.groupingBy(value -> value.getRecordedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalDate()))
                .values().stream().filter(this::completeDay).count();
        return new SnapshotHistory(vehicleId, records, policy.retentionDays(), completeDays);
    }

    public LatestCharge latestCharge(String vehicleId) {
        requireVehicle(vehicleId);
        var completion = events.findFirstByVehicleIdAndEventTypeOrderByOccurredAtDescEventIdDesc(vehicleId, EventType.COMPLETE);
        if (completion.isEmpty()) return new LatestCharge(vehicleId, null, null, null, null, null, false);
        ChargingEvent event = completion.get();
        var current = snapshots.findFirstByVehicleIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDesc(vehicleId, policy.cutoff());
        Double odometer = current.map(VehicleSnapshot::getOdometerKm).orElse(null);
        Double distance = null;
        if (event.getOdometerKm() != null && current.isPresent() && !current.get().getRecordedAt().isBefore(event.getOccurredAt())
                && odometer >= event.getOdometerKm()) {
            distance = odometer - event.getOdometerKm();
        }
        return new LatestCharge(vehicleId, event.getOccurredAt(), event.getOdometerKm(), odometer, distance,
                event.getSource(), event.isVerified() && event.getSource() == Source.REAL);
    }

    @Transactional
    public long purgeExpiredSnapshots() { return snapshots.deleteByRecordedAtLessThan(policy.cutoff()); }

    private void lockVehicle(String vehicleId) {
        vehicles.findLockedById(vehicleId).orElseThrow(this::vehicleNotFound);
    }

    private void requireVehicle(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "vehicleId가 필요합니다.");
        if (!vehicles.existsById(vehicleId)) throw vehicleNotFound();
    }

    private ApiException vehicleNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND", "해당 차량을 찾을 수 없습니다."); }
    private ApiException conflict(String error, String message) { return new ApiException(HttpStatus.CONFLICT, error, message); }
    private Double zero(Double value) {
        if (value == null) return null;
        return value == 0 ? 0.0 : value;
    }
    private SnapshotSaved snapshotSaved(VehicleSnapshot value) { return new SnapshotSaved(value.getSnapshotId(), true, value.getRecordedAt()); }
    private EventAccepted accepted(ChargingEvent value) { return new EventAccepted(value.getEventId(), true, value.getMatchedStationId(), value.getVisitCountAdded(), value.isDailyDuplicate()); }

    private boolean sameEvent(ChargingEvent saved, EventRequest request, OffsetDateTime occurredAt) {
        return saved.getVehicleId().equals(request.vehicleId()) && saved.getEventType() == request.eventType()
                && saved.getSource() == Source.MOCK && saved.getOccurredAt().toInstant().equals(occurredAt.toInstant())
                && Double.compare(saved.getLat(), zero(request.lat())) == 0 && Double.compare(saved.getLng(), zero(request.lng())) == 0
                && saved.getSocPercent() == request.socPercent() && Objects.equals(saved.getOdometerKm(), zero(request.odometerKm()));
    }

    private boolean completeDay(List<VehicleSnapshot> values) {
        if (values.size() < 2) return false;
        if (values.getFirst().getRecordedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalTime().isAfter(LocalTime.of(1, 0))
                || values.getLast().getRecordedAt().atZoneSameInstant(HistoryPolicy.SEOUL).toLocalTime().isBefore(LocalTime.of(23, 0))) return false;
        for (int i = 1; i < values.size(); i++) {
            if (Duration.between(values.get(i - 1).getRecordedAt(), values.get(i).getRecordedAt()).compareTo(Duration.ofHours(6)) > 0
                    || values.get(i).getOdometerKm() < values.get(i - 1).getOdometerKm()) return false;
        }
        return true;
    }
}
