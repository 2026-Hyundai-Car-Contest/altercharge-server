# AlterCharge 후속 API 제안서 (구현 이전 초안 보관)

> 기존 8개 API 제안을 보관한 문서다. 현재 구현된 API 2개와 확정된 계약·실행 방법은 [README](../README.md)를 기준으로 한다. 아래 mock 시드·방문 횟수·추천·차량·이벤트 내용은 현재 서버 구현을 설명하지 않는다.

> **기준:** 요구사항정의서 v0.1 · 수정일 2026-11-10 · MVP 범위: 주차 화면<br>
> **문서 상태:** API 초안 — REST URL과 JSON은 요구사항을 바탕으로 제안한 인터페이스이며 확정된 서버 API가 아닙니다.<br>
> **검토 규모:** 요구사항 48건 (우선순위 상 34건 / 하 14건)

## 목차

- [구현 전제](#구현-전제)
- [API 목록](#api-목록)
- [01. 주변 충전소 목록·검색](#api-01)
- [02. 충전소 상세·충전기 상태](#api-02)
- [03. 추천 충전소 1곳 조회](#api-03)
- [04. 길 안내 전 충전소 상태 확인](#api-04)
- [05. 충전 시작·완료 이벤트 기록](#api-05)
- [06. 차량 상태·누적 주행거리 저장](#api-06)
- [07. 최근 차량 기록 조회](#api-07)
- [08. 최근 충전 이력 조회](#api-08)
- [1차 제외 · 후속 개발 항목](#후속-개발)

---

## 구현 전제

- MVP는 주차 화면만 구현. 주행 중 안내·음성 브리핑·거점 등록·시간대 점유 이력은 요구사항에 MVP 제외로 기재됨.
- 일반 사용자 ID mock-user-001, 관리자 ID mock-admin-001 및 차량 ID mock-vehicle-001은 서버 시드(Mock) 데이터로 미리 생성. 실제 회원가입/로그인/어드민 화면 API는 이번 요구사항에 정의되지 않아 명세에서 제외.
- 차종 제원 조회는 신규 요구사항에 존재하지 않으므로 P0 API에서 삭제. 배터리 총용량이 필요해질 때만 공식 제원에서 수동 확인한 2개 사양을 정적 JSON으로 하드코딩하는 선택안을 사용하고, 자동 외부/내부 제원 API가 확보되었다고 가정하지 않음.
- 앱 로컬 DB를 사용하지 않는다는 기존 협업 가정을 반영해 차량 기록과 충전 이벤트 보관용 API를 제안. 단, 원문은 주행거리 기록과 다음 충전일 계산을 프론트엔드로 분류하며, 네트워크가 끊겨도 판정 갱신을 요구한다. 프론트 메모리 캐시·최소 로컬 지속 저장 여부는 별도 결정 필요.
- GET은 URL Path/Query로 조회 조건을 전달하며 요청 Body가 없다. POST는 JSON Body를 사용. 모든 요청·응답 예시는 가상의 테스트 데이터.
- 충전기 가용 수는 충전소 주차면 빈자리 수가 아니라 외부 API에서 충전 대기(코드 2) 상태인 충전기 수. 외부 데이터 갱신 지연·상태 미확인을 실제 빈자리로 해석하면 안 됨.
- **REQ_ST_005 예외:** 주행 기록이 14일 미만이면 화면에 **「기록 수집 중」**을 표시하고, 프론트는 **SOC ≤ 30%**일 때만 충전을 권장한다. 이 판정값을 API 03의 `chargeRecommended`로 전달한다. 14일 이상이면 기존 충전 권장 판정 로직을 사용한다.
- **REQ_ST_007 요금:** 충전 요금은 **1차 개발 범위에서 제외**되었으며, **1차 통과 후 추가 개발 항목**으로 관리한다. 따라서 현재 API 02 응답에는 요금 필드를 포함하지 않는다.

---


### 데이터 타입 기준 (Kotlin)

- 아래 표의 **Kotlin 타입**은 프론트 DTO 작성 시 사용할 **제안 타입**입니다. 실제 서버와의 타입 계약은 구현 전에 확정해야 합니다.
- `String` ↔ JSON 문자열, `Int` ↔ 정수, `Double` ↔ 소수 포함 숫자, `Boolean` ↔ 참/거짓, `List<T>` ↔ JSON 배열, `?` ↔ `null` 허용을 의미합니다.
- 날짜·시각은 JSON에서 모두 `String`으로 전달하며, `recordedAt`·`checkedAt` 같은 시각은 **ISO 8601 오프셋 포함** (`2026-11-11T01:30:00+09:00`), `from`·`to`는 `YYYY-MM-DD` 형식을 사용합니다. 필요하면 Kotlin 내부에서 `OffsetDateTime`/`LocalDate`로 파싱할 수 있습니다.
- GET의 Query 값은 URL에서 모두 텍스트로 인코딩되지만, Retrofit `@Query` / `@Path`의 Kotlin 인수는 표에 적힌 `Double`, `Int`, `Boolean`, `String` 타입으로 선언할 수 있습니다.
- `maxOutputKw`·`outputKw`는 출력값에 소수나 누락값이 올 수 있어 `Double?`로 표기했습니다. 기존 예시 JSON의 `100` 같은 정수 표기는 **그대로 유지**했습니다.
- `maxOutputKw`, `drivableRangeKm` 등 데이터가 없을 수 있는 필드의 `?`는 프론트의 null 처리에 도움이 되도록 표시한 제안입니다. **일부 예외 상황의 null 응답 정책은 아직 미확정**이므로 확정 사양으로 간주하지 않습니다.
- `StationSummaryDto`, `ChargerDto`, `RecommendedStationDto`, `VehicleSnapshotDto`는 **설명용 Kotlin 객체 클래스명**으로, 실제 코드에 존재한다고 가정하지 않습니다.

---

## API 목록

| 번호 | Method | Endpoint | 기능 | 구분 |
| --- | --- | --- | --- | --- |
| 01 | `GET` | `/api/v1/stations` | [주변 충전소 목록·검색](#api-01) | P0 / 서버·통합 |
| 02 | `GET` | `/api/v1/stations/{stationId}` | [충전소 상세·충전기 상태](#api-02) | P0 / 서버·통합 |
| 03 | `GET` | `/api/v1/stations/recommendation` | [추천 충전소 1곳 조회](#api-03) | P0 / 백엔드 |
| 04 | `GET` | `/api/v1/stations/{stationId}/navigation-check` | [길 안내 전 충전소 상태 확인](#api-04) | P0 / 통합 |
| 05 | `POST` | `/api/v1/charging-events` | [충전 시작·완료 이벤트 기록](#api-05) | P0 / 저장 방식 추가 설계 |
| 06 | `POST` | `/api/v1/vehicles/snapshots` | [차량 상태·누적 주행거리 저장](#api-06) | P0 / 로컬 DB 미사용 가정 |
| 07 | `GET` | `/api/v1/vehicles/snapshots` | [최근 차량 기록 조회](#api-07) | P0 / 로컬 DB 미사용 가정 |
| 08 | `GET` | `/api/v1/charging-events/latest` | [최근 충전 이력 조회](#api-08) | P0 / 로컬 DB 미사용 가정 |

---

<a id="api-01"></a>

## 01. 주변 충전소 목록·검색

**구분:** P0 / 서버·통합<br>
**연결 요구사항:** REQ_ST_001, REQ_SL_001, REQ_SL_002, REQ_SL_004, REQ_SL_005, REQ_VS_006, REQ_CM_006

**목적:** 차량 현재 위치에서 반경 3km 내 충전소 목록을 반환한다. 검색은 충전소명·주소를 대상으로 하고, 거리·방문순 및 충전 규격·출력 범위 필터에 필요한 정보를 포함한다.

### REST URL

`GET /api/v1/stations`

### Query 파라미터

| 파라미터 | Kotlin 타입 | 예시 | 설명 |
| --- | --- | --- | --- |
| `lat` | `Double` | `37.5500` | 현재 차량 위치의 위도 |
| `lng` | `Double` | `127.1400` | 현재 차량 위치의 경도 |
| `radiusKm` | `Int` | `3` | 조회 반경(km). MVP 기준 3km |
| `keyword` | `String` | 빈 값 (`keyword=`) | 충전소명·주소 검색어. 빈 값이면 검색어 없음 |
| `page` | `Int` | `0` | 조회 페이지 번호(예시: 0부터 시작) |
| `size` | `Int` | `20` | 한 페이지 응답 개수 |

### 요청 예시

**HTTP GET — 요청 Body 없음**

```http
GET /api/v1/stations?lat=37.5500&lng=127.1400&radiusKm=3&keyword=&page=0&size=20
```

### 응답 JSON

```json
{
  "items": [
    {
      "stationId": "ST-001",
      "name": "예시 충전소",
      "address": "서울특별시 (예시)",
      "lat": 37.551,
      "lng": 127.141,
      "distanceMeters": 150,
      "availableCount": 2,
      "totalValidCount": 4,
      "chargerTypes": [
        "DC_COMBO"
      ],
      "maxOutputKw": 100,
      "visitCount": 2,
      "statusObservedAt": "2026-11-11T01:30:00+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "hasNext": false,
  "fetchedAt": "2026-11-11T01:31:00+09:00"
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `items` | `array<object>` | `List<StationSummaryDto>` | 충전소 목록 |
| `items[].stationId` | `string` | `String` | 충전소 식별자 |
| `items[].name` | `string` | `String` | 충전소명 |
| `items[].address` | `string` | `String` | 충전소 주소 |
| `items[].lat` | `number` | `Double` | 충전소 위도 |
| `items[].lng` | `number` | `Double` | 충전소 경도 |
| `items[].distanceMeters` | `integer` | `Int` | 차량 위치로부터 거리(m) |
| `items[].availableCount` | `integer` | `Int` | 이용 가능한 충전기 수 |
| `items[].totalValidCount` | `integer` | `Int` | 유효한 전체 충전기 수 |
| `items[].chargerTypes` | `array<string>` | `List<String>` | 지원 커넥터 유형 목록 |
| `items[].maxOutputKw` | `number` | `Double?` | 최대 출력(kW); 출처에 값이 없을 가능성 고려 |
| `items[].visitCount` | `integer` | `Int` | 해당 사용자 방문 횟수 |
| `items[].statusObservedAt` | `string (date-time)` | `String` | 상태 관측 시각, ISO 8601 |
| `page` | `integer` | `Int` | 0부터 시작하는 페이지 |
| `size` | `integer` | `Int` | 페이지 크기 |
| `hasNext` | `boolean` | `Boolean` | 다음 페이지 존재 여부 |
| `fetchedAt` | `string (date-time)` | `String` | 서버 응답 데이터 조회 시각, ISO 8601 |

**주의:** 조회 반경은 요구사항대로 3km. 정렬·필터의 화면 동작은 프론트 담당이지만, 서버가 정렬·검색에 필요한 전체 필드를 제공한다. 페이지네이션을 사용한다면 전역 방문순 정렬은 서버에서 수행하거나 충분한 결과를 받아야 한다.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-02"></a>

## 02. 충전소 상세·충전기 상태

**구분:** P0 / 서버·통합<br>
**연결 요구사항:** REQ_ST_007, REQ_SL_001

**목적:** 선택한 충전소의 주소, 충전기별 규격·출력·상태·갱신 시각을 반환한다.

### REST URL

`GET /api/v1/stations/{stationId}`

### Path 파라미터

| 파라미터 | Kotlin 타입 | 예시 | 설명 |
| --- | --- | --- | --- |
| `stationId` | `String` | `ST-001` | 조회할 충전소 ID |

### 요청 예시

**HTTP GET — 요청 Body 없음**

```http
GET /api/v1/stations/ST-001
```

### 응답 JSON

```json
{
  "stationId": "ST-001",
  "name": "예시 충전소",
  "address": "서울특별시 (예시)",
  "availableCount": 2,
  "totalValidCount": 4,
  "chargers": [
    {
      "chargerId": "01",
      "connectorType": "DC_COMBO",
      "outputKw": 100,
      "status": "AVAILABLE",
      "statusUpdatedAt": "2026-11-11T01:30:00+09:00"
    },
    {
      "chargerId": "02",
      "connectorType": "DC_COMBO",
      "outputKw": 100,
      "status": "CHARGING",
      "statusUpdatedAt": "2026-11-11T01:29:00+09:00"
    }
  ],
  "statusObservedAt": "2026-11-11T01:30:00+09:00"
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `stationId` | `string` | `String` | 충전소 식별자 |
| `name` | `string` | `String` | 충전소명 |
| `address` | `string` | `String` | 충전소 주소 |
| `availableCount` | `integer` | `Int` | 이용 가능한 충전기 수 |
| `totalValidCount` | `integer` | `Int` | 유효한 전체 충전기 수 |
| `chargers` | `array<object>` | `List<ChargerDto>` | 충전기별 상세 데이터 |
| `chargers[].chargerId` | `string` | `String` | 충전기 식별자 (앞자리 0 보존) |
| `chargers[].connectorType` | `string` | `String` | 연결 규격 코드 (예: DC_COMBO) |
| `chargers[].outputKw` | `number` | `Double?` | 출력(kW); 데이터 미제공 가능 |
| `chargers[].status` | `string` | `String` | 충전기 상태 코드 문자열 |
| `chargers[].statusUpdatedAt` | `string (date-time)` | `String` | 해당 충전기 상태 갱신 시각 |
| `statusObservedAt` | `string (date-time)` | `String` | 충전소 상태 관측 시각 |

**주의:** 충전기 상태는 충전 대기(코드 2)만 가용으로 계산한다. **충전 요금(REQ_ST_007)은 1차 범위에서 제외**되었으므로 `feePerKwh`, `feeAvailable` 같은 필드는 이번 응답 계약에 포함하지 않는다. 요금은 1차 통과 후 별도 개발 범위에서 정의한다.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-03"></a>

## 03. 추천 충전소 1곳 조회

**구분:** P0 / 백엔드<br>
**연결 요구사항:** REQ_ST_005, REQ_ST_006, REQ_ST_007

**목적:** 현재 위치와 프론트 충전 권장 판정값을 입력받아 충전소 1곳을 추천한다.

### REST URL

`GET /api/v1/stations/recommendation`

### Query 파라미터

| 파라미터 | Kotlin 타입 | 예시 | 설명 |
| --- | --- | --- | --- |
| `lat` | `Double` | `37.5500` | 현재 차량 위치의 위도 |
| `lng` | `Double` | `127.1400` | 현재 차량 위치의 경도 |
| `chargeRecommended` | `Boolean` | `true` | 프론트가 계산한 충전 권장 여부. **기록 14일 미만이면 SOC ≤ 30% 기준**. true이면 급속 충전소만 후보 |

### 충전 권장 판정 기준 (`REQ_ST_005` 예외)

| 주행 기록 | 화면 표시 | 프론트의 `chargeRecommended` 계산 |
| --- | --- | --- |
| **14일 미만** | **기록 수집 중** | `socPercent <= 30` (30% 이하 `true`, 30% 초과 `false`) |
| **14일 이상** | 기존 판정 결과 표시 | 기존 주행 기록 기반 충전 권장 로직 사용 |

- **판정 위치:** 프론트(Kotlin). 백엔드는 `chargeRecommended`를 다시 계산하지 않고 전달받은 값으로 추천 대상을 선정한다.
- **데이터 출처:** API 07의 주행 기록과 차량의 최신 SOC를 이용한다. 14일 판정에 쓸 유효 기록일의 세부 집계 기준은 프론트·백엔드 협의가 필요하다.
- **예시:** 기록 12일 · SOC 30% → `true`, 기록 12일 · SOC 31% → `false`. 아래 요청 예시의 `true`는 첫 번째 상황에 해당할 수 있다.

### 요청 예시

**HTTP GET — 요청 Body 없음**

```http
GET /api/v1/stations/recommendation?lat=37.5500&lng=127.1400&chargeRecommended=true
```

### 응답 JSON

```json
{
  "station": {
    "stationId": "ST-001",
    "name": "예시 충전소",
    "distanceMeters": 150,
    "availableCount": 2,
    "visitCount": 2,
    "speedCategory": "FAST",
    "maxOutputKw": 100
  },
  "reasonCode": "VISITS_THEN_DISTANCE",
  "fallbackUsed": false,
  "statusObservedAt": "2026-11-11T01:30:00+09:00"
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `station` | `object` | `RecommendedStationDto` | 추천 충전소 정보; 후보 없음 처리 방식은 미정 |
| `station.stationId` | `string` | `String` | 추천 충전소 ID |
| `station.name` | `string` | `String` | 충전소명 |
| `station.distanceMeters` | `integer` | `Int` | 거리(m) |
| `station.availableCount` | `integer` | `Int` | 이용 가능한 충전기 수 |
| `station.visitCount` | `integer` | `Int` | 방문 횟수 |
| `station.speedCategory` | `string` | `String` | 충전 속도 분류 (예: FAST) |
| `station.maxOutputKw` | `number` | `Double?` | 최대 출력(kW); 데이터 미제공 가능 |
| `reasonCode` | `string` | `String` | 추천 사유 코드 (예: VISITS_THEN_DISTANCE) |
| `fallbackUsed` | `boolean` | `Boolean` | 대체 추천 사용 여부 |
| `statusObservedAt` | `string (date-time)` | `String` | 충전소 상태 관측 시각 |

**주의:** 후보: 반경 3km, 가용≥1, 운영중지·점검중 제외. `chargeRecommended=true`이면 급속만 후보. 방문 횟수 내림차순 → 거리 오름차순. 후보가 없으면 3km 내 최단거리 충전소로 대체. 다음 충전일·충전 권장은 프론트에서 계산하며 서버는 요청값을 사용한다. **기록이 14일 미만인 경우에도 위 SOC 30% 기준으로 계산한 값을 그대로 전달한다.**

[↑ 목차로 돌아가기](#목차)

---

<a id="api-04"></a>

## 04. 길 안내 전 충전소 상태 확인

**구분:** P0 / 통합<br>
**연결 요구사항:** REQ_EX_004, REQ_BR_004, REQ_BR_005

**목적:** 프론트가 내비 경로를 요청하기 직전, 해당 충전소의 최신 상태를 확인해 운영중지·점검중이면 안내를 막는다.

### REST URL

`GET /api/v1/stations/{stationId}/navigation-check`

### Path 파라미터

| 파라미터 | Kotlin 타입 | 예시 | 설명 |
| --- | --- | --- | --- |
| `stationId` | `String` | `ST-001` | 길 안내 전 상태를 확인할 충전소 ID |

### 요청 예시

**HTTP GET — 요청 Body 없음**

```http
GET /api/v1/stations/ST-001/navigation-check
```

### 응답 JSON

```json
{
  "stationId": "ST-001",
  "navigationAllowed": true,
  "reasonCode": "OK",
  "latestStatuses": [
    "AVAILABLE",
    "CHARGING"
  ],
  "checkedAt": "2026-11-11T01:31:00+09:00"
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `stationId` | `string` | `String` | 충전소 ID |
| `navigationAllowed` | `boolean` | `Boolean` | 길 안내 허용 여부 |
| `reasonCode` | `string` | `String` | 판정 사유 코드 (예: OK) |
| `latestStatuses` | `array<string>` | `List<String>` | 충전기 상태 문자열 목록 |
| `checkedAt` | `string (date-time)` | `String` | 상태 확인 시각 |

**주의:** 백엔드는 상태만 확인한다. 목적지 변경과 경로 계산은 NaviHelper를 사용하는 프론트 책임. 이미 선택한 충전소의 상태 변경도 확인해야 한다. 확인 실패 시 허용/차단 정책은 기획 확인 필요.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-05"></a>

## 05. 충전 시작·완료 이벤트 기록

**구분:** P0 / 저장 방식 추가 설계<br>
**연결 요구사항:** REQ_ST_008, REQ_VS_005, REQ_LG_002

**목적:** 앱이 감지하거나 MVP Mock으로 생성한 충전 시작·완료 이벤트를 서버에 저장한다. 충전 시작 이벤트의 위치로 방문 횟수 산정을 지원한다.

### REST URL

`POST /api/v1/charging-events`

### 요청 JSON Body

`Content-Type: application/json`

```json
{
  "eventId": "evt-001",
  "vehicleId": "mock-vehicle-001",
  "eventType": "START",
  "occurredAt": "2026-11-11T01:00:00+09:00",
  "lat": 37.5501,
  "lng": 127.1401,
  "socPercent": 35,
  "odometerKm": 20350.4
}
```

### 요청 JSON Body 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `eventId` | `string` | `String` | 이벤트 고유 ID |
| `vehicleId` | `string` | `String` | 사전 등록한 Mock 차량 ID |
| `eventType` | `string` | `String` | 충전 이벤트 유형: START 또는 END (코드 확정 필요) |
| `occurredAt` | `string (date-time)` | `String` | 이벤트 발생 시각, ISO 8601 |
| `lat` | `number` | `Double` | 이벤트 위치 위도 |
| `lng` | `number` | `Double` | 이벤트 위치 경도 |
| `socPercent` | `integer` | `Int` | 이벤트 시점의 배터리 잔량(%) |
| `odometerKm` | `number` | `Double` | 이벤트 시점의 누적 주행거리(km) |

### 응답 JSON

```json
{
  "eventId": "evt-001",
  "accepted": true,
  "matchedStationId": "ST-001",
  "visitCountAdded": 1,
  "dailyDuplicate": false
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `eventId` | `string` | `String` | 클라이언트가 보내는 충전 이벤트 고유 ID |
| `accepted` | `boolean` | `Boolean` | 서버 수락 여부 |
| `matchedStationId` | `string \| null` | `String?` | 위치로 매칭된 충전소 ID; 매칭 실패 시 null 가능 |
| `visitCountAdded` | `integer` | `Int` | 이번 이벤트로 추가된 방문 횟수 (0 또는 1) |
| `dailyDuplicate` | `boolean` | `Boolean` | 동일 일자·충전소 중복 방문 여부 |

**주의:** REQ_ST_008: 충전 시작 위치가 충전소 반경 100m 이내이고, 같은 충전소 하루 최대 1회. MVP 차량 충전 상태는 Mock이므로 이벤트도 시연용 입력임을 표시. 한 이벤트가 여러 충전소 반경에 해당할 때의 우선순위는 미정.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-06"></a>

## 06. 차량 상태·누적 주행거리 저장

**구분:** P0 / 로컬 DB 미사용 가정<br>
**연결 요구사항:** REQ_JD_002, REQ_VS_001, REQ_VS_004, REQ_CM_006

**목적:** 프론트가 읽어온 차량 상태를 서버에 보관한다. 날짜별 주행 기록과 최근 8주 조회를 위한 원천 데이터로 사용한다.

### REST URL

`POST /api/v1/vehicles/snapshots`

### 요청 JSON Body

`Content-Type: application/json`

```json
{
  "vehicleId": "mock-vehicle-001",
  "recordedAt": "2026-11-11T01:30:00+09:00",
  "socPercent": 62,
  "odometerKm": 20350.4,
  "drivableRangeKm": 235
}
```

### 요청 JSON Body 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `vehicleId` | `string` | `String` | 사전 등록한 Mock 차량 ID |
| `recordedAt` | `string (date-time)` | `String` | 차량 측정 시각, ISO 8601 |
| `socPercent` | `integer` | `Int` | 배터리 잔량(%) |
| `odometerKm` | `number` | `Double` | 누적 주행거리(km) |
| `drivableRangeKm` | `integer \| null` | `Int?` | 주행 가능 거리(km); 확보 불가 시 처리 협의 필요 |

### 응답 JSON

```json
{
  "snapshotId": "SNAP-001",
  "saved": true,
  "recordedAt": "2026-11-11T01:30:00+09:00"
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `snapshotId` | `string` | `String` | 저장된 차량 스냅샷 ID |
| `saved` | `boolean` | `Boolean` | 저장 성공 여부 |
| `recordedAt` | `string (date-time)` | `String` | 전달받은 측정 시각, ISO 8601 |

**주의:** 원문 REQ_JD_002는 프론트 구현으로 지정되어 있으며 로컬 DB 금지 요구는 스프레드시트에 없다. 이 API는 대화에서 정한 서버 저장 가정을 적용한 추가 설계. drivableRangeKm의 실제 확보 방법은 SDK/데이터 연동 확인 필요.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-07"></a>

## 07. 최근 차량 기록 조회

**구분:** P0 / 로컬 DB 미사용 가정<br>
**연결 요구사항:** REQ_JD_002, REQ_LG_002, REQ_CM_006

**목적:** 서버에 저장된 최근 8주 이하 차량 기록을 반환하여 프론트가 일별 주행거리·평균·다음 충전일을 계산하게 한다.

### REST URL

`GET /api/v1/vehicles/snapshots`

### Query 파라미터

| 파라미터 | Kotlin 타입 | 예시 | 설명 |
| --- | --- | --- | --- |
| `vehicleId` | `String` | `mock-vehicle-001` | 차량 ID(Mock) |
| `from` | `String` | `2026-10-28` | 조회 시작일(YYYY-MM-DD) |
| `to` | `String` | `2026-11-11` | 조회 종료일(YYYY-MM-DD) |

### 요청 예시

**HTTP GET — 요청 Body 없음**

```http
GET /api/v1/vehicles/snapshots?vehicleId=mock-vehicle-001&from=2026-10-28&to=2026-11-11
```

### 응답 JSON

```json
{
  "vehicleId": "mock-vehicle-001",
  "records": [
    {
      "recordedAt": "2026-11-10T23:59:00+09:00",
      "socPercent": 65,
      "odometerKm": 20332.0,
      "drivableRangeKm": 247
    },
    {
      "recordedAt": "2026-11-11T01:30:00+09:00",
      "socPercent": 62,
      "odometerKm": 20350.4,
      "drivableRangeKm": 235
    }
  ],
  "retentionDays": 56,
  "completeDayCount": 12
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `vehicleId` | `string` | `String` | 조회 대상 차량 ID |
| `records` | `array<object>` | `List<VehicleSnapshotDto>` | 주행·배터리 스냅샷 목록 |
| `records[].recordedAt` | `string (date-time)` | `String` | 차량 데이터 측정 시각 |
| `records[].socPercent` | `integer` | `Int` | 배터리 잔량(%) |
| `records[].odometerKm` | `number` | `Double` | 누적 주행거리(km) |
| `records[].drivableRangeKm` | `integer \| null` | `Int?` | 주행 가능 거리(km), SDK 확보 방식 미정 |
| `retentionDays` | `integer` | `Int` | 서버 기록 보관 일수 |
| `completeDayCount` | `integer` | `Int` | 자료가 완비된 일자 수. **14일 미만 예외 판정에 활용** (유효일 산정 기준 협의 필요) |

**주의 (`REQ_ST_005`):** **유효 주행 기록이 14일 미만이면** 일평균·예비거리·잔여일수는 산출하지 않고 프론트에 **「기록 수집 중」**을 표시한다. **충전 권장 판정은 중단하지 않고 현재 SOC가 30% 이하인 경우에만 `true`**로 판정하여 API 03의 `chargeRecommended`로 전달한다. 14일 이상부터 기존 계산 로직을 사용한다.

운행하지 않은 날(0km)과 미수집일을 구분해야 하므로 전체 날짜별 수집 여부를 클라이언트에서 판단할 수 있도록 응답 확장 필요. 서버 저장소만으로 완전 오프라인 갱신은 불가능하다.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-08"></a>

## 08. 최근 충전 이력 조회

**구분:** P0 / 로컬 DB 미사용 가정<br>
**연결 요구사항:** REQ_VS_005, REQ_LG_002

**목적:** 마지막 충전 완료 시각과 완료 당시 누적 주행거리를 제공한다.

### REST URL

`GET /api/v1/charging-events/latest`

### Query 파라미터

| 파라미터 | Kotlin 타입 | 예시 | 설명 |
| --- | --- | --- | --- |
| `vehicleId` | `String` | `mock-vehicle-001` | 차량 ID(Mock) |

### 요청 예시

**HTTP GET — 요청 Body 없음**

```http
GET /api/v1/charging-events/latest?vehicleId=mock-vehicle-001
```

### 응답 JSON

```json
{
  "vehicleId": "mock-vehicle-001",
  "lastChargeCompletedAt": "2026-11-10T21:10:00+09:00",
  "odometerAtLastChargeKm": 20310.0,
  "currentOdometerKm": 20350.4,
  "distanceSinceChargeKm": 40.4
}
```

### 응답 JSON 필드 타입

| 필드 | JSON 타입 | Kotlin 타입 | 설명 |
| --- | --- | --- | --- |
| `vehicleId` | `string` | `String` | 조회 대상 차량 ID |
| `lastChargeCompletedAt` | `string (date-time) \| null` | `String?` | 마지막 충전 완료 시각; 이력 없음이면 null |
| `odometerAtLastChargeKm` | `number \| null` | `Double?` | 충전 완료 당시 누적 주행거리(km) |
| `currentOdometerKm` | `number \| null` | `Double?` | 최근 측정된 누적 주행거리(km) |
| `distanceSinceChargeKm` | `number \| null` | `Double?` | 마지막 충전 후 주행거리(km) |

**주의:** 충전완료 이벤트가 없거나 Mock 데이터만 있다면 null 또는 Mock 출처 표시가 필요하다. 계산된 충전 후 주행거리는 누적 오도미터의 차분이다.

[↑ 목차로 돌아가기](#목차)

---

<a id="후속-개발"></a>

## 1차 제외 · 후속 개발 항목

### 충전 요금 (`REQ_ST_007`)

- **현재 상태:** 1차 개발 명세에서 제외. **1차 통과 후 추가 개발**로 관리한다.
- **이번 API 범위:** API 02 (`GET /api/v1/stations/{stationId}`)는 충전기 연결 타입·출력·상태 정보를 제공하고, 요금 관련 JSON 필드는 반환하지 않는다.
- **추후 결정:** 요금의 의미(기본 단가 `원/kWh` 또는 이용자별 예상 결제 금액), 신뢰할 수 있는 데이터 출처, 적용 사업자·회원 조건, 요금 미제공 시 표시 정책을 확정한 뒤 API 계약에 추가한다.

[↑ 목차로 돌아가기](#목차)
