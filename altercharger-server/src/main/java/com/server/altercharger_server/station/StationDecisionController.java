package com.server.altercharger_server.station;

import com.server.altercharger_server.common.error.ApiErrorAdvice.ApiError;
import com.server.altercharger_server.common.validation.FiniteNumber;
import com.server.altercharger_server.station.StationDecisionResponses.NavigationCheck;
import com.server.altercharger_server.station.StationDecisionResponses.Recommendation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stations")
@RequiredArgsConstructor
@Tag(name = "충전소")
public class StationDecisionController {
    private final StationDecisionService service;

    @GetMapping("/recommendation")
    @Operation(summary = "03. 추천 충전소 1곳", description = "REQ_ST_005/006/007. 반경 3km 이내의 유효 가용 충전기(stat=2)가 있는 충전소를 검증된 REAL 방문 수 내림차순 → 거리 → statId 순으로 선택합니다. 알 수 없는 방문 수는 반환 시 null, 순위 계산에서만 0입니다. MOCK 방문은 집계하지 않습니다. chargeRecommended=true이면 출력 50kW 이상인 가용 충전기가 있어야 합니다. 출력 기반 속도 SLOW/FAST/ULTRA_FAST는 제안 구간입니다. 조건을 만족하지 못하면 가장 가까운 충전소를 fallbackUsed=true로 반환하며 충전 가능을 보장하지 않습니다. 반경 내 0건은 station=null인 정상 200이며 조회 불능 503과 다릅니다. ST_005: 14일 미만 기록은 프론트가 SOC 30% 이하를 기준으로 전달하고, 14일 이상도 프론트 판정을 그대로 사용합니다. 서버는 SOC·다음 충전일을 계산하지 않습니다. 캐시 기반이며 길 안내는 04로 재검증합니다. 요금 제외, 모든 예시는 가상입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "가용 추천·거리 대체·반경 내 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Recommendation.class), examples = {@ExampleObject(name = "가용 추천", value = StationApiExamples.RECOMMEND), @ExampleObject(name = "거리 대체", value = StationApiExamples.FALLBACK), @ExampleObject(name = "반경 내 없음", value = StationApiExamples.NO_RECOMMENDATION)})),
            @ApiResponse(responseCode = "400", description = "필수 위경도·프론트 판정 누락 또는 형식·범위 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.BAD_REQUEST))),
            @ApiResponse(responseCode = "503", description = "캐시 미수집 또는 DB 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = {@ExampleObject(name = "초기 미수집", value = StationApiExamples.UNAVAILABLE), @ExampleObject(name = "DB 연결 불가", value = StationApiExamples.DATABASE_UNAVAILABLE)})),
            @ApiResponse(responseCode = "500", description = "예기치 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.INTERNAL_ERROR)))
    })
    public Recommendation recommend(
            @Parameter(description = "현재 위도, 유한수", required = true, example = "37.55", schema = @Schema(type = "number", format = "double", minimum = "-90", maximum = "90")) @RequestParam @FiniteNumber(min = -90, max = 90) double lat,
            @Parameter(description = "현재 경도, 유한수", required = true, example = "127.14", schema = @Schema(type = "number", format = "double", minimum = "-180", maximum = "180")) @RequestParam @FiniteNumber(min = -180, max = 180) double lng,
            @Parameter(description = "프론트의 충전 권장 판정. true이면 50kW 이상 가용 충전기만 후보", required = true, example = "true") @RequestParam boolean chargeRecommended) {
        return service.recommend(lat, lng, chargeRecommended);
    }

    @GetMapping("/{stationId}/navigation-check")
    @Operation(summary = "04. 길 안내 직전 상태 확인", description = "REQ_EX_004, REQ_BR_004/005. DB 캐시만 확인하며 Pleos NaviHelper 목적지 변경·경로 생성은 프론트 책임입니다. STATION_STATUS_MAX_AGE_MINUTES(기본15분) 내에 관측된 AVAILABLE이 하나 이상이면 다른 충전기의 오래된 상태보다 우선하여 OK입니다. 그렇지 않으면 관측 누락·유효기간 초과·미래 관측은 STATUS_STALE로 차단합니다. 나머지 대표 차단 사유 우선순위는 OUT_OF_SERVICE(운영 중지) > MAINTENANCE(점검) > COMM_ERROR(통신 이상) > STATUS_UNKNOWN(0/9/기타/유효 충전기 없음) > NO_AVAILABLE_CHARGER(충전 중·예약)입니다. 정상 차단도200이며 초기 캐시 미수집·DB 조회 불가는503입니다. checkedAt은 확인 시각이며 원천 statUpdDt와 다릅니다. 실제 빈자리를 보장하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "안내 허용 또는 정상적인 상태·오래된 캐시 차단", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NavigationCheck.class), examples = {@ExampleObject(name = "안내 허용", value = StationApiExamples.NAV_OK), @ExampleObject(name = "관측 만료", value = StationApiExamples.NAV_STALE)})),
            @ApiResponse(responseCode = "404", description = "수집 완료 후 없는 충전소", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.NOT_FOUND))),
            @ApiResponse(responseCode = "503", description = "캐시 미수집 또는 DB 연결 불가", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = {@ExampleObject(name = "초기 미수집", value = StationApiExamples.UNAVAILABLE), @ExampleObject(name = "DB 연결 불가", value = StationApiExamples.DATABASE_UNAVAILABLE)})),
            @ApiResponse(responseCode = "500", description = "예기치 못한 서버 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = StationApiExamples.INTERNAL_ERROR)))
    })
    public NavigationCheck navigationCheck(
            @Parameter(description = "환경공단 원본 statId", required = true, example = "ST-001") @PathVariable String stationId) {
        return service.navigationCheck(stationId);
    }
}
