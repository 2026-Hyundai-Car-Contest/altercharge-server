package com.server.altercharger_server.station;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public final class StationDecisionResponses {
    private StationDecisionResponses() {}

    public enum RecommendationReason { VISITS_THEN_DISTANCE, NEAREST_FALLBACK, NO_STATIONS_IN_RADIUS }
    public enum SpeedCategory { SLOW, FAST, ULTRA_FAST }
    public enum NavigationReason { OK, STATUS_STALE, NO_AVAILABLE_CHARGER }

    @Schema(description = "3km 이내 추천 결과. 충전 권장 판정은 프론트 책임이며 실제 충전 가능 여부는 길 안내 API로 재확인합니다.")
    public record Recommendation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "선택한 충전소. 반경 내 0건이면 null", nullable = true) RecommendedStation station,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "VISITS_THEN_DISTANCE: 가용 후보 중 방문·거리·ID 순; NEAREST_FALLBACK: 가용 조건을 만족하지 못해 최단거리 대체; NO_STATIONS_IN_RADIUS: 반경 내 0건")
            RecommendationReason reasonCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "가용·속도 조건을 충족하지 못한 최단거리 대체 여부", example = "false") boolean fallbackUsed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "선택 충전소 유효 충전기의 가장 오래된 실제 관측 시각. 미관측 또는 결과 없음은 null", nullable = true, example = "2026-11-11T01:30:00+09:00")
            OffsetDateTime statusObservedAt) {}

    public record RecommendedStation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "환경공단 원본 statId", example = "ST-001") String stationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "원본 충전소명. 임의 번역하지 않음", example = "예시 충전소") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "요청 위치와의 Haversine 거리(m)", example = "150", minimum = "0") int distanceMeters,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "상태 2인 유효 충전기 수. 대체 후보는 0일 수 있음", example = "2", minimum = "0") int availableCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "시연 사용자에 대해 검증된 실제 방문 수. 원천 기록 없음은 null이며 순위 계산에서만 0으로 취급", nullable = true, example = "2") Integer visitCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "출력 기반 제안 구간: 50kW 미만 SLOW, 50 이상 200 미만 FAST, 200 이상 ULTRA_FAST. 출력 없음은 null", nullable = true) SpeedCategory speedCategory,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "유효 충전기 전체의 최대 출력(kW). 가용 충전기 출력과 다를 수 있음", nullable = true, example = "100") Double maxOutputKw) {}

    public record NavigationCheck(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "환경공단 원본 statId", example = "ST-001") String stationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "최근 실제 관측 또는 해당 관측과 연결된 유효한 지역 연속 동기화로 상태가 확인된 AVAILABLE 충전기가 하나 이상이면 true. 동기화는 실제 충전기와 직접 통신한 확인이 아님", example = "false") boolean navigationAllowed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"OK", "STATUS_STALE", "NO_AVAILABLE_CHARGER"}, description = "OK: 최신성 근거가 유효한 AVAILABLE이 하나 이상; STATUS_STALE: 유효한 AVAILABLE이 없고 최신성 근거가 부족한 충전기가 하나 이상; NO_AVAILABLE_CHARGER: 나머지 모든 경우. 유효 충전기 0개도 NO_AVAILABLE_CHARGER이며 원천 상태별 세부 차단 사유는 구분하지 않음") NavigationReason reasonCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "서버가 캐시의 유효기간과 상태를 확인한 시각. 원천 상태 변경 시각과 다름", example = "2026-11-11T01:31:00+09:00") OffsetDateTime checkedAt) {}
}
