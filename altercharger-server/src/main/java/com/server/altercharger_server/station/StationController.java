package com.server.altercharger_server.station;

import com.server.altercharger_server.common.error.ApiErrorAdvice.ApiError;
import com.server.altercharger_server.station.StationResponses.StationDetail;
import com.server.altercharger_server.station.StationResponses.StationPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stations")
@Tag(name = "충전소", description = "설정된 수집 지역의 PostgreSQL 캐시를 조회합니다. GET 요청은 외부 API를 호출하지 않습니다.")
@ApiResponses({
        @ApiResponse(responseCode = "503", description = "초기 수집 데이터 없음", content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content(schema = @Schema(implementation = ApiError.class)))
})
public class StationController {
    private final StationQueryService service;

    public StationController(StationQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "주변 충전소 목록·검색", description = "요청 반경 이내를 먼저 필터링한 뒤 거리·충전소 ID 순으로 정렬하고 페이지를 나눕니다.")
    @ApiResponse(responseCode = "400", description = "필수 좌표 누락 또는 좌표·반경·페이지 범위 오류", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public StationPage list(
            @Parameter(description = "유한한 위도", schema = @Schema(minimum = "-90", maximum = "90")) @RequestParam double lat,
            @Parameter(description = "유한한 경도", schema = @Schema(minimum = "-180", maximum = "180")) @RequestParam double lng,
            @Parameter(description = "0 초과 3 이하인 유한한 반경(km)", schema = @Schema(minimum = "0", exclusiveMinimum = true, maximum = "3", defaultValue = "3")) @RequestParam(defaultValue = "3") double radiusKm,
            @Parameter(description = "충전소명·주소 부분 검색") @RequestParam(required = false) String keyword,
            @Parameter(schema = @Schema(minimum = "0", defaultValue = "0")) @RequestParam(defaultValue = "0") int page,
            @Parameter(schema = @Schema(minimum = "1", maximum = "200", defaultValue = "20")) @RequestParam(defaultValue = "20") int size) {
        return service.list(lat, lng, radiusKm, keyword, page, size);
    }

    @GetMapping("/{stationId}")
    @Operation(summary = "충전소 상세·충전기 상태", description = "삭제되거나 식별자가 유효하지 않은 충전기는 제외합니다. 알 수 없는 규격·상태는 원본 코드와 함께 반환합니다.")
    @ApiResponse(responseCode = "404", description = "수집된 자료에 충전소 없음", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public StationDetail detail(@Parameter(description = "환경공단 원본 statId") @PathVariable String stationId) {
        return service.detail(stationId);
    }
}
