package com.server.altercharger_server.history;

import com.server.altercharger_server.common.error.ApiErrorAdvice.ApiError;
import com.server.altercharger_server.history.HistoryDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "차량·충전 기록", description = "시드 차량의 시연용 원본 기록. Mock ID는 인증이 아니며 실제 사용자 격리를 제공하지 않습니다.")
public class HistoryController {
    private static final String EVENT_OK = "{\"eventId\":\"evt-demo-001\",\"accepted\":true,\"matchedStationId\":null,\"visitCountAdded\":0,\"dailyDuplicate\":false}";
    private static final String SNAPSHOT_OK = "{\"snapshotId\":\"e2f685c7-0b42-4265-a15d-f74db9e90f8b\",\"saved\":true,\"recordedAt\":\"2026-11-10T16:30:00Z\"}";
    private static final String BAD_BODY = "{\"error\":\"INVALID_REQUEST_BODY\",\"message\":\"요청 본문의 필수 값, 자료형, 범위를 확인해 주세요.\"}";
    private static final String BAD_QUERY = "{\"error\":\"INVALID_REQUEST\",\"message\":\"요청 파라미터의 형식과 필수 값을 확인해 주세요.\"}";
    private static final String MISSING_VEHICLE = "{\"error\":\"VEHICLE_NOT_FOUND\",\"message\":\"해당 차량을 찾을 수 없습니다.\"}";
    private static final String DB_UNAVAILABLE = "{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.\"}";
    private static final String INTERNAL_ERROR = "{\"error\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버에서 요청을 처리하지 못했습니다.\"}";
    private final HistoryService history;

    public HistoryController(HistoryService history) { this.history = history; }

