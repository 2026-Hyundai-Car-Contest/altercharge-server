package com.server.altercharger_server.history;

import com.server.altercharger_server.common.validation.FiniteNumber;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class HistoryDtos {
    private HistoryDtos() {}

    @Schema(description = "START: 충전 시작, COMPLETE: 충전 완료. END는 허용하지 않습니다.", allowableValues = {"START", "COMPLETE"})
    public enum EventType { START, COMPLETE }

    @Schema(description = "MOCK: 시연 입력. REAL: 검증된 실제 입력을 위한 예약값이며 현재 외부 POST에서는 400으로 거부합니다.", allowableValues = {"MOCK", "REAL"})
    public enum Source { MOCK, REAL }

    @Schema(name = "ChargingEventRequest", description = "시연용 충전 원본 이벤트. Mock ID는 인증이 아니며 실제 방문 횟수에는 반영하지 않습니다.")
    public record EventRequest(
            @NotBlank @Size(max = 100) @Schema(description = "클라이언트가 생성한 전역 멱등 키, 1~100자", example = "evt-demo-001", requiredMode = Schema.RequiredMode.REQUIRED) String eventId,
            @NotBlank @Size(max = 100) @Schema(description = "시드 차량 식별자", example = "mock-vehicle-001", requiredMode = Schema.RequiredMode.REQUIRED) String vehicleId,
            @NotNull @Schema(description = "충전 시작 또는 완료", example = "START", requiredMode = Schema.RequiredMode.REQUIRED) EventType eventType,
            @Schema(description = "생략/null이면 MOCK. REAL은 현재 거부합니다.", defaultValue = "MOCK", example = "MOCK", nullable = true) Source source,
            @NotNull @Schema(description = "ISO 8601 오프셋 시각. UTC 마이크로초로 절삭 저장하며 최근 56일의 한국시간 날짜와 현재+5분까지 허용(설정 가능).", example = "2026-11-11T01:00:00+09:00", requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime occurredAt,
            @NotNull @FiniteNumber(min = -90, max = 90) @Schema(description = "유한한 위도, 도", minimum = "-90", maximum = "90", example = "37.5501", requiredMode = Schema.RequiredMode.REQUIRED) Double lat,
            @NotNull @FiniteNumber(min = -180, max = 180) @Schema(description = "유한한 경도, 도", minimum = "-180", maximum = "180", example = "127.1401", requiredMode = Schema.RequiredMode.REQUIRED) Double lng,
            @NotNull @Min(0) @Max(100) @Schema(description = "배터리 SOC, %", minimum = "0", maximum = "100", example = "35", requiredMode = Schema.RequiredMode.REQUIRED) Integer socPercent,
            @FiniteNumber(min = 0) @Schema(description = "이벤트 당시 유한한 누적 주행거리, km. 출처에 없으면 null", minimum = "0", example = "20350.4", nullable = true) Double odometerKm) {}

    @Schema(name = "ChargingEventAccepted")
    public record EventAccepted(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "저장된 멱등 키", example = "evt-demo-001") String eventId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "저장 또는 동일 요청 재전송을 수용했는지 여부", example = "true") boolean accepted,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "관측된 충전소 중 100m 이내 최단거리, 동률이면 ID 순. 캐시가 없거나 후보가 없으면 null이며 실제 비방문을 입증하지 않습니다.", example = "ST-001", nullable = true) String matchedStationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "실제 검증된 방문 증가량. 외부 MOCK 입력은 항상 0", example = "0") int visitCountAdded,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "실제 방문 집계의 같은 한국시간 날짜 중복 여부. 외부 MOCK 입력은 집계하지 않아 항상 false", example = "false") boolean dailyDuplicate) {}

    @Schema(name = "VehicleSnapshotRequest", description = "차량의 원본 측정값. 서버는 충전 권장 여부나 다음 충전일을 계산하지 않습니다.")
    public record SnapshotRequest(
            @NotBlank @Size(max = 100) @Schema(description = "시드 차량 식별자", example = "mock-vehicle-001", requiredMode = Schema.RequiredMode.REQUIRED) String vehicleId,
            @NotNull @Schema(description = "오프셋이 포함된 측정 시각. UTC 마이크로초로 절삭 저장. 최근 56일 한국시간 날짜 및 현재+5분까지 허용(설정 가능)", example = "2026-11-11T01:30:00+09:00", requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime recordedAt,
            @NotNull @Min(0) @Max(100) @Schema(description = "배터리 SOC, %", minimum = "0", maximum = "100", example = "62", requiredMode = Schema.RequiredMode.REQUIRED) Integer socPercent,
            @NotNull @FiniteNumber(min = 0) @Schema(description = "유한한 누적 주행거리, km", minimum = "0", example = "20350.4", requiredMode = Schema.RequiredMode.REQUIRED) Double odometerKm,
            @PositiveOrZero @Schema(description = "주행 가능 거리, km. SDK 출처가 확정되지 않은 선택 값", minimum = "0", example = "235", nullable = true) Integer drivableRangeKm) {}

    @Schema(name = "VehicleSnapshotSaved")
    public record SnapshotSaved(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "서버가 생성한 스냅샷 ID. 동일 재전송에는 기존 ID를 반환", example = "e2f685c7-0b42-4265-a15d-f74db9e90f8b") String snapshotId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "신규 저장 또는 동일 재전송 성공", example = "true") boolean saved,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UTC 마이크로초로 정규화한 측정 시각", example = "2026-11-10T16:30:00Z") OffsetDateTime recordedAt) {}

    @Schema(name = "VehicleSnapshotRecord")
    public record SnapshotRecord(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "저장된 스냅샷 식별자", example = "e2f685c7-0b42-4265-a15d-f74db9e90f8b") String snapshotId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "측정 시각, ISO 8601 오프셋", example = "2026-11-10T16:30:00Z") OffsetDateTime recordedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "배터리 SOC, %", minimum = "0", maximum = "100", example = "62") int socPercent,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "누적 주행거리, km", minimum = "0", example = "20350.4") double odometerKm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "주행 가능 거리, km", minimum = "0", nullable = true, example = "235") Integer drivableRangeKm) {}

    @Schema(name = "VehicleSnapshotHistory")
    public record SnapshotHistory(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "조회 차량 ID", example = "mock-vehicle-001") String vehicleId,
            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "측정 시각 오름차순. 자료가 없으면 빈 배열"), schema = @Schema(implementation = SnapshotRecord.class)) List<SnapshotRecord> records,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "현재 설정한 보관 기간, 한국시간 날짜 수", example = "56") int retentionDays,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "반환 범위 내 보수적인 유효 기록일 수: 한국시간 하루에 2건 이상, 첫 기록 01:00 이하, 마지막 23:00 이상, 연속 기록 간격 6시간 이하, 누적거리 감소 없음. 0km 운행은 가능하며 누락일은 제외합니다.", example = "0") int completeDayCount) {}

    @Schema(name = "LatestChargeHistory", description = "최신 COMPLETE 이벤트와 차량 스냅샷으로 계산합니다. MOCK 출처와 신뢰 여부를 명시하며 실측 기록을 주장하지 않습니다.")
    public record LatestCharge(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "차량 ID", example = "mock-vehicle-001") String vehicleId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "최신 COMPLETE 시각. 완료 이벤트 없으면 null", example = "2026-11-10T16:00:00Z", nullable = true) OffsetDateTime lastChargeCompletedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "완료 이벤트 당시 누적거리, km. 미제공이면 null", example = "20310.0", nullable = true) Double odometerAtLastChargeKm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "보관 범위 내 최신 스냅샷 누적거리, km. 완료 또는 스냅샷이 없으면 null", example = "20350.4", nullable = true) Double currentOdometerKm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "완료 이후 스냅샷이 있고 양쪽 거리가 알려져 있으며 차분이 음수가 아닐 때만 계산, km. 그 외 null", example = "40.4", nullable = true) Double distanceSinceChargeKm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "완료 이벤트 출처. 현재 외부 경로는 MOCK만 저장하며 이벤트 없으면 null", example = "MOCK", nullable = true) Source source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "검증된 실제 충전 완료인지 여부. 현재 시연 경로는 항상 false", example = "false") boolean trusted) {}

    public record Stored<T>(T body, boolean created) {}
}
