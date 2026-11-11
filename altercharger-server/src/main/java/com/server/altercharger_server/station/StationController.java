package com.server.altercharger_server.station;

import com.server.altercharger_server.common.error.ApiErrorAdvice.ApiError;
import com.server.altercharger_server.common.validation.FiniteNumber;
import com.server.altercharger_server.station.StationResponses.StationDetail;
import com.server.altercharger_server.station.StationResponses.StationPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stations")
@Tag(name = "충전소", description = "설정된 수집 지역의 PostgreSQL 캐시를 조회합니다. GET 요청은 외부 API를 호출하지 않습니다.")
public class StationController {
    private final StationQueryService service;

    public StationController(StationQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "01. 주변 충전소 목록·검색", description = "REQ_ST_001, REQ_SL_001/002/004/005, REQ_VS_006, REQ_CM_006. 설정 지역의 환경공단 XML 수집 캐시를 서버 JSON으로 조회합니다. 반경 필터 → 거리·statId 정렬 → 페이지 순으로 처리합니다. 가용 수는 stat=2만 포함합니다. 원본 한국어 이름·주소를 유지하며, 방문 수는 mock-user-001의 검증된 REAL 기록만 반영하고 미확인은 null입니다. MOCK 이벤트는 집계하지 않습니다. 요금은 제외합니다. 오래된 캐시도 최종 관측 시각과 반환하며 fetchedAt은 조회 시각입니다. 예시는 모두 가상 데이터입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. 반경 내 0건도 정상 빈 items", content = @Content(mediaType = "application/json", schema = @Schema(implementation = StationPage.class), examples = {@ExampleObject(name = "목록", value = StationApiExamples.PAGE), @ExampleObject(name = "반경 내 없음", value = StationApiExamples.EMPTY_PAGE)})),
            @ApiResponse(responseCode = "400", description = "필수 좌표 누락·유한수 범위·반경·페이지 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.BAD_REQUEST))),
            @ApiResponse(responseCode = "503", description = "초기 캐시 없음 또는 DB 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = {@ExampleObject(name = "초기 미수집", value = StationApiExamples.UNAVAILABLE), @ExampleObject(name = "DB 연결 불가", value = StationApiExamples.DATABASE_UNAVAILABLE)})),
            @ApiResponse(responseCode = "500", description = "예기치 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.INTERNAL_ERROR)))
    })
    public StationPage list(
            @Parameter(description = "현재 위도(도). 유한수", required = true, example = "37.55", schema = @Schema(minimum = "-90", maximum = "90")) @RequestParam @FiniteNumber(min = -90, max = 90) double lat,
            @Parameter(description = "현재 경도(도). 유한수", required = true, example = "127.14", schema = @Schema(minimum = "-180", maximum = "180")) @RequestParam @FiniteNumber(min = -180, max = 180) double lng,
            @Parameter(description = "반경(km), 0 초과 3 이하. 생략 시 3", example = "3", schema = @Schema(minimum = "0", exclusiveMinimum = true, maximum = "3", defaultValue = "3")) @RequestParam(defaultValue = "3") @FiniteNumber(min = 0, max = 3, minInclusive = false) double radiusKm,
            @Parameter(description = "충전소명·주소 부분 검색. 생략·빈 값이면 검색 제한 없음", example = "강동") @RequestParam(required = false) String keyword,
            @Parameter(description = "0부터 시작하는 페이지 번호", example = "0", schema = @Schema(minimum = "0", defaultValue = "0")) @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "내부 DB 결과의 페이지 크기. 외부 API 호출량과 무관", example = "20", schema = @Schema(minimum = "1", maximum = "200", defaultValue = "20")) @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return service.list(lat, lng, radiusKm, keyword, page, size);
    }

    @GetMapping("/{stationId}")
    @Operation(summary = "02. 충전소 상세·충전기 상태", description = "REQ_ST_007, REQ_SL_001. 환경공단 XML의 충전기별 원본 코드·변경 시각과 서버 관측 시각을 JSON으로 제공합니다. 삭제·무효 항목은 제외하며 stat=2만 가용 수로 집계합니다. 복합 규격은 connectorTypes에 모두 보존합니다. 출력 미제공은 null이며 충전 요금은 1차 범위에서 제외합니다. 원본 한국어를 유지하고 GET에서 외부 호출을 하지 않습니다. 캐시가 오래된 경우 길 안내 확인 API에서 안내 허용 여부를 재검사해야 합니다. 예시 ID는 가상입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "충전소 및 유효 충전기 조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = StationDetail.class), examples = @ExampleObject(value = StationApiExamples.DETAIL))),
            @ApiResponse(responseCode = "404", description = "수집 완료 후 존재하지 않는 충전소", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.NOT_FOUND))),
            @ApiResponse(responseCode = "503", description = "초기 캐시 없음 또는 DB 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = {@ExampleObject(name = "초기 미수집", value = StationApiExamples.UNAVAILABLE), @ExampleObject(name = "DB 연결 불가", value = StationApiExamples.DATABASE_UNAVAILABLE)})),
            @ApiResponse(responseCode = "500", description = "예기치 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.INTERNAL_ERROR)))
    })
    public StationDetail detail(@Parameter(description = "환경공단 원본 statId. 문자열과 앞자리 0을 보존", required = true, example = "ST-001") @PathVariable String stationId) {
        return service.detail(stationId);
    }
}