    @PostMapping(value = "/charging-events", consumes = "application/json")
    @Operation(summary = "05. 충전 이벤트 저장", description = """
            REQ_ST_008, REQ_VS_005, REQ_LG_002. 시연용 START/COMPLETE 원본을 PostgreSQL에 저장합니다.
            source 생략/null은 MOCK이며 REAL은 검증 경로가 없어 400입니다. MOCK는 실제 방문 집계에서 제외되어 visitCountAdded=0, dailyDuplicate=false입니다.
            수집 캐시가 있으면 100m 이내 최단거리→stationId 순으로 충전소를 연결하며, 캐시/후보가 없으면 matchedStationId=null입니다.
            eventId는 전역 멱등 키입니다. 같은 내용 재전송은 기존 결과로 200, 다른 내용은 409, 신규 저장은 201입니다.
            날짜는 UTC 마이크로초로 절삭 정규화한 뒤 비교합니다. 새 이벤트는 최근 56일 한국시간 날짜 및 현재+5분까지 허용(설정 가능).
            이미 저장된 동일 이벤트의 재전송에는 새 이벤트의 시간 제한을 다시 적용하지 않습니다. 완료 당시 odometerKm는 nullable입니다.
            """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "MOCK 충전 이벤트. 아래 값은 시연 예시입니다.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EventRequest.class), examples = @ExampleObject(name = "시연 시작", value = "{\"eventId\":\"evt-demo-001\",\"vehicleId\":\"mock-vehicle-001\",\"eventType\":\"START\",\"source\":\"MOCK\",\"occurredAt\":\"2026-11-11T01:00:00+09:00\",\"lat\":37.5501,\"lng\":127.1401,\"socPercent\":35,\"odometerKm\":20350.4}")))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새 시연 이벤트 저장", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EventAccepted.class), examples = @ExampleObject(value = EVENT_OK))),
            @ApiResponse(responseCode = "200", description = "동일 이벤트 재전송, 저장된 결과 반환", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EventAccepted.class), examples = @ExampleObject(value = EVENT_OK))),
            @ApiResponse(responseCode = "400", description = "본문 누락·잘못된 enum/좌표/SOC/거리/시각 또는 REAL 출처", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = BAD_BODY))),
            @ApiResponse(responseCode = "404", description = "시드 차량 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = MISSING_VEHICLE))),
            @ApiResponse(responseCode = "409", description = "같은 eventId에 다른 내용", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = "{\"error\":\"EVENT_ID_CONFLICT\",\"message\":\"같은 eventId에 다른 이벤트 내용이 저장되어 있습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "PostgreSQL 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = DB_UNAVAILABLE))),
            @ApiResponse(responseCode = "500", description = "예상하지 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = INTERNAL_ERROR)))
    })
    public ResponseEntity<EventAccepted> saveEvent(@Valid @RequestBody EventRequest request) {
        var result = history.saveEvent(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @PostMapping(value = "/vehicles/snapshots", consumes = "application/json")
    @Operation(summary = "06. 차량 스냅샷 저장", description = """
            REQ_JD_002, REQ_VS_001, REQ_VS_004. SOC와 누적 주행거리 원본을 PostgreSQL에 저장합니다. 주행 가능 거리는 nullable입니다.
            신규 201, 같은 vehicleId+recordedAt(UTC 마이크로초로 절삭)에 완전히 같은 값의 재전송은 같은 snapshotId로 200, 다른 값은 409입니다.
            최근 56일 한국시간 날짜 및 현재+5분까지 새 자료를 허용하며 보관 기간/미래 오차는 설정값입니다. offset이 다른 같은 순간은 동일 키입니다.
            서버는 다음 충전일·충전 권장을 계산하지 않습니다. Mock ID는 인증이 아니며 사용자 생성 API는 없습니다.
            """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "차량 원본 측정값. 시연용 예시", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SnapshotRequest.class), examples = @ExampleObject(value = "{\"vehicleId\":\"mock-vehicle-001\",\"recordedAt\":\"2026-11-11T01:30:00+09:00\",\"socPercent\":62,\"odometerKm\":20350.4,\"drivableRangeKm\":null}")))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새 스냅샷 저장", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SnapshotSaved.class), examples = @ExampleObject(value = SNAPSHOT_OK))),
            @ApiResponse(responseCode = "200", description = "정규화된 시각과 내용이 같은 재전송", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SnapshotSaved.class), examples = @ExampleObject(value = SNAPSHOT_OK))),
            @ApiResponse(responseCode = "400", description = "필수 값·시각·SOC·거리 검증 실패", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = BAD_BODY))),
            @ApiResponse(responseCode = "404", description = "차량 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = MISSING_VEHICLE))),
            @ApiResponse(responseCode = "409", description = "같은 차량·측정 시각에 다른 값", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = "{\"error\":\"SNAPSHOT_CONFLICT\",\"message\":\"같은 차량과 측정 시각에 다른 값이 저장되어 있습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "PostgreSQL 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = DB_UNAVAILABLE))),
            @ApiResponse(responseCode = "500", description = "예상하지 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = INTERNAL_ERROR)))
    })
    public ResponseEntity<SnapshotSaved> saveSnapshot(@Valid @RequestBody SnapshotRequest request) {
        var result = history.saveSnapshot(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping("/vehicles/snapshots")
    @Operation(summary = "07. 최근 차량 스냅샷 기록", description = """
            REQ_JD_002, REQ_LG_002, REQ_CM_006. 차량별 기록을 측정 시각 오름차순으로 반환합니다.
            from/to는 양끝 한국시간 날짜를 포함합니다. 생략 시 오늘 포함 최근 56일이며 요청 범위는 보관 시작일~오늘로 제한합니다. 역전은 400입니다.
            차량은 있지만 기록이 없거나 보관 범위와 겹치지 않으면 200+records:[]. 만료된 기록은 한국시간 매일 03:10에 안전하게 정리합니다.
            completeDayCount는 반환 날짜별로 2건 이상, 첫 측정 01:00 이하, 마지막 23:00 이상, 연속 간격 6시간 이하, 누적거리 감소 없음인 날짜만 집계합니다.
            미운행 0km와 누락일을 구분하는 서버 참고값입니다. 현재 앱은 원본 스냅샷의 날짜별 누적거리 차분에서 어제까지 최근 14일을 골라 판정하며 completeDayCount를 직접 사용하지 않습니다.
            시연 차량은 어제까지 최근 14일의 측정값을 매일 보충합니다. 같은 차량·시각의 기존 기록은 보존합니다.
            서버는 chargeRecommended나 화면 문구를 계산·반환하지 않습니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "차량별 기록. 빈 배열도 정상", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SnapshotHistory.class), examples = {
                    @ExampleObject(name = "기록 있음", value = "{\"vehicleId\":\"mock-vehicle-001\",\"records\":[{\"snapshotId\":\"e2f685c7-0b42-4265-a15d-f74db9e90f8b\",\"recordedAt\":\"2026-11-10T16:30:00Z\",\"socPercent\":62,\"odometerKm\":20350.4,\"drivableRangeKm\":null}],\"retentionDays\":56,\"completeDayCount\":0}"),
                    @ExampleObject(name = "기록 없음", value = "{\"vehicleId\":\"mock-vehicle-001\",\"records\":[],\"retentionDays\":56,\"completeDayCount\":0}") })),
            @ApiResponse(responseCode = "400", description = "필수 차량 ID 누락, 날짜 형식/범위 역전", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = BAD_QUERY))),
            @ApiResponse(responseCode = "404", description = "차량 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = MISSING_VEHICLE))),
            @ApiResponse(responseCode = "503", description = "PostgreSQL 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = DB_UNAVAILABLE))),
            @ApiResponse(responseCode = "500", description = "예상하지 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = INTERNAL_ERROR)))
    })
    public SnapshotHistory snapshots(
            @Parameter(description = "필수 시드 차량 ID", required = true, example = "mock-vehicle-001") @RequestParam @NotBlank String vehicleId,
            @Parameter(description = "시작일(포함), YYYY-MM-DD. 생략 시 한국시간 오늘-55일(기본 설정)", example = "2026-10-28", schema = @Schema(type = "string", format = "date")) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일(포함), YYYY-MM-DD. 생략 시 한국시간 오늘", example = "2026-11-11", schema = @Schema(type = "string", format = "date")) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return history.snapshots(vehicleId, from, to);
    }

    @GetMapping("/charging-events/latest")
    @Operation(summary = "08. 최근 충전 완료 이력", description = """
            REQ_VS_005, REQ_LG_002. 차량의 마지막 COMPLETE를 occurredAt 내림차순→eventId 내림차순으로 결정합니다.
            source=MOCK/trusted=false로 시연 자료임을 드러냅니다. 연관된 START가 없어도 시연 완료 기록은 보관하되 실측으로 검증하지 않습니다.
            시연 스냅샷의 SOC가 전날 밤보다 오른 날에는 당시 누적거리를 가진 MOCK COMPLETE를 생성합니다. 실제 방문 횟수에는 포함하지 않습니다.
            현재 누적거리는 보관 기간 내 최신 스냅샷을 사용합니다. 완료 또는 스냅샷이 없거나 완료 당시 거리가 없으면 계산 필드는 null입니다.
            스냅샷 측정 시각이 완료 시각 이상이고 누적거리 차분이 0 이상일 때만 distanceSinceChargeKm를 계산합니다. 음수를 절댓값으로 바꾸지 않습니다.
            COMPLETE가 없으면 차량 ID 이외의 nullable 항목은 null이고 trusted=false입니다. 차량 없음은 404입니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최근 완료와 주행거리 또는 nullable 결과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LatestCharge.class), examples = {
                    @ExampleObject(name = "시연 완료 있음", value = "{\"vehicleId\":\"mock-vehicle-001\",\"lastChargeCompletedAt\":\"2026-11-10T16:00:00Z\",\"odometerAtLastChargeKm\":20310.0,\"currentOdometerKm\":20350.4,\"distanceSinceChargeKm\":40.4,\"source\":\"MOCK\",\"trusted\":false}"),
                    @ExampleObject(name = "완료 없음", value = "{\"vehicleId\":\"mock-vehicle-001\",\"lastChargeCompletedAt\":null,\"odometerAtLastChargeKm\":null,\"currentOdometerKm\":null,\"distanceSinceChargeKm\":null,\"source\":null,\"trusted\":false}") })),
            @ApiResponse(responseCode = "400", description = "필수 차량 ID 누락·공백", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = BAD_QUERY))),
            @ApiResponse(responseCode = "404", description = "차량 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = MISSING_VEHICLE))),
            @ApiResponse(responseCode = "503", description = "PostgreSQL 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = DB_UNAVAILABLE))),
            @ApiResponse(responseCode = "500", description = "예상하지 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = INTERNAL_ERROR)))
    })
    public LatestCharge latestCharge(@Parameter(description = "필수 시드 차량 ID", required = true, example = "mock-vehicle-001") @RequestParam @NotBlank String vehicleId) {
        return history.latestCharge(vehicleId);
    }
}
