# AlterCharge 백엔드 API 명세서

- 배포 API 기본 주소: [https://15-164-244-37.sslip.io/api/v1](https://15-164-244-37.sslip.io/api/v1)
- Swagger UI: [https://15-164-244-37.sslip.io/swagger-ui/index.html](https://15-164-244-37.sslip.io/swagger-ui/index.html)
- OpenAPI JSON: [https://15-164-244-37.sslip.io/v3/api-docs](https://15-164-244-37.sslip.io/v3/api-docs)

버전 **0.2.0** · 요구사항정의서 v0.1(2026-11-10) 기반 · 업무 REST API **01~08 구현**. Java 21, Spring Boot 4.1.2, Gradle Wrapper 9.8.2, Spring Data JPA, PostgreSQL, Flyway를 사용한다. 아래 요청·응답의 충전소, 이벤트, 거리, 시각은 모두 가상의 테스트 데이터다.

MVP는 주차 화면이다. 차량 기록을 PostgreSQL에 저장하는 방식과 출력별 속도 구간은 기존 협업 설계안에 따른 구현 가정이다. 원문에서 프론트 담당인 충전 권장 여부·다음 충전일·화면 문구를 서버가 계산하지 않는다. 기존 API 초안 문서는 삭제했으며 현재 계약은 이 문서와 실제 OpenAPI를 기준으로 한다.

`mock-user-001`, `mock-admin-001`, `mock-vehicle-001`은 DB 시연용 시드다. Mock ID는 인증 수단이 아니며, 현재 요청은 시연용 사용자·차량을 대상으로 한다. 실제 충전 이벤트 수신 경로가 검증되지 않아 외부 이벤트는 `MOCK`만 받는다. 운영용 인증과 사용자 격리 없이 위치·차량 기록을 외부에 공개하면 안 된다.

## 목차

- [Base URL](#base-url)
- [Swagger 문서](#swagger-문서)
- [API 요약](#api-요약)
- [공통 타입·오류·코드](#공통-타입오류코드)
- [01. 주변 충전소 목록·검색](#api-01) — `GET https://15-164-244-37.sslip.io/api/v1/stations`
- [02. 충전소 상세·충전기 상태](#api-02) — `GET https://15-164-244-37.sslip.io/api/v1/stations/{stationId}`
- [03. 추천 충전소 1곳](#api-03) — `GET https://15-164-244-37.sslip.io/api/v1/stations/recommendation`
- [04. 길 안내 직전 상태 확인](#api-04) — `GET https://15-164-244-37.sslip.io/api/v1/stations/{stationId}/navigation-check`
- [05. 충전 시작·완료 이벤트](#api-05) — `POST https://15-164-244-37.sslip.io/api/v1/charging-events`
- [06. 차량 스냅샷 저장](#api-06) — `POST https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots`
- [07. 최근 차량 기록](#api-07) — `GET https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots`
- [08. 최근 충전 완료 이력](#api-08) — `GET https://15-164-244-37.sslip.io/api/v1/charging-events/latest`
- [환경공단 수집·캐시·Mock 정책](#환경공단-수집캐시mock-정책)
- [1차 제외 범위](#1차-제외-범위)
- [로컬 실행·DB 이전·검증](#로컬-실행db-이전검증)
- [배포 상태](#배포-상태)

## Base URL

| 환경 | 주소 | 상태 |
| --- | --- | --- |
| 운영 | [https://15-164-244-37.sslip.io](https://15-164-244-37.sslip.io) | 확정된 공개 호스트. 이번 변경의 배포 여부는 아래 배포 상태 참조 |
| 로컬 전용 | `https://localhost:8443` | Docker Compose Caddy. 로컬 CA 신뢰 설정 필요 |

운영 API 접두사는 `https://15-164-244-37.sslip.io/api/v1`이다. 호스트 이름은 EC2 공인 IP `15.164.244.37`에 의존하므로 공인 IP가 바뀌면 sslip.io 주소와 설정·링크도 확인해야 한다. 로컬 초기화는 HTTPS 8443, 운영은 80/443을 사용한다. `HTTPS_PORT`로 로컬 포트를 바꿀 수 있으며 앱 8080과 PostgreSQL 5432는 호스트에 공개하지 않는다.

## Swagger 문서

- 운영 UI: [Swagger UI](https://15-164-244-37.sslip.io/swagger-ui/index.html), 호환 경로 `https://15-164-244-37.sslip.io/swagger-ui.html`
- 운영 JSON: [OpenAPI JSON](https://15-164-244-37.sslip.io/v3/api-docs)
- 로컬 전용 UI: [Swagger UI](https://localhost:8443/swagger-ui/index.html), 호환 경로 `https://localhost:8443/swagger-ui.html`
- 로컬 전용 JSON: [OpenAPI JSON](https://localhost:8443/v3/api-docs)

Swagger 서버 목록은 상대 경로 `/`를 기본으로 하여 현재 접속한 HTTPS 호스트·포트를 따르고, 운영 절대 URL도 별도 선택할 수 있다. 로컬에서 Try it out을 실행할 때는 `/`를 선택하고 로컬 Caddy 공개 루트 CA를 신뢰해야 한다. Caddy의 HTTPS 전달과 Spring forwarded headers 설정은 유지한다.

## API 요약

| 번호 | Method | URI | 기능 | 요구사항 ID |
| --- | --- | --- | --- | --- |
| [01](#api-01) | GET | `https://15-164-244-37.sslip.io/api/v1/stations` | 주변 충전소 목록·검색 | REQ_ST_001, REQ_SL_001, REQ_SL_002, REQ_SL_004, REQ_SL_005, REQ_VS_006, REQ_CM_006 |
| [02](#api-02) | GET | `https://15-164-244-37.sslip.io/api/v1/stations/{stationId}` | 충전소·충전기 상세 | REQ_ST_007, REQ_SL_001 |
| [03](#api-03) | GET | `https://15-164-244-37.sslip.io/api/v1/stations/recommendation` | 방문·거리 기준 추천 1곳 | REQ_ST_005, REQ_ST_006, REQ_ST_007 |
| [04](#api-04) | GET | `https://15-164-244-37.sslip.io/api/v1/stations/{stationId}/navigation-check` | 목적지 안내 전 상태 판정 | REQ_EX_004, REQ_BR_004, REQ_BR_005 |
| [05](#api-05) | POST | `https://15-164-244-37.sslip.io/api/v1/charging-events` | 충전 시작·완료 이벤트 저장 | REQ_ST_008, REQ_VS_005, REQ_LG_002 |
| [06](#api-06) | POST | `https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots` | 차량 상태·누적거리 저장 | REQ_JD_002, REQ_VS_001, REQ_VS_004 |
| [07](#api-07) | GET | `https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots` | 최대 8주 차량 기록 | REQ_JD_002, REQ_LG_002, REQ_CM_006 |
| [08](#api-08) | GET | `https://15-164-244-37.sslip.io/api/v1/charging-events/latest` | 최근 충전 완료·거리 차분 | REQ_VS_005, REQ_LG_002 |

모든 GET은 Body 없이 Path·Query를 사용한다. POST 요청과 모든 업무 응답은 `application/json`이다. Actuator health와 Swagger는 업무 API 8개 외의 구동·문서 경로다.

## 공통 타입·오류·코드

| JSON | Kotlin | 계약 |
| --- | --- | --- |
| string | `String` | ID의 앞자리 0 보존. 충전소명·주소는 원본 한국어 유지 |
| integer | `Int` | SOC(%), 개수, 정수 거리(m) |
| number | `Double` | 위·경도(도), 출력(kW), 누적거리(km). 주행가능거리는 `Int?` |
| boolean | `Boolean` | true/false |
| array | `List<T>` | 조회 0건은 `[]` |
| null 허용 | `T?` | 알 수 없는 값을 0·빈 문자열로 만들지 않음 |
| string(date-time) | `String` | ISO 8601 오프셋 필수. 예: `2026-11-11T01:30:00+09:00` |
| string(date) | `String` | `YYYY-MM-DD`. 기록일·하루 중복은 `Asia/Seoul` 기준 |

시각은 같은 순간이면 다른 UTC 오프셋 표기도 가능하다. 기록 시각은 UTC로 정규화하고 마이크로초 미만을 절삭한다. 나노초 입력도 허용하며 정규화한 시각으로 멱등성을 비교한다. 응답에 표시된 nullable 필드는 JSON `null`을 허용한다. 위·경도와 모든 실수는 NaN·무한대를 허용하지 않는다.

공통 오류 응답(`ErrorResponse`, 서버 DTO `ApiError`)의 필드는 정확히 아래 2개다. API별 실제 오류는 각 응답 표에 명시한다. 외부 환경공단 호출은 비동기 수집이므로 업무 요청에 502를 임의로 반환하지 않는다. 현재 인증·레이트리미팅은 구현하지 않아 401·403·429를 계약에 넣지 않는다.

```json
{"error":"STATION_NOT_FOUND","message":"해당 충전소를 찾을 수 없습니다."}
```

| 필드 | JSON 타입 | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| error | string | `String` | 예 | `STATION_NOT_FOUND` | 프로그램이 분기할 오류 코드 |
| message | string | `String` | 예 | 해당 충전소를 찾을 수 없습니다. | 한국어 안내. 내부 SQL·키·스택트레이스 제외 |

정의된 API 외의 경로는 404 `{"error":"NOT_FOUND","message":"요청한 경로를 찾을 수 없습니다."}`, 지원하지 않는 HTTP 메서드는 405 `{"error":"METHOD_NOT_ALLOWED","message":"지원하지 않는 요청 방식입니다."}`다. POST의 잘못된 Content-Type·JSON은 400 INVALID_REQUEST_BODY로 통일한다. 각 표의 message는 대표 예시이며 검증 항목에 따라 구체적인 한국어 설명을 반환한다.

충전기 상태는 기존 01·02의 문자열을 유지한다. 원본 코드와 길 안내 사유는 서로 다른 값이다.

| 환경공단 stat | 응답 status | 의미 | 가용 수 포함 |
| --- | --- | --- | --- |
| 0 / 알 수 없는 코드 | UNKNOWN | 알 수 없음 | 아니오 |
| 1 | COMMUNICATION_ERROR | 통신 이상 | 아니오 |
| 2 | AVAILABLE | 충전 대기 | **예** |
| 3 | CHARGING | 충전 중 | 아니오 |
| 4 | STOPPED | 운영 중지 | 아니오 |
| 5 | MAINTENANCE | 점검 중 | 아니오 |
| 6 | RESERVED | 예약 중 | 아니오 |
| 9 | UNCONFIRMED | 상태 미확인 | 아니오 |

확정된 별도 고장 코드는 만들지 않는다. `availableCount`는 주차면 빈자리 수가 아니며, 오래된 `AVAILABLE`도 실시간 이용 가능 보장이 아니다.

| 원본 chgerType | connectorTypes |
| --- | --- |
| 01 | `["DC_CHADEMO"]` |
| 02 | `["AC_SLOW"]` |
| 03 | `["DC_CHADEMO","AC_3PHASE"]` |
| 04 | `["DC_COMBO"]` |
| 05 | `["DC_CHADEMO","DC_COMBO"]` |
| 06 | `["DC_CHADEMO","AC_3PHASE","DC_COMBO"]` |
| 07 | `["AC_3PHASE"]` |
| 08 | `["DC_COMBO_SLOW"]` |
| 09 | `["NACS"]` |
| 10 | `["DC_COMBO","NACS"]` |
| 11 | `["DC_COMBO_2"]`(버스 전용 원본 코드) |
| 그 외 | `["UNKNOWN"]` 및 원본 코드 보존 |

상세 `connectorType`은 위 배열을 `+`로 연결한다. 복합 규격을 단일 규격으로 줄이지 않는다. 속도는 출력 `<50kW` → `SLOW`, `50~<200kW` → `FAST`, `>=200kW` → `ULTRA_FAST`, 출력 미제공 → `null`이다. 이 구간은 기획 설계안이며 공식 확정 분류가 아니다.

| 구분 | 값 | 의미 |
| --- | --- | --- |
| 추천 reasonCode | VISITS_THEN_DISTANCE | 유효 후보를 실제 방문수 내림차순·거리·ID 순으로 선정 |
| 추천 reasonCode | NEAREST_FALLBACK | 유효 후보가 없어 반경 내 최단거리 충전소 제시 |
| 추천 reasonCode | NO_STATIONS_IN_RADIUS | 3km 안 충전소 없음. station=null |
| 길 안내 reasonCode | OK | 유효기간 안의 AVAILABLE 충전기 존재 |
| 길 안내 reasonCode | STATUS_STALE | 허용 가능한 가용 충전기가 없고 관측 시각 누락·만료 존재 |
| 길 안내 reasonCode | OUT_OF_SERVICE | 운영 중지 상태 포함 |
| 길 안내 reasonCode | MAINTENANCE | 점검 상태 포함 |
| 길 안내 reasonCode | COMM_ERROR | 통신 이상 상태 포함 |
| 길 안내 reasonCode | STATUS_UNKNOWN | 알 수 없거나 미확인 상태 포함 |
| 길 안내 reasonCode | NO_AVAILABLE_CHARGER | 충전 중·예약 중 등으로 가용 충전기 없음 |
| eventType | START / COMPLETE | 충전 시작 / 완료. END는 사용하지 않음 |
| source | MOCK / REAL | 시연 / 검증된 실제 원천. **외부 입력은 MOCK만 허용** |

<a id="api-01"></a>

## 01. 주변 충전소 목록·검색

요구사항: `REQ_ST_001`, `REQ_SL_001`, `REQ_SL_002`, `REQ_SL_004`, `REQ_SL_005`, `REQ_VS_006`, `REQ_CM_006`. PostgreSQL 캐시에서 반경 내 충전소를 검색한다. 요청마다 환경공단 API를 호출하지 않는다.

`GET https://15-164-244-37.sslip.io/api/v1/stations`

| Query | JSON 의미 타입 | Kotlin | 필수 | 예시·기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| lat | number | `Double` | 예 | 37.5500 | -90~90도 |
| lng | number | `Double` | 예 | 127.1400 | -180~180도 |
| radiusKm | number | `Double` | 아니오 | 기본 3 | 0 초과 3 이하(km) |
| keyword | string | `String?` | 아니오 | 강동 / 빈 값 | 이름·주소 부분 검색 |
| page | integer | `Int` | 아니오 | 기본 0 | 0 이상 |
| size | integer | `Int` | 아니오 | 기본 20 | 1~200. 내부 DB 응답 단위 |

```http
GET https://15-164-244-37.sslip.io/api/v1/stations?lat=37.5500&lng=127.1400&radiusKm=3&keyword=&page=0&size=20 HTTP/1.1
Host: 15-164-244-37.sslip.io
```

Haversine 직선거리로 반경 필터링한 뒤 거리 오름차순 → stationId 오름차순으로 정렬하고 페이지를 나눈다.

```json
{
  "items":[{
    "stationId":"ST-001","name":"예시 충전소","address":"서울특별시 예시 주소",
    "lat":37.551,"lng":127.141,"distanceMeters":142,
    "availableCount":1,"totalValidCount":2,"chargerTypes":["AC_SLOW","DC_COMBO"],
    "maxOutputKw":100.0,"visitCount":null,"statusObservedAt":"2026-11-11T01:30:00+09:00"
  }],
  "page":0,"size":20,"hasNext":false,"fetchedAt":"2026-11-11T01:31:00+09:00"
}
```

| 응답 필드 | JSON | Kotlin | 필수·null | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| items | array<object> | `List<StationSummaryDto>` | 예 | 위 예시 / `[]` | 현재 페이지 |
| items[].stationId | string | `String` | 예 | ST-001 | 원본 statId |
| items[].name | string | `String` | 예 | 예시 충전소 | 원본 한국어 이름 |
| items[].address | string | `String` | 예 | 서울특별시 예시 주소 | 원본 한국어 주소 |
| items[].lat / lng | number | `Double` | 예 | 37.551 / 127.141 | 위·경도(도) |
| items[].distanceMeters | integer | `Int` | 예 | 142 | 직선거리(m), 반올림 |
| items[].availableCount | integer | `Int` | 예 | 1 | stat=2 유효 충전기 수 |
| items[].totalValidCount | integer | `Int` | 예 | 2 | 삭제·무효 제외 수 |
| items[].chargerTypes | array<string> | `List<String>` | 예 | `["DC_COMBO"]` | 전체 규격 합집합, 문자열 정렬 |
| items[].maxOutputKw | number/null | `Double?` | 예·허용 | 100.0 | 최대 출력(kW) |
| items[].visitCount | integer/null | `Int?` | 예·허용 | null | 검증된 현재 사용자 실제 방문수. 미확인은 null |
| items[].statusObservedAt | string/null | `String?` | 예·허용 | 위 시각 | 유효 충전기 중 가장 오래된 관측 시각 |
| page / size | integer | `Int` | 예 | 0 / 20 | 요청 페이지·크기 |
| hasNext | boolean | `Boolean` | 예 | false | 다음 페이지 존재 |
| fetchedAt | string | `String` | 예 | 위 시각 | DB 조회 응답 생성 시각 |

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 200 | 정상 / 검색 0건 | 위 정상 JSON / `{"items":[],"page":0,"size":20,"hasNext":false,"fetchedAt":"2026-11-11T01:31:00+09:00"}` |
| 400 | 좌표·반경·페이지·필수 Query 오류 | `{"error":"INVALID_REQUEST","message":"요청 파라미터의 형식과 필수 값을 확인해 주세요."}` |
| 503 | 초기 수집 데이터 없음 | `{"error":"STATION_DATA_UNAVAILABLE","message":"아직 수집된 충전소 데이터가 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

[↑ 목차](#목차)

<a id="api-02"></a>

## 02. 충전소 상세·충전기 상태

요구사항: `REQ_ST_007`, `REQ_SL_001`. 원본 충전소와 충전기 상태를 조회한다. 요금은 1차 제외다.

`GET https://15-164-244-37.sslip.io/api/v1/stations/{stationId}`

| Path | JSON 의미 타입 | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| stationId | string | `String` | 예 | ST-001 | 실제 요청은 환경공단 statId 사용 |

```http
GET https://15-164-244-37.sslip.io/api/v1/stations/ST-001 HTTP/1.1
Host: 15-164-244-37.sslip.io
```

```json
{
  "stationId":"ST-001","name":"예시 충전소","address":"서울특별시 예시 주소",
  "lat":37.551,"lng":127.141,"availableCount":1,"totalValidCount":2,
  "chargers":[{
    "chargerId":"01","connectorType":"DC_COMBO","connectorTypes":["DC_COMBO"],
    "chgerType":"04","outputKw":100.0,"status":"AVAILABLE","stat":"2",
    "statusUpdatedAt":"2026-11-11T01:29:00+09:00","statusObservedAt":"2026-11-11T01:30:00+09:00"
  },{
    "chargerId":"02","connectorType":"AC_SLOW","connectorTypes":["AC_SLOW"],
    "chgerType":"02","outputKw":null,"status":"CHARGING","stat":"3",
    "statusUpdatedAt":null,"statusObservedAt":"2026-11-11T01:30:00+09:00"
  }],
  "statusObservedAt":"2026-11-11T01:30:00+09:00"
}
```

| 응답 필드 | JSON | Kotlin | 필수·null | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| stationId / name / address | string | `String` | 예 | 위 예시 | 원본 ID·한국어 이름·주소 |
| lat / lng | number | `Double` | 예 | 37.551 / 127.141 | 위·경도(도) |
| availableCount / totalValidCount | integer | `Int` | 예 | 1 / 2 | 가용 수 / 삭제·무효 제외 수 |
| chargers | array<object> | `List<ChargerDto>` | 예 | 위 예시 | chargerId 문자열 오름차순 |
| chargers[].chargerId | string | `String` | 예 | 01 | 앞자리 0 보존 |
| chargers[].connectorType | string | `String` | 예 | DC_COMBO | 복합 규격이면 `+` 연결 |
| chargers[].connectorTypes | array<string> | `List<String>` | 예 | `["DC_COMBO"]` | 모든 정규화 규격 |
| chargers[].chgerType | string | `String` | 예 | 04 | 환경공단 원본 연결 코드 |
| chargers[].outputKw | number/null | `Double?` | 예·허용 | 100.0 / null | 출력(kW). 미제공·무효는 null |
| chargers[].status | string | `String` | 예 | AVAILABLE | 공통 상태표 참조 |
| chargers[].stat | string | `String` | 예 | 2 | 환경공단 원본 상태 코드 |
| chargers[].statusUpdatedAt | string/null | `String?` | 예·허용 | 위 시각 / null | 원천 statUpdDt 변경 시각 |
| chargers[].statusObservedAt | string/null | `String?` | 예·허용 | 위 시각 | 해당 충전기 서버 관측 시각 |
| statusObservedAt | string/null | `String?` | 예·허용 | 위 시각 | 유효 충전기 중 가장 오래된 관측 시각 |

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 200 | 정상 | 위 정상 JSON |
| 404 | 수집된 자료에 충전소 없음 | `{"error":"STATION_NOT_FOUND","message":"해당 충전소를 찾을 수 없습니다."}` |
| 503 | 초기 수집 데이터 없음 | `{"error":"STATION_DATA_UNAVAILABLE","message":"아직 수집된 충전소 데이터가 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

[↑ 목차](#목차)

<a id="api-03"></a>

## 03. 추천 충전소 1곳

요구사항: `REQ_ST_005`, `REQ_ST_006`, `REQ_ST_007`. 프론트의 충전 권장 판정값을 받아 서버 캐시의 후보 1곳을 고른다.

`GET https://15-164-244-37.sslip.io/api/v1/stations/recommendation`

| Query | JSON 의미 타입 | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| lat | number | `Double` | 예 | 37.5500 | -90~90도 |
| lng | number | `Double` | 예 | 127.1400 | -180~180도 |
| chargeRecommended | boolean | `Boolean` | 예 | true | 프론트 판정값. 서버 재계산 없음 |

```http
GET https://15-164-244-37.sslip.io/api/v1/stations/recommendation?lat=37.5500&lng=127.1400&chargeRecommended=true HTTP/1.1
Host: 15-164-244-37.sslip.io
```

3km 이내에서 가용 충전기가 1개 이상인 충전소를 검토한다. `chargeRecommended=true`면 **가용 충전기 중 출력 50kW 이상**이 있어야 하며, false면 완속도 후보에 포함한다. 검증된 사용자별 실제 방문 횟수 내림차순 → 거리 오름차순 → stationId 오름차순이다. 방문 원천이 없으면 비교 계산에만 0을 쓰고 응답은 `null`을 유지한다.

유효 후보가 없으면 반경 내 최단거리 충전소를 `fallbackUsed=true`로 반환한다. 이 후보는 운영 중지·점검·충전 중일 수 있으며 충전 가능을 뜻하지 않는다. 프론트는 안내 직전 API 04를 호출한다.

```json
{
  "station":{"stationId":"ST-001","name":"예시 충전소","distanceMeters":142,
    "availableCount":1,"visitCount":null,"speedCategory":"FAST","maxOutputKw":100.0},
  "reasonCode":"VISITS_THEN_DISTANCE","fallbackUsed":false,
  "statusObservedAt":"2026-11-11T01:30:00+09:00"
}
```

| 응답 필드 | JSON | Kotlin | 필수·null | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| station | object/null | `RecommendedStationDto?` | 예·허용 | 위 객체 / null | 반경 내 충전소가 없으면 null |
| station.stationId / name | string | `String` | 객체 내 예 | ST-001 / 예시 충전소 | 원본 ID·한국어 이름 |
| station.distanceMeters | integer | `Int` | 객체 내 예 | 142 | 직선거리(m) |
| station.availableCount | integer | `Int` | 객체 내 예 | 1 | stat=2 유효 수. 대체 후보는 0 가능 |
| station.visitCount | integer/null | `Int?` | 객체 내 예·허용 | null | 검증된 실제 방문수. Mock 제외 |
| station.speedCategory | string/null | `String?` | 객체 내 예·허용 | FAST | SLOW / FAST / ULTRA_FAST. 출력 미제공 null |
| station.maxOutputKw | number/null | `Double?` | 객체 내 예·허용 | 100.0 | 충전소 최대 출력(kW) |
| reasonCode | string | `String` | 예 | VISITS_THEN_DISTANCE | 공통 추천 사유표의 3개 값 |
| fallbackUsed | boolean | `Boolean` | 예 | false | 최단거리 대체 후보 여부 |
| statusObservedAt | string/null | `String?` | 예·허용 | 위 시각 / null | 선택 충전소의 보수적 관측 시각 |

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 200 | 정상 추천 | 위 정상 JSON |
| 200 | 대체 추천 | `{"station":{"stationId":"ST-002","name":"예시 중지 충전소","distanceMeters":100,"availableCount":0,"visitCount":null,"speedCategory":null,"maxOutputKw":null},"reasonCode":"NEAREST_FALLBACK","fallbackUsed":true,"statusObservedAt":"2026-11-11T01:30:00+09:00"}` |
| 200 | 반경 3km 충전소 0건 | `{"station":null,"reasonCode":"NO_STATIONS_IN_RADIUS","fallbackUsed":false,"statusObservedAt":null}` |
| 400 | 필수 Query·타입·좌표 오류 | `{"error":"INVALID_REQUEST","message":"요청 파라미터의 형식과 필수 값을 확인해 주세요."}` |
| 503 | 초기 데이터 조회 불가 | `{"error":"STATION_DATA_UNAVAILABLE","message":"아직 수집된 충전소 데이터가 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

`REQ_ST_005` 예외: 유효 기록 **14일 미만**이면 프론트가 **「기록 수집 중」**을 표시하고 SOC **30% 이하**일 때만 `chargeRecommended=true`로 보낸다. 14일 이상이면 기존 프론트 로직을 사용한다. 다음 충전일도 프론트 책임이다.

[↑ 목차](#목차)

<a id="api-04"></a>

## 04. 길 안내 직전 상태 확인

요구사항: `REQ_EX_004`, `REQ_BR_004`, `REQ_BR_005`. 저장된 상태와 관측 시각으로 안내 허용 여부만 판단한다. 경로 생성·목적지 변경은 프론트 Pleos NaviHelper가 담당한다.

`GET https://15-164-244-37.sslip.io/api/v1/stations/{stationId}/navigation-check`

| Path | JSON 의미 타입 | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| stationId | string | `String` | 예 | ST-001 | 환경공단 원본 statId |

```http
GET https://15-164-244-37.sslip.io/api/v1/stations/ST-001/navigation-check HTTP/1.1
Host: 15-164-244-37.sslip.io
```

충전기별 관측 시각이 현재부터 `STATION_STATUS_MAX_AGE_MINUTES`(기본 15분) 이내이고 상태가 AVAILABLE이면 다른 충전기가 오래됐어도 허용한다. 오래된 AVAILABLE만으로는 허용하지 않는다. 허용 가능한 가용 충전기가 없으면 **관측시각 누락·미래·만료(STATUS_STALE) → 운영 중지 → 점검 → 통신 이상 → 알 수 없음·미확인 → 충전 중·예약 중** 순으로 대표 차단 사유를 정한다.

```json
{
  "stationId":"ST-001","navigationAllowed":true,"reasonCode":"OK",
  "latestStatuses":["AVAILABLE","CHARGING"],"checkedAt":"2026-11-11T01:31:00+09:00"
}
```

| 응답 필드 | JSON | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| stationId | string | `String` | 예 | ST-001 | 충전소 ID |
| navigationAllowed | boolean | `Boolean` | 예 | true | 신선한 가용 충전기 존재 시에만 true |
| reasonCode | string | `String` | 예 | OK | 공통 길 안내 사유표의 7개 값 |
| latestStatuses | array<string> | `List<String>` | 예 | `["AVAILABLE","CHARGING"]` | 02와 동일한 상태 문자열 목록 |
| checkedAt | string | `String` | 예 | 위 시각 | 서버 판정 시각. 외부 실시간 조회 시각 아님 |

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 200 | 안내 허용 | 위 정상 JSON |
| 200 | 정상 차단(예: 오래된 상태) | `{"stationId":"ST-001","navigationAllowed":false,"reasonCode":"STATUS_STALE","latestStatuses":["AVAILABLE"],"checkedAt":"2026-11-11T01:31:00+09:00"}` |
| 404 | 충전소 없음 | `{"error":"STATION_NOT_FOUND","message":"해당 충전소를 찾을 수 없습니다."}` |
| 503 | 사용할 초기 상태 데이터 없음 | `{"error":"STATION_DATA_UNAVAILABLE","message":"아직 수집된 충전소 데이터가 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

운영 중지·점검·통신 이상·만료는 정상 판정이므로 200 + false다. 503은 조회 자체가 불가능한 경우이며 오래됐다는 이유만으로 502를 사용하지 않는다.

[↑ 목차](#목차)

<a id="api-05"></a>

## 05. 충전 시작·완료 이벤트

요구사항: `REQ_ST_008`, `REQ_VS_005`, `REQ_LG_002`. 시연용 이벤트를 저장하며 COMPLETE는 API 08의 최근 충전 기록에 사용한다.

`POST https://15-164-244-37.sslip.io/api/v1/charging-events`

| JSON Body | JSON | Kotlin | 필수 | 예시·기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| eventId | string | `String` | 예 | evt-001 | 1~100자, 전역 멱등 이벤트 식별자 |
| vehicleId | string | `String` | 예 | mock-vehicle-001 | 1~100자 시드 차량 ID |
| eventType | string | `String` | 예 | START | START / COMPLETE |
| source | string | `String?` | 아니오 | 기본 MOCK | 외부 REAL 요청은 400 |
| occurredAt | string | `String` | 예 | 2026-11-11T01:00:00+09:00 | 오프셋 필수, 최근 보관기간~현재+5분 |
| lat / lng | number | `Double` | 예 | 37.5509 / 127.1409 | -90~90 / -180~180도 |
| socPercent | integer | `Int` | 예 | 35 | 0~100(%) |
| odometerKm | number/null | `Double?` | 아니오 | 20350.4 / null | 누적거리(km), 0 이상 |

```http
POST https://15-164-244-37.sslip.io/api/v1/charging-events HTTP/1.1
Host: 15-164-244-37.sslip.io
Content-Type: application/json

{"eventId":"evt-001","vehicleId":"mock-vehicle-001","eventType":"START","source":"MOCK","occurredAt":"2026-11-11T01:00:00+09:00","lat":37.5509,"lng":127.1409,"socPercent":35,"odometerKm":20350.4}
```

```json
{"eventId":"evt-001","accepted":true,"matchedStationId":"ST-001","visitCountAdded":0,"dailyDuplicate":false}
```

| 응답 필드 | JSON | Kotlin | 필수·null | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| eventId | string | `String` | 예 | evt-001 | 보낸 이벤트 ID |
| accepted | boolean | `Boolean` | 예 | true | 저장 또는 동일 내용 재전송 수락 |
| matchedStationId | string/null | `String?` | 예·허용 | ST-001 / null | 이벤트 위치 100m 이내 최단거리·ID 순 매칭 |
| visitCountAdded | integer | `Int` | 예 | 0 | 외부 MOCK 이벤트는 실제 방문 추가 0 |
| dailyDuplicate | boolean | `Boolean` | 예 | false | 실제 방문의 사용자·충전소·서울 날짜 하루 중복 여부. MOCK은 실제 집계에 넣지 않음 |

같은 eventId의 동일 내용은 보관기간이 지났어도 기존 결과를 200으로 반환한다. 같은 ID에 다른 내용이면 409다. 생략한 source는 MOCK으로 정규화한다. 실제 방문은 검증된 REAL START에만 반경 100m와 하루 1회 조건을 적용하며, 현재 외부 요청이 REAL 집계 경로에 접근할 수는 없다. Mock 입력으로 추천 방문 순위를 조작하지 않는다.

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 201 | 신규 저장 | 위 정상 JSON |
| 200 | 동일 이벤트 재전송 | 최초 수락한 결과와 동일 JSON |
| 400 | Body·시각·범위·REAL 입력 오류 | `{"error":"INVALID_REQUEST_BODY","message":"요청 본문의 필수 값, 자료형, 범위를 확인해 주세요."}` |
| 404 | 차량 없음 | `{"error":"VEHICLE_NOT_FOUND","message":"해당 차량을 찾을 수 없습니다."}` |
| 409 | 같은 eventId에 다른 내용 | `{"error":"EVENT_ID_CONFLICT","message":"같은 eventId에 다른 이벤트 내용이 저장되어 있습니다."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

매칭할 충전소 캐시가 없거나 100m 안 충전소가 없으면 matchedStationId는 null이다. 이벤트 기록 수락을 실측 충전 완료 인증으로 해석하지 않는다.

[↑ 목차](#목차)

<a id="api-06"></a>

## 06. 차량 스냅샷 저장

요구사항: `REQ_JD_002`, `REQ_VS_001`, `REQ_VS_004`. 프론트 수집 원본을 서버에 보관한다. 충전 권장값·다음 충전일은 생성하지 않는다.

`POST https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots`

| JSON Body | JSON | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| vehicleId | string | `String` | 예 | mock-vehicle-001 | 1~100자 시드 차량 ID |
| recordedAt | string | `String` | 예 | 2026-11-11T01:30:00+09:00 | 오프셋 필수. 최근 보관기간~현재+5분 |
| socPercent | integer | `Int` | 예 | 62 | 0~100(%) |
| odometerKm | number | `Double` | 예 | 20350.4 | 누적거리(km), 0 이상 |
| drivableRangeKm | integer/null | `Int?` | 아니오 | 235 / null | 주행가능거리(km), 0 이상. SDK 출처 미확정 |

```http
POST https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots HTTP/1.1
Host: 15-164-244-37.sslip.io
Content-Type: application/json

{"vehicleId":"mock-vehicle-001","recordedAt":"2026-11-11T01:30:00+09:00","socPercent":62,"odometerKm":20350.4,"drivableRangeKm":null}
```

```json
{"snapshotId":"00000000-0000-4000-8000-000000000001","saved":true,"recordedAt":"2026-11-10T16:30:00Z"}
```

| 응답 필드 | JSON | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| snapshotId | string | `String` | 예 | 위 UUID | 서버 생성 저장 식별자 |
| saved | boolean | `Boolean` | 예 | true | 저장 또는 동일 재전송 성공 |
| recordedAt | string | `String` | 예 | 위 UTC 시각 | 요청과 같은 순간의 오프셋 시각 |

vehicleId + recordedAt의 같은 순간을 고유 키로 사용한다. 같은 수치의 재전송은 같은 snapshotId를 200으로 반환하고 다른 수치이면 409다. 신규 기록의 허용 시간은 현재 서울 날짜 기준 최근 56일이며, 미래는 기본 5분까지다. 기존 동일 스냅샷이 남아 있으면 재전송에 시간 제한을 다시 적용하지 않으며, 정리된 과거 기록은 새 요청으로 검사한다. 설정값은 실행 절을 참고한다.

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 201 | 신규 저장 | 위 정상 JSON |
| 200 | 동일 스냅샷 재전송 | 기존 snapshotId·saved=true·recordedAt |
| 400 | Body·SOC·거리·시각 오류 | `{"error":"INVALID_REQUEST_BODY","message":"요청 본문의 필수 값, 자료형, 범위를 확인해 주세요."}` |
| 404 | 차량 없음 | `{"error":"VEHICLE_NOT_FOUND","message":"해당 차량을 찾을 수 없습니다."}` |
| 409 | 같은 차량·시각에 다른 수치 | `{"error":"SNAPSHOT_CONFLICT","message":"같은 차량과 측정 시각에 다른 값이 저장되어 있습니다."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

[↑ 목차](#목차)

<a id="api-07"></a>

## 07. 최근 차량 기록

요구사항: `REQ_JD_002`, `REQ_LG_002`, `REQ_CM_006`. 최대 최근 8주 원본 스냅샷을 반환한다. 평균 주행거리와 충전 권장 계산은 프론트 책임이다.

`GET https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots`

| Query | JSON 의미 타입 | Kotlin | 필수 | 예시·기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| vehicleId | string | `String` | 예 | mock-vehicle-001 | 차량 ID |
| from | string(date) | `String?` | 아니오 | 기본 오늘-55일 | YYYY-MM-DD, 서울 날짜 |
| to | string(date) | `String?` | 아니오 | 기본 오늘 | YYYY-MM-DD, 시작일 이하 불가 |

```http
GET https://15-164-244-37.sslip.io/api/v1/vehicles/snapshots?vehicleId=mock-vehicle-001&from=2026-10-28&to=2026-11-11 HTTP/1.1
Host: 15-164-244-37.sslip.io
```

요청 범위를 현재 보관 창과 오늘까지로 제한한다. 날짜 범위 양끝을 포함하며 측정 시각 오름차순으로 반환한다. 동일 시각의 레코드는 차량별 고유하다. 차량별 조회를 분리하고, 미수집일을 0km로 채우지 않는다.

```json
{
  "vehicleId":"mock-vehicle-001",
  "records":[{
    "snapshotId":"00000000-0000-4000-8000-000000000001","recordedAt":"2026-11-11T00:30:00+09:00","socPercent":65,
    "odometerKm":20332.0,"drivableRangeKm":null
  },{
    "snapshotId":"00000000-0000-4000-8000-000000000002","recordedAt":"2026-11-11T01:30:00+09:00","socPercent":62,
    "odometerKm":20350.4,"drivableRangeKm":235
  }],
  "retentionDays":56,"completeDayCount":0
}
```

| 응답 필드 | JSON | Kotlin | 필수·null | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| vehicleId | string | `String` | 예 | mock-vehicle-001 | 조회 차량 |
| records | array<object> | `List<VehicleSnapshotDto>` | 예 | 위 배열 / `[]` | 측정 시각 오름차순 |
| records[].snapshotId | string | `String` | 예 | 위 UUID | 저장 식별자 |
| records[].recordedAt | string | `String` | 예 | 위 시각 | 오프셋 측정 시각 |
| records[].socPercent | integer | `Int` | 예 | 62 | SOC(%) |
| records[].odometerKm | number | `Double` | 예 | 20350.4 | 누적거리(km) |
| records[].drivableRangeKm | integer/null | `Int?` | 예·허용 | 235 / null | 주행가능거리(km) |
| retentionDays | integer | `Int` | 예 | 56 | 서버 스냅샷 보관일 수 |
| completeDayCount | integer | `Int` | 예 | 0 | 아래 보수적 기준을 충족한 유효 기록일 수 |

유효 기록일은 서울 날짜별 **2개 이상** 기록이 있고, **첫 측정 01:00 이하·마지막 측정 23:00 이상**, 인접 측정 간격 **6시간 이하**, 누적거리 감소 없음이 모두 성립한 날이다. 같은 누적거리의 0km는 허용하되 누락을 정상 운행일로 가정하지 않는다. 예시의 2개 새벽 기록은 하루를 덮지 않아 completeDayCount=0이다. 이는 연속 센서 수집의 보장이 아니라 실제 저장 자료에 적용한 보수적 판정 기준이다.

`completeDayCount<14`이면 프론트는 「기록 수집 중」을 표시하고 SOC<=30%일 때만 충전을 권장한다. 14일 이상이면 기존 프론트 로직을 사용한다. 서버 응답에는 chargeRecommended·다음 충전일이 없다.

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 200 | 정상 / 기록 없음 | 위 정상 JSON / `{"vehicleId":"mock-vehicle-001","records":[],"retentionDays":56,"completeDayCount":0}` |
| 400 | 필수 Query·날짜 형식·역전 범위 오류 | `{"error":"INVALID_REQUEST","message":"요청 파라미터의 형식과 필수 값을 확인해 주세요."}` |
| 404 | 차량 없음 | `{"error":"VEHICLE_NOT_FOUND","message":"해당 차량을 찾을 수 없습니다."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

[↑ 목차](#목차)

<a id="api-08"></a>

## 08. 최근 충전 완료 이력

요구사항: `REQ_VS_005`, `REQ_LG_002`. 마지막 COMPLETE와 최근 보관된 차량 스냅샷을 조회한다. 시연용 결과는 source=MOCK·trusted=false로 표시한다.

`GET https://15-164-244-37.sslip.io/api/v1/charging-events/latest`

| Query | JSON 의미 타입 | Kotlin | 필수 | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| vehicleId | string | `String` | 예 | mock-vehicle-001 | 차량 ID |

```http
GET https://15-164-244-37.sslip.io/api/v1/charging-events/latest?vehicleId=mock-vehicle-001 HTTP/1.1
Host: 15-164-244-37.sslip.io
```

```json
{
  "vehicleId":"mock-vehicle-001","lastChargeCompletedAt":"2026-11-10T21:10:00+09:00",
  "odometerAtLastChargeKm":20310.0,"currentOdometerKm":20350.4,"distanceSinceChargeKm":40.4,
  "source":"MOCK","trusted":false
}
```

| 응답 필드 | JSON | Kotlin | 필수·null | 예시 | 설명 |
| --- | --- | --- | --- | --- | --- |
| vehicleId | string | `String` | 예 | mock-vehicle-001 | 조회 차량 |
| lastChargeCompletedAt | string/null | `String?` | 예·허용 | 위 시각 / null | 마지막 COMPLETE 시각 |
| odometerAtLastChargeKm | number/null | `Double?` | 예·허용 | 20310.0 | 완료 이벤트의 누적거리(km) |
| currentOdometerKm | number/null | `Double?` | 예·허용 | 20350.4 | 최근 보관된 스냅샷 누적거리(km). COMPLETE가 없으면 null |
| distanceSinceChargeKm | number/null | `Double?` | 예·허용 | 40.4 | 현재 누적거리 - 완료 당시 누적거리(km) |
| source | string/null | `String?` | 예·허용 | MOCK / null | 마지막 완료 원천. 미존재면 null |
| trusted | boolean | `Boolean` | 예 | false | 검증된 실제 이벤트 여부. 현재 외부 입력은 false |

COMPLETE가 없으면 스냅샷이 있어도 차량 ID 외의 nullable 항목은 모두 null이고 trusted=false다. COMPLETE가 여러 개이면 occurredAt 내림차순, 동률이면 eventId 내림차순으로 결정한다. 완료 당시 오도미터가 없거나, 현재 스냅샷이 없거나 완료보다 이전이면 거리 차분은 null이다. 차분이 음수여도 null이며 절댓값을 만들지 않는다. 시작 이벤트가 없다는 이유로 시작 시각·거리 등 추가 실측값을 지어내지 않는다.

| HTTP | 상황 | 본문 예시 |
| --- | --- | --- |
| 200 | 정상 | 위 정상 JSON |
| 200 | 완료 이력·스냅샷 없음 | `{"vehicleId":"mock-vehicle-001","lastChargeCompletedAt":null,"odometerAtLastChargeKm":null,"currentOdometerKm":null,"distanceSinceChargeKm":null,"source":null,"trusted":false}` |
| 400 | 필수 Query·차량 ID 형식 오류 | `{"error":"INVALID_REQUEST","message":"요청 파라미터의 형식과 필수 값을 확인해 주세요."}` |
| 404 | 차량 없음 | `{"error":"VEHICLE_NOT_FOUND","message":"해당 차량을 찾을 수 없습니다."}` |
| 503 | DB 저장소 연결 불가 | `{"error":"SERVICE_UNAVAILABLE","message":"데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."}` |
| 500 | 예상하지 못한 오류 | `{"error":"INTERNAL_SERVER_ERROR","message":"서버에서 요청을 처리하지 못했습니다."}` |

[↑ 목차](#목차)

## 환경공단 수집·캐시·Mock 정책

[한국환경공단 OpenAPI](https://www.data.go.kr/data/15076352/openapi.do)의 getChargerInfo·getChargerStatus에 `dataType=JSON`을 요청하고 Jackson으로 파싱하여 PostgreSQL에 저장한다. 앱에 반환하는 자체 JSON 계약은 그대로 유지한다. 2026-11-12 인증된 검증에서 두 오퍼레이션을 각각 1회 호출해 HTTP 200, `application/json;charset=UTF-8`, `resultCode=00`, 평평한 루트와 `items.item` 배열을 확인했다. 이 확인은 이번 코드가 운영에 배포되었다는 뜻은 아니다.

외부 응답의 형식·오류·누락값은 수신 경계 한 곳에서 처리한다. JSON 요청에 XML·HTML 등 예상하지 않은 응답이 오면 정상 목록으로 취급하지 않고 수집 실패로 처리하여 기존 캐시를 유지한다. 연결·읽기 시간 제한, 응답 크기 제한, 지역·페이지·DB 배치 처리와 호출 예산을 사용한다. 인증키는 서버 전용이며 응답·문서 예시·로그에 포함하지 않는다. 이름·주소는 원본 한국어를 유지한다.

기본 수집 범위는 **서울(zcode=11)**이다. DATA_GO_KR_ZCODES로 설정한 지역만 정기 갱신하며 전국 데이터가 있다고 가정하지 않는다. 메타데이터는 최초 수집 후 하루 1회, 상태는 5분 주기를 목표로 수집한다. 상태 조회는 `period=10`으로 구간을 겹쳐 일반적인 실행 지연의 누락을 줄이고 원천 시각이 이전인 변경분으로 현재 값을 되돌리지 않는다. 상태는 변경분 병합이며 누락 항목을 삭제하지 않는다. 전체 지역 메타데이터 스냅샷의 삭제·무효 항목은 유효 충전기 수에서 제외한다.

페이지 종료는 누적 수신 건수가 `totalCount`에 도달했는지로 판단한다. 환경공단 응답의 `numOfRows`가 마지막 페이지의 실제 반환 건수로 줄어들어도 이를 새 페이지 크기로 해석하지 않는다. 첫 실제 페이지의 크기는 서버 상한과 남은 호출 예산 추정에만 사용한다. 전체 건수를 알 수 없으면 빈 종료 페이지까지 확인하며, 메타데이터의 건수 변경·중복·누락은 기존 캐시 교체 전에 차단한다. 각 페이지를 임시 파일에 보관하고 전체 수신·검증이 끝난 뒤 JPA 250건 배치를 하나의 트랜잭션으로 반영한다.

01~04 충전소 조회 API는 `collection_state`의 메타데이터 수집 성공 기록을 확인한 다음 PostgreSQL에서 조회한다. 첫 수집이 끝나기 전에는 503이고, 성공한 기존 캐시가 있으면 이후 갱신 실패에도 이를 조회한다. GET 요청은 수집을 강제로 실행하지 않는다. 05 이벤트 저장은 캐시가 준비되지 않아도 가능하지만 `matchedStationId`가 null일 수 있으며, 06~08 차량 기록 API는 충전소 수집 성공 여부에 직접 의존하지 않는다. 목록·추천은 DB에서 주변 사각 범위 후보를 먼저 좁힌 뒤 정확한 반경·정렬·페이지 처리를 수행하며, 이벤트 매칭도 100m 후보만 조회한다.

개발계정 하루 한도는 1,000회로 가정하며 앱 예산은 기본 900회로 100회 여유를 둔다. 일별 호출량·성공·재시도 시각을 DB에 저장하고 DB 잠금으로 중복 실행을 막는다. 실제 페이지·지역 수에 따라 요청을 차감하고 다음 페이지의 남은 예산을 검사한다. 지역당 상태 1페이지라면 한 지역은 하루 약 288회, 3개 지역은 약 864회이며 메타데이터·실패 비용이 추가된다. 여러 페이지이면 더 많은 호출이 필요하다.

예산 부족·실패 시 보수적으로 재시도를 미루므로 5분 최신성을 보장하지 않는다. 기존 자료가 있으면 마지막 관측 시각과 함께 제공하고 최초 자료가 없으면 503이다. 장시간 중단 중 발생한 상태 변경은 일일 메타데이터 수집 때 복구될 수 있다. statusObservedAt은 실제 관측 시각, statusUpdatedAt은 원천 변경 시각, fetchedAt·checkedAt은 이번 요청 처리 시각이다. 변경분에 없는 충전기의 관측 시각을 새로 만들지 않는다. 따라서 상태가 오래 유지되어 변경분에 포함되지 않은 충전기도 관측 유효기간이 지나면 길 안내가 차단될 수 있다.

긴 다운로드가 초기 페이지를 실제보다 신선하게 표시하지 않도록 공통 관측 시각은 수집 시작 시각으로 보수적으로 잡는다. 수집 실패 로그에는 task·region·page·누적 건수·응답 건수·stage·고정된 reason 코드를 남기며 인증키·요청 URL·외부 응답 본문·임의 예외 메시지는 기록하지 않는다. 예를 들어 `EMPTY_PAGE_BEFORE_TOTAL`은 전체 건수에 도달하기 전에 빈 페이지를 받은 경우다.

재시도 시각 `retry_after`와 호출 예산은 DB에 유지된다. 버그 수정 배포나 재시작만으로 기존 재시도 대기가 초기화되지는 않으며, 일반 실패는 최대 6시간, 예산 소진은 다음 서울 날짜까지 기다릴 수 있다. 배포 후에는 `collection_state`의 `succeeded_at`, `retry_after`, `consecutive_failures`와 `Collector metadata ... completed` 로그를 확인한다. health UP은 최초 수집 완료를 뜻하지 않는다.

Mock 이벤트는 영속 저장되지만 실제 방문 집계에 포함하지 않는다. 현재 실제 검증 경로가 없어 일반 요청의 visitCount는 알 수 없으면 null이다. 실제 방문 집계 구조는 검증된 REAL START만 사용자·충전소·서울 날짜 하루 1회로 계산하도록 분리되어 있다. 같은 키를 다른 앱에서도 사용하면 외부 전체 호출량을 따로 고려해야 한다.

## 1차 제외 범위

- 충전 요금(REQ_ST_007): 1차 통과 후 별도 계약. feePerKwh·feeAvailable 없음.
- 주행 중 안내·음성 브리핑·거점 등록·시간대 점유 이력: 주차 화면 MVP 밖 기능.
- 회원가입·로그인·어드민·차종 제원 API: 현재 구현 범위 밖 기능.
- 충전 권장 여부·다음 충전일 서버 계산: 프론트 책임.
- 실제 AWS 리소스 생성·배포, 이미지 레지스트리 게시·자동 릴리스: 수행하지 않음. 단일 EC2용 CD 설정은 아래에 제공한다.

## 로컬 실행·DB 이전·검증

Docker Desktop Linux 엔진, Docker Compose, Python 3가 필요하다. 루트에서 초기화하면 Git에서 제외되는 `.env`가 만들어지고 앱·관리자 DB 비밀번호를 각각 안전한 난수로 생성한다. 기존 파일의 실제 값은 보존하고 누락 키만 추가한다. 로컬은 `APP_DOMAIN=localhost`, `HTTPS_PORT=8443`으로 시작한다.

```powershell
python scripts/lib/env.py init --file .env --example .env.example --mode local
# .env의 DATA_GO_KR_SERVICE_KEY=needed를 실제 키로 채운 뒤 실행
python scripts/lib/env.py validate --file .env --mode local
```

| 변수 | 예제·기본값 | 용도 |
| --- | --- | --- |
| COMPOSE_PROJECT_NAME | altercharge | Compose 프로젝트·볼륨 이름 |
| SPRING_PROFILES_ACTIVE | prod | 컨테이너 실행 프로필 |
| APP_DOMAIN | 15-164-244-37.sslip.io / 로컬 localhost | 운영 호스트 이름만 입력. 프로토콜·포트 제외 |
| APP_PORT | 8080 | 앱 내부 포트 |
| HTTP_PORT / HTTPS_PORT | 80 / 443 | 운영 외부 포트. 로컬 초기화는 HTTPS 8443 |
| AWS_REGION | ap-northeast-2 | EC2 준비 시 사용할 기본 리전 |
| POSTGRES_DB | altercharge | 앱 DB |
| POSTGRES_USER | altercharge | 비관리자 앱 역할. postgres와 달라야 함 |
| POSTGRES_PASSWORD | 예제 needed / 초기화 시 자동 생성 | 앱 계정 비밀번호 |
| POSTGRES_ADMIN_PASSWORD | 예제 needed / 초기화 시 별도 생성 | 기존 권한 분리를 유지하는 DB 초기화 관리자 비밀번호 |
| POSTGRES_PORT | 5432 | DB 내부 포트 |
| DATA_GO_KR_BASE_URL | https://apis.data.go.kr/B552584/EvCharger | HTTPS 원본 API 주소 |
| DATA_GO_KR_SERVICE_KEY | needed | 서버 전용 실제 키 입력 전 실행 불가 |
| DATA_GO_KR_DATA_TYPE | JSON | 환경공단 원본 요청 형식. 운영 .env는 사용자가 직접 변경 |
| DATA_GO_KR_ZCODES | 11 | 수집 지역 쉼표 목록 |
| DATA_GO_KR_STATUS_REFRESH_SECONDS | 300 | 상태 수집 간격, 최소 5분 |
| DATA_GO_KR_INFO_REFRESH_SECONDS | 86400 | 메타데이터 수집 간격, 최소 하루 |
| DATA_GO_KR_ENABLED | true | 수집 활성화. 격리된 검증 환경에서만 false |
| DATA_GO_KR_NUM_OF_ROWS | 9999 | 한 요청 행 수, 10~9999 |
| DATA_GO_KR_DAILY_BUDGET | 900 | 앱 하루 최대 호출 수, 최대 900 |
| DATA_GO_KR_METADATA_BUDGET | 100 | 메타데이터 하루 예산 |
| SPRING_JPA_HIBERNATE_DDL_AUTO | validate | Flyway가 만든 스키마와 엔티티 검증 |
| STATION_STATUS_MAX_AGE_MINUTES | 15 | 길 안내 충전기 관측 유효기간, 1~1440분 |
| HISTORY_RETENTION_DAYS | 56 | 스냅샷 보관기간, 1~56일 |
| HISTORY_MAX_FUTURE_MINUTES | 5 | 저장 시각의 미래 허용 범위, 0~60분 |
| FLYWAY_ADOPT_LEGACY_SCHEMA | false | 아래 기존 DB 이전 때만 일시 사용 |

키는 디코딩 또는 한 번 URL 인코딩된 형태를 사용할 수 있다. `.env`·개인키·덤프는 Git에 넣지 않는다. 앱 DB 사용자는 해당 DB 스키마 권한만 갖고 관리자와 분리한다. dotenv는 셸 코드로 실행하지 않으며 여러 줄 값과 변수 참조는 사용하지 않는다. 비밀번호 등에 `$`가 있으면 작은따옴표로 감싼 리터럴 값을 사용한다. Compose를 직접 실행할 때 같은 이름의 셸 환경변수를 별도로 내보내지 않는다.

```bash
python3 scripts/lib/env.py validate --file .env --mode local
docker compose --env-file .env -f compose.yaml up -d --build --wait
docker compose --env-file .env -f compose.yaml ps
docker compose --env-file .env -f compose.yaml logs --tail=100 app
docker compose --env-file .env -f compose.yaml logs --tail=100 caddy
docker compose --env-file .env -f compose.yaml down
```

Windows에서는 `python3` 대신 `python`을 사용할 수 있다. 앱은 PostgreSQL health 이후, Caddy는 앱 health 이후 시작한다. `down`은 DB·Caddy CA named volume을 유지한다. 볼륨 삭제 옵션은 사용하지 않는다. 계정 초기화는 새 DB 볼륨의 첫 실행 때만 적용하므로 `.env` 암호만 바꿔도 기존 계정의 암호가 바뀌지는 않는다. 기존 볼륨이 있다면 기존 DB 비밀번호와 계정명을 유지한다.

Flyway가 DB 변경을 관리하고 Hibernate는 ddl-auto=validate로 검증한다. 새 DB는 별도 조작 없이 V1부터 적용한다. 이전 2개 API 단계의 기존 4개 테이블(stations, chargers, collection_state, api_daily_usage) DB는 먼저 백업하고 해당 구조가 예상한 기존 스키마인지 확인한다. Flyway 이력이 없는 기존 DB를 의도적으로 인수할 때만 FLYWAY_ADOPT_LEGACY_SCHEMA=true로 한 번 기동한다. baseline version 0 이후 V1과 V2가 적용되어 캐시를 유지하고 기록 테이블·시드를 추가한다. 성공 후 false로 되돌린다. 기본값은 false이며 임의의 비어 있지 않은 DB를 자동 인수하지 않는다.

스냅샷은 서울 날짜 기준 최근 56일만 조회·저장하며 매일 03:10에 보관기간 밖 기록을 정리한다. 삭제 기준은 HISTORY_RETENTION_DAYS 설정을 따른다. 이벤트 멱등성·방문 기록은 별도 기록이며 스냅샷 정리와 혼동하지 않는다.

로컬 HTTPS는 Caddy tls internal로 발급한다. 공개 루트 CA를 복사하고 요청별로 신뢰할 수 있다.

```powershell
docker compose --env-file .env -f compose.yaml cp caddy:/data/caddy/pki/authorities/local/root.crt ./local-ca.crt
curl.exe --cacert ./local-ca.crt https://localhost:8443/actuator/health
curl.exe --cacert ./local-ca.crt https://localhost:8443/v3/api-docs
```

브라우저에서도 사용할 때는 이 로컬 Caddy의 CA인지 확인한 뒤 현재 사용자 저장소에 등록할 수 있다. 사용 후 certmgr.msc에서 해당 인증서를 확인해 제거한다. 별도 신뢰 저장소를 사용하는 브라우저에는 공개 CA를 따로 등록한다. 개인키는 복사하지 않는다.

```powershell
certutil -user -addstore Root .\local-ca.crt
```

[Caddy 로컬 TLS](https://caddyserver.com/docs/automatic-https#local-https)는 OS·브라우저·Android에서 자동으로 신뢰되지 않는다. Android Automotive 에뮬레이터의 10.0.2.2와 localhost는 서로 다른 호스트다. 현재 인증서를 다른 IP에 재사용하지 말고 실제 접근 호스트 이름에 맞는 인증서와 [Android 디버그 CA 설정](https://developer.android.com/privacy-and-security/security-config#debug-overrides)을 별도로 준비한다. TLS·호스트 이름 검증은 유지한다.

JDK 21 테스트·패키징:

```powershell
cd altercharger-server
.\gradlew.bat test --no-daemon '-Dspring.profiles.active=ci'
.\gradlew.bat bootJar --no-daemon -x test
```

Linux/macOS에서는 ./gradlew를 사용한다. ci 프로필과 가짜 JSON·시드로 실제 환경공단 호출·키·AWS 자격증명 없이 검증한다. CI는 main의 push·pull_request에서 테스트·JAR 패키징을 수행한다. HTTP 필드·범위 검증은 Bean Validation, 도메인 정책은 서비스, 외부 JSON 검증은 수신 경계에서 처리한다. Swagger 실제 생성과 정확한 8개 업무 operation은 로컬 스모크에서 확인한다. 작업하는 Codex의 한국어 Conventional Commits 규칙은 [AGENTS.md](AGENTS.md)에 둔다.

## 배포 상태

**이번 버그 수정의 push·실배포 미실행.** 공개 주소는 `https://15-164-244-37.sslip.io`다. 사용자 제공 운영 응답에서 최초 수집 실패에 따른 503을 확인했으며, 이번 수정의 검증 결과와 기존 운영 서비스의 상태를 구분한다. AWS 리소스·운영 `.env`·GitHub Secrets는 이번 작업에서 생성하거나 변경하지 않았다.

사용자가 먼저 EC2 `/opt/altercharge/.env`의 `DATA_GO_KR_DATA_TYPE=JSON`, `APP_DOMAIN=15-164-244-37.sslip.io`, `SPRING_PROFILES_ACTIVE=prod`와 기존 비밀값을 확인한다. 운영 `.env`는 사용자가 직접 수정하며 Codex와 CD는 수정·재생성·업로드하지 않는다. 준비 후 사용자가 `main`에 push하면 기존 CI 성공과 `DEPLOY_ENABLED=true` 조건에 따라 CD가 실행될 수 있다. 게이트가 없거나 `false`이면 실행되지 않는다. 운영 설정을 준비하기 전에 자동 배포를 트리거하지 않는다.

구성은 EC2 1대의 Docker Compose(app + PostgreSQL + Caddy)다. Ubuntu LTS, SSH 사용자 `ubuntu`, `/opt/altercharge`, 리전 `ap-northeast-2`를 기본으로 한다. GitHub Actions가 Java 21 실행 JAR를 빌드해 SHA별 staging으로 전송하고 EC2는 `Dockerfile.runtime`으로 JAR 실행 이미지만 만든다. 소스를 서버에서 컴파일하지 않는다.

EC2를 준비하고 저장소의 `.env.example`, `scripts/`, `docker/postgres/10-create-app-user.sh`를 서버 작업 디렉터리에 둔 뒤 최초 한 번 실행한다. 실행 전 코드와 호스트를 확인한다.

```bash
sudo bash scripts/bootstrap-ec2.sh
```

부트스트랩은 Docker Engine·Compose·rsync·curl·Python을 준비하고 새 EC2의 `/opt/altercharge/.env`에 신규 DB 비밀번호를 자동 생성한다. 기존 서버에서는 `.env`·비밀번호·DB 볼륨을 보존하며 이번 JSON 전환을 위해 부트스트랩을 다시 실행하지 않는다. 기존 DB 볼륨이 있는데 비밀번호가 없으면 자동 교체하지 않으므로 기존 값을 복구해야 한다. 최초 준비 때 Docker 그룹을 변경했다면 `ubuntu`로 다시 로그인한다. 운영 파일의 `APP_DOMAIN=15-164-244-37.sslip.io`, `DATA_GO_KR_DATA_TYPE=JSON`, 기존 `DATA_GO_KR_SERVICE_KEY`를 확인하고 검증한다. 로컬 `.env`를 서버로 복사하지 않는다.

```bash
python3 /opt/altercharge/scripts/lib/env.py validate --file /opt/altercharge/.env --mode production
```

운영 DNS는 EC2를 가리켜야 한다. 보안 그룹은 HTTP 80·HTTPS 443을 공개하고 SSH 22는 배포에 필요한 신뢰 범위로 제한한다. 앱 8080·DB 5432는 외부에 공개하지 않는다. Caddy는 실제 도메인으로 공인 TLS를 발급한다. Mock ID만 사용하는 쓰기 API에는 실제 개인정보를 넣지 않으며, 외부 공개 전 인증·접근통제와 Swagger 공개 정책을 정해야 한다.

GitHub 저장소에서 다음 값을 설정한 뒤 준비가 끝나면 `DEPLOY_ENABLED=true`로 전환한다. DB 비밀번호·환경공단 키는 GitHub가 아닌 EC2의 `.env`에만 둔다.

| 종류 | 이름 | 입력 |
| --- | --- | --- |
| Secret | EC2_HOST | 실제 EC2 공인 IP 또는 DNS |
| Secret | EC2_SSH_PRIVATE_KEY | 배포용 SSH 개인키 |
| Secret | EC2_SSH_KNOWN_HOSTS | 별도 신뢰 경로로 확인한 서버 호스트 공개키의 known_hosts 항목 |
| Variable | EC2_USER | ubuntu |
| Variable | DEPLOY_ENABLED | 최초 false, 준비 후 true |

기존 `CI`의 main push가 성공하면 현재 main의 정확한 SHA와 원본 저장소를 다시 확인한 뒤 CD가 실행된다. 수동 실행도 동일 SHA의 성공 CI가 필요하다. GitHub CLI 설치·로그인 후 개발자 PC에서 다음 한 줄로 요청할 수 있다(Git Bash/Linux/macOS).

```bash
./scripts/deploy.sh
```

서버는 SSH 호스트 키를 엄격히 검사하며 SHA별 허용 파일만 받는다. `.env`와 SSH 개인키는 전송 묶음에 포함하지 않는다. 최초에는 staged SHA를 명시하고, 이후에는 `pending.sha`를 사용하는 한 줄 재실행도 가능하다.

```bash
bash /opt/altercharge/staging/<40자리-SHA>/scripts/deploy-ec2.sh <40자리-SHA>
bash /opt/altercharge/scripts/deploy-ec2.sh
```

배포는 `flock` 잠금, 이전 정상 JAR·설정 백업(최근 2개), Compose `up -d --build --wait`, 공인 HTTPS `/actuator/health` 순서로 진행한다. 환경변수는 `/opt/altercharge/.env`만 사용한다. 실패하면 이전 앱·프록시 설정 복구를 시도하고 실패 코드로 종료한다. 첫 배포 실패에는 이전 정상본이 없어 앱·프록시만 중지한다. 첫 JSON 전환 후 이전 XML 전용 JAR로 복구하면 현재 운영 `.env`의 JSON 설정과 호환되지 않을 수 있으므로 운영자가 복구할 버전에 맞춰 형식을 수동 조정해야 한다. 스크립트는 `.env`를 자동 롤백하거나 덮어쓰지 않는다. DB 스키마도 자동 롤백하지 않으므로 마이그레이션은 이전 앱과 호환되게 작성하고 별도로 DB를 백업한다. `altercharge_postgres_data`, `altercharge_caddy_data`, `altercharge_caddy_config` 볼륨은 유지한다.

배포 후 [health](https://15-164-244-37.sslip.io/actuator/health), [Swagger UI](https://15-164-244-37.sslip.io/swagger-ui/index.html), [OpenAPI JSON](https://15-164-244-37.sslip.io/v3/api-docs)을 실제로 확인한다.

### 운영 메모리 확인

EC2 t3.micro는 2 vCPU·메모리 1GiB다. 운영 Compose에만 앱 544MiB·PostgreSQL 192MiB·Caddy 64MiB 제한을 두고 JVM `-Xms64m -Xmx224m`, Metaspace 160MiB, Direct memory 32MiB, Code cache 64MiB, 스택 512KiB와 Serial GC·OOM 즉시 종료를 적용한다. 운영 Hikari 연결 풀은 최대 3·최소 유휴 0이다. PostgreSQL은 shared_buffers 32MB·work_mem 1MB·maintenance_work_mem 16MB·max_connections 20을 사용한다. 로컬 Compose와 CI에는 이 자원 제한을 적용하지 않는다.

이는 메모리 사용량을 낮추기 위한 초기값이며 **t3.micro 운영에서 장시간 수집·동시 요청·OOM 안정성은 아직 실측하지 않았다**. 사용자가 배포 후 EC2에서 아래 명령으로 메모리와 재시작 여부를 확인한다. 로그나 상태를 공유할 때 운영 비밀값은 포함하지 않는다.

```bash
docker stats --no-stream
free -h
docker compose --env-file /opt/altercharge/.env -f /opt/altercharge/current/compose.prod.yaml ps
docker compose --env-file /opt/altercharge/.env -f /opt/altercharge/current/compose.prod.yaml logs --tail=80 app
curl --fail --silent --show-error https://15-164-244-37.sslip.io/actuator/health
```

OOM이 발생하면 JSON 페이지·DB 배치·중복 적재와 연결 풀을 먼저 점검한다. swap 추가나 인스턴스 변경은 사용자가 별도로 판단하며 이번 작업에서는 원격 자원·swap을 변경하지 않는다.
