package com.server.altercharger_server.station;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class StationResponses {
    private StationResponses() {}

    @Schema(name = "StationPage", description = "API 01. PostgreSQL 캐시를 반경 필터링·거리 정렬한 조회 페이지")
    public record StationPage(
            @ArraySchema(arraySchema = @Schema(description = "현재 페이지 충전소. 조회 0건이면 []", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(implementation = StationSummary.class))
            List<StationSummary> items,
            @Schema(description = "요청한 페이지 번호. 0부터 시작", minimum = "0", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
            int page,
            @Schema(description = "요청한 페이지 크기. 내부 DB 응답 단위", minimum = "1", maximum = "200", example = "20", requiredMode = Schema.RequiredMode.REQUIRED)
            int size,
            @Schema(description = "현재 필터·반경의 다음 페이지 존재 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasNext,
            @Schema(description = "서버가 DB 조회 응답을 만든 시각. 상태 수집·변경 시각과 다름", type = "string", format = "date-time", example = "2026-11-11T01:31:00+09:00", requiredMode = Schema.RequiredMode.REQUIRED)
            OffsetDateTime fetchedAt) {}

    @Schema(name = "StationSummary", description = "API 01 충전소 요약. 예시 식별자는 가상값")
    public record StationSummary(
            @Schema(description = "환경공단 원본 statId 문자열", example = "ST-001", requiredMode = Schema.RequiredMode.REQUIRED)
            String stationId,
            @Schema(description = "환경공단 원본 한국어 충전소명. 자동 번역 없음", example = "예시 충전소", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "환경공단 원본 한국어 주소", example = "서울특별시 예시 주소", requiredMode = Schema.RequiredMode.REQUIRED)
            String address,
            @Schema(description = "위도(도)", minimum = "-90", maximum = "90", example = "37.551", requiredMode = Schema.RequiredMode.REQUIRED)
            double lat,
            @Schema(description = "경도(도)", minimum = "-180", maximum = "180", example = "127.141", requiredMode = Schema.RequiredMode.REQUIRED)
            double lng,
            @Schema(description = "Haversine 직선거리(m), 정수 반올림", minimum = "0", example = "142", requiredMode = Schema.RequiredMode.REQUIRED)
            int distanceMeters,
            @Schema(description = "원본 stat=2인 유효 충전기 수. 실제 주차면 빈자리 보장 아님", minimum = "0", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            int availableCount,
            @Schema(description = "삭제·무효 항목 제외. 비가용 상태도 유효 장비이면 포함", minimum = "0", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
            int totalValidCount,
            @ArraySchema(arraySchema = @Schema(description = "모든 유효 충전기의 정규화 연결 규격. 중복 제거·문자열 오름차순", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(type = "string", allowableValues = {"DC_CHADEMO", "AC_SLOW", "AC_3PHASE", "DC_COMBO", "DC_COMBO_SLOW", "NACS", "DC_COMBO_2", "UNKNOWN"}, example = "DC_COMBO"))
            List<String> chargerTypes,
            @Schema(description = "유효 충전기 최대 출력(kW). 미제공이면 null", nullable = true, example = "100.0", requiredMode = Schema.RequiredMode.REQUIRED)
            Double maxOutputKw,
            @Schema(description = "현재 Mock 사용자의 검증된 실제 방문 횟수. 집계 원천이 없으면 null. Mock 이벤트 제외", nullable = true, minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
            Integer visitCount,
            @Schema(description = "유효 충전기 중 가장 오래된 상태 관측 시각. 하나라도 미관측이면 null. 변경분에 없는 충전기의 시각은 유지", nullable = true, type = "string", format = "date-time", example = "2026-11-11T01:30:00+09:00", requiredMode = Schema.RequiredMode.REQUIRED)
            OffsetDateTime statusObservedAt) {}

    @Schema(name = "StationDetail", description = "API 02. 저장된 충전소와 유효 충전기 상세. 충전 요금 필드 제외")
    public record StationDetail(
            @Schema(description = "환경공단 원본 statId", example = "ST-001", requiredMode = Schema.RequiredMode.REQUIRED)
            String stationId,
            @Schema(description = "환경공단 원본 한국어 충전소명", example = "예시 충전소", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "환경공단 원본 한국어 주소", example = "서울특별시 예시 주소", requiredMode = Schema.RequiredMode.REQUIRED)
            String address,
            @Schema(description = "위도(도)", minimum = "-90", maximum = "90", example = "37.551", requiredMode = Schema.RequiredMode.REQUIRED)
            double lat,
            @Schema(description = "경도(도)", minimum = "-180", maximum = "180", example = "127.141", requiredMode = Schema.RequiredMode.REQUIRED)
            double lng,
            @Schema(description = "원본 stat=2인 유효 충전기 수", minimum = "0", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            int availableCount,
            @Schema(description = "삭제·무효 항목을 제외한 충전기 수", minimum = "0", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
            int totalValidCount,
            @ArraySchema(arraySchema = @Schema(description = "유효 충전기 상세. chargerId 문자열 오름차순", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(implementation = ChargerDetail.class))
            List<ChargerDetail> chargers,
            @Schema(description = "유효 충전기 중 가장 오래된 상태 관측 시각. 하나라도 미관측이면 null", nullable = true, type = "string", format = "date-time", example = "2026-11-11T01:30:00+09:00", requiredMode = Schema.RequiredMode.REQUIRED)
            OffsetDateTime statusObservedAt) {}

    @Schema(name = "ChargerDetail", description = "충전기 상세. 원본 코드와 정규화 값을 함께 제공")
    public record ChargerDetail(
            @Schema(description = "환경공단 chgerId. 앞자리 0을 보존하는 문자열", example = "01", requiredMode = Schema.RequiredMode.REQUIRED)
            String chargerId,
            @Schema(description = "단일 규격 이름. 복합 규격은 모든 이름을 +로 연결", example = "DC_CHADEMO+DC_COMBO", requiredMode = Schema.RequiredMode.REQUIRED)
            String connectorType,
            @ArraySchema(arraySchema = @Schema(description = "복합 규격을 축약하지 않은 전체 정규화 연결 규격", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(type = "string", allowableValues = {"DC_CHADEMO", "AC_SLOW", "AC_3PHASE", "DC_COMBO", "DC_COMBO_SLOW", "NACS", "DC_COMBO_2", "UNKNOWN"}, example = "DC_COMBO"))
            List<String> connectorTypes,
            @Schema(description = "환경공단 원본 chgerType. 알려지지 않은 새 코드도 원문 보존", example = "05", requiredMode = Schema.RequiredMode.REQUIRED)
            String chgerType,
            @Schema(description = "환경공단 output 출력(kW). 미제공·무효이면 null", nullable = true, example = "100.0", requiredMode = Schema.RequiredMode.REQUIRED)
            Double outputKw,
            @Schema(description = "기존 01·02 호환 상태값. 가용 집계는 stat=2만 포함. 길 안내 reasonCode와 구분", allowableValues = {"UNKNOWN", "COMMUNICATION_ERROR", "AVAILABLE", "CHARGING", "STOPPED", "MAINTENANCE", "RESERVED", "UNCONFIRMED"}, example = "AVAILABLE", requiredMode = Schema.RequiredMode.REQUIRED)
            String status,
            @Schema(description = "환경공단 원본 stat. 새 코드도 원문 보존. 2만 가용 집계", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
            String stat,
            @Schema(description = "환경공단 statUpdDt 상태 변경 시각. 미제공·파싱 불가이면 null", nullable = true, type = "string", format = "date-time", example = "2026-11-11T01:29:00+09:00", requiredMode = Schema.RequiredMode.REQUIRED)
            OffsetDateTime statusUpdatedAt,
            @Schema(description = "서버가 이 충전기의 상태를 실제 관측한 시각. 미관측이면 null", nullable = true, type = "string", format = "date-time", example = "2026-11-11T01:30:00+09:00", requiredMode = Schema.RequiredMode.REQUIRED)
            OffsetDateTime statusObservedAt) {}
}
