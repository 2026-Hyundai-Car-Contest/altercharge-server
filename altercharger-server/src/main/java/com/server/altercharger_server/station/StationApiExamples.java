package com.server.altercharger_server.station;

final class StationApiExamples {
    private StationApiExamples() {}
    static final String PAGE = """
            {"items":[{"stationId":"ST-001","name":"예시 충전소","address":"서울특별시 예시 주소","lat":37.551,"lng":127.141,"distanceMeters":142,"availableCount":1,"totalValidCount":2,"chargerTypes":["AC_SLOW","DC_COMBO"],"maxOutputKw":100.0,"visitCount":null,"statusObservedAt":"2026-11-11T01:30:00+09:00"}],"page":0,"size":20,"hasNext":false,"fetchedAt":"2026-11-11T01:31:00+09:00"}
            """;
    static final String EMPTY_PAGE = """
            {"items":[],"page":0,"size":20,"hasNext":false,"fetchedAt":"2026-11-11T01:31:00+09:00"}
            """;
    static final String DETAIL = """
            {"stationId":"ST-001","name":"예시 충전소","address":"서울특별시 예시 주소","lat":37.551,"lng":127.141,"availableCount":1,"totalValidCount":2,"chargers":[{"chargerId":"01","connectorType":"DC_COMBO","connectorTypes":["DC_COMBO"],"chgerType":"04","outputKw":100.0,"status":"AVAILABLE","stat":"2","statusUpdatedAt":"2026-11-11T01:30:00+09:00","statusObservedAt":"2026-11-11T01:30:00+09:00"},{"chargerId":"02","connectorType":"AC_SLOW","connectorTypes":["AC_SLOW"],"chgerType":"02","outputKw":null,"status":"CHARGING","stat":"3","statusUpdatedAt":"2026-11-11T01:29:00+09:00","statusObservedAt":"2026-11-11T01:30:00+09:00"}],"statusObservedAt":"2026-11-11T01:30:00+09:00"}
            """;
    static final String RECOMMEND = """
            {"station":{"stationId":"ST-001","name":"예시 충전소","distanceMeters":150,"availableCount":1,"visitCount":null,"speedCategory":"FAST","maxOutputKw":100.0},"reasonCode":"VISITS_THEN_DISTANCE","fallbackUsed":false,"statusObservedAt":"2026-11-11T01:30:00+09:00"}
            """;
    static final String FALLBACK = """
            {"station":{"stationId":"ST-001","name":"예시 충전소","distanceMeters":150,"availableCount":0,"visitCount":null,"speedCategory":null,"maxOutputKw":null},"reasonCode":"NEAREST_FALLBACK","fallbackUsed":true,"statusObservedAt":"2026-11-11T01:30:00+09:00"}
            """;
    static final String NO_RECOMMENDATION = """
            {"station":null,"reasonCode":"NO_STATIONS_IN_RADIUS","fallbackUsed":false,"statusObservedAt":null}
            """;
    static final String NAV_OK = """
            {"stationId":"ST-001","navigationAllowed":true,"reasonCode":"OK","checkedAt":"2026-11-11T01:31:00+09:00"}
            """;
    static final String NAV_STALE = """
            {"stationId":"ST-001","navigationAllowed":false,"reasonCode":"STATUS_STALE","checkedAt":"2026-11-11T02:00:00+09:00"}
            """;
    static final String BAD_REQUEST = """
            {"error":"INVALID_REQUEST","message":"요청 파라미터의 형식과 필수 값을 확인해 주세요."}
            """;
    static final String NOT_FOUND = """
            {"error":"STATION_NOT_FOUND","message":"해당 충전소를 찾을 수 없습니다."}
            """;
    static final String UNAVAILABLE = """
            {"error":"STATION_DATA_UNAVAILABLE","message":"아직 수집된 충전소 데이터가 없습니다. 잠시 후 다시 시도해 주세요."}
            """;
    static final String DATABASE_UNAVAILABLE = """
            {"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}
            """;
    static final String INTERNAL_ERROR = """
            {"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}
            """;
}
