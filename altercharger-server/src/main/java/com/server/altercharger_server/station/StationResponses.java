package com.server.altercharger_server.station;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class StationResponses {
    private StationResponses() {}

    @Schema(name = "StationPage")
    public record StationPage(
            List<StationSummary> items,
            int page,
            int size,
            boolean hasNext,
            @Schema(description = "서버가 DB를 조회해 응답을 만든 시각. 외부 상태 갱신 시각과 다릅니다.")
            OffsetDateTime fetchedAt) {}

    @Schema(name = "StationSummary")
    public record StationSummary(
            String stationId,
            String name,
            String address,
            double lat,
            double lng,
            int distanceMeters,
            @Schema(description = "환경공단 stat=2인 유효 충전기 수") int availableCount,
            int totalValidCount,
            List<String> chargerTypes,
            @Schema(nullable = true) Double maxOutputKw,
            @Schema(description = "방문 기록을 수집하지 않으므로 항상 null", nullable = true) Integer visitCount,
            @Schema(description = "유효 충전기들의 가장 오래된 상태 관측 시각. 하나라도 미관측이면 null", nullable = true)
            OffsetDateTime statusObservedAt) {}

    @Schema(name = "StationDetail")
    public record StationDetail(
            String stationId,
            String name,
            String address,
            double lat,
            double lng,
            int availableCount,
            int totalValidCount,
            List<ChargerDetail> chargers,
            @Schema(description = "유효 충전기들의 가장 오래된 상태 관측 시각. 하나라도 미관측이면 null", nullable = true)
            OffsetDateTime statusObservedAt) {}

    @Schema(name = "ChargerDetail")
    public record ChargerDetail(
            String chargerId,
            @Schema(description = "단일 규격 이름. 복합 규격은 모든 이름을 +로 연결하며 connectorTypes에도 전체 규격을 제공합니다.")
            String connectorType,
            List<String> connectorTypes,
            @Schema(description = "환경공단 원본 연결 규격 코드") String chgerType,
            @Schema(nullable = true) Double outputKw,
            @Schema(description = "UNKNOWN, COMMUNICATION_ERROR, AVAILABLE, CHARGING, STOPPED, MAINTENANCE, RESERVED, UNCONFIRMED")
            String status,
            @Schema(description = "환경공단 원본 상태 코드") String stat,
            @Schema(description = "환경공단 statUpdDt 상태 변경 시각", nullable = true) OffsetDateTime statusUpdatedAt,
            @Schema(description = "이 충전기의 상태를 실제 관측한 시각", nullable = true) OffsetDateTime statusObservedAt) {}
}
