# AlterCharge 백엔드 API 명세서

> **기준:** 요구사항정의서 v0.1 · 수정일 2026-11-10 · MVP 범위: 주차 화면  
> **문서 상태:** API 초안 — REST URL과 JSON은 요구사항을 바탕으로 제안한 인터페이스이며 확정된 서버 API가 아닙니다.  
> **검토 규모:** 요구사항 48건 (우선순위 상 34건 / 하 14건)

## 목차

- [구현 전제](#구현-전제)
- [요청 데이터 표기법](#요청-데이터-표기법)
- [API 목록](#api-목록)
- [01. 주변 충전소 목록·검색](#api-01)
- [02. 충전소 상세·충전기 상태](#api-02)
- [03. 추천 충전소 1곳 조회](#api-03)
- [04. 길 안내 전 충전소 상태 확인](#api-04)
- [05. 충전 시작·완료 이벤트 기록](#api-05)
- [06. 차량 상태·누적 주행거리 저장](#api-06)
- [07. 최근 차량 기록 조회](#api-07)
- [08. 최근 충전 이력 조회](#api-08)
- [백엔드 내부 작업](#백엔드-내부-작업)
- [원문에서 확인되지 않은 사항 / 확정 필요](#원문에서-확인되지-않은-사항--확정-필요)
- [근거 요구사항 매핑](#근거-요구사항-매핑)

---

## 구현 전제

- MVP는 주차 화면만 구현. 주행 중 안내·음성 브리핑·거점 등록·시간대 점유 이력은 요구사항에 MVP 제외로 기재됨.
- 일반 사용자 ID mock-user-001, 관리자 ID mock-admin-001 및 차량 ID mock-vehicle-001은 서버 시드(Mock) 데이터로 미리 생성. 실제 회원가입/로그인/어드민 화면 API는 이번 요구사항에 정의되지 않아 명세에서 제외.
- 차종 제원 조회는 신규 요구사항에 존재하지 않으므로 P0 API에서 삭제. 배터리 총용량이 필요해질 때만 공식 제원에서 수동 확인한 2개 사양을 정적 JSON으로 하드코딩하는 선택안을 사용하고, 자동 외부/내부 제원 API가 확보되었다고 가정하지 않음.
- 앱 로컬 DB를 사용하지 않는다는 기존 협업 가정을 반영해 차량 기록과 충전 이벤트 보관용 API를 제안. 단, 원문은 주행거리 기록과 다음 충전일 계산을 프론트엔드로 분류하며, 네트워크가 끊겨도 판정 갱신을 요구한다. 프론트 메모리 캐시·최소 로컬 지속 저장 여부는 별도 결정 필요.
- GET은 URL Path/Query로 조회 조건을 전달하며 요청 Body가 없다. POST는 JSON Body를 사용. 모든 요청·응답 예시는 가상의 테스트 데이터.
- 충전기 가용 수는 충전소 주차면 빈자리 수가 아니라 외부 API에서 충전 대기(코드 2) 상태인 충전기 수. 외부 데이터 갱신 지연·상태 미확인을 실제 빈자리로 해석하면 안 됨.

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

**구분:** P0 / 서버·통합  
**연결 요구사항:** REQ_ST_001, REQ_SL_001, REQ_SL_002, REQ_SL_004, REQ_SL_005, REQ_VS_006, REQ_CM_006

**목적:** 차량 현재 위치에서 반경 3km 내 충전소 목록을 반환한다. 검색은 충전소명·주소를 대상으로 하고, 거리·방문순 및 충전 규격·출력 범위 필터에 필요한 정보를 포함한다.

### REST URL

`GET /api/v1/stations`

### Query 파라미터

| 파라미터 | 예시 | 설명 |
| --- | --- | --- |
| `lat` | `37.5500` | 현재 차량 위치의 위도 |
| `lng` | `127.1400` | 현재 차량 위치의 경도 |
| `radiusKm` | `3` | 조회 반경(km). MVP 기준 3km |
| `keyword` | 빈 값 (`keyword=`) | 충전소명·주소 검색어. 빈 값이면 검색어 없음 |
| `page` | `0` | 조회 페이지 번호(예시: 0부터 시작) |
| `size` | `20` | 한 페이지 응답 개수 |

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

**주의:** 조회 반경은 요구사항대로 3km. 정렬·필터의 화면 동작은 프론트 담당이지만, 서버가 정렬·검색에 필요한 전체 필드를 제공한다. 페이지네이션을 사용한다면 전역 방문순 정렬은 서버에서 수행하거나 충분한 결과를 받아야 한다.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-02"></a>

## 02. 충전소 상세·충전기 상태

**구분:** P0 / 서버·통합  
**연결 요구사항:** REQ_ST_007, REQ_SL_001

**목적:** 선택한 충전소의 주소, 충전기별 규격·출력·상태·갱신 시각을 반환한다.

### REST URL

`GET /api/v1/stations/{stationId}`

### Path 파라미터

| 파라미터 | 예시 | 설명 |
| --- | --- | --- |
| `stationId` | `ST-001` | 조회할 충전소 ID |

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
  "feePerKwh": null,
  "feeAvailable": false,
  "statusObservedAt": "2026-11-11T01:30:00+09:00"
}
```

**주의:** 요금은 요구사항에 있으나 첨부 한국환경공단 OpenAPI 가이드의 getChargerInfo/getChargerStatus에는 단가 필드가 확인되지 않는다. 요금 출처가 확정되기 전까지 null로 반환한다. 충전기 상태는 충전 대기(코드 2)만 가용으로 계산한다.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-03"></a>

## 03. 추천 충전소 1곳 조회

**구분:** P0 / 백엔드  
**연결 요구사항:** REQ_ST_005, REQ_ST_006, REQ_ST_007

**목적:** 현재 위치와 프론트 충전 권장 판정값을 입력받아 충전소 1곳을 추천한다.

### REST URL

`GET /api/v1/stations/recommendation`

### Query 파라미터

| 파라미터 | 예시 | 설명 |
| --- | --- | --- |
| `lat` | `37.5500` | 현재 차량 위치의 위도 |
| `lng` | `127.1400` | 현재 차량 위치의 경도 |
| `chargeRecommended` | `true` | 프론트가 계산한 충전 권장 여부. true이면 급속 충전소만 후보 |

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

**주의:** 후보: 반경 3km, 가용≥1, 운영중지·점검중 제외. chargeRecommended=true이면 급속만 후보. 방문 횟수 내림차순 → 거리 오름차순. 후보가 없으면 3km 내 최단거리 충전소로 대체. 다음 충전일·충전 권장은 프론트에서 계산하며 서버는 요청값을 사용한다.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-04"></a>

## 04. 길 안내 전 충전소 상태 확인

**구분:** P0 / 통합  
**연결 요구사항:** REQ_EX_004, REQ_BR_004, REQ_BR_005

**목적:** 프론트가 내비 경로를 요청하기 직전, 해당 충전소의 최신 상태를 확인해 운영중지·점검중이면 안내를 막는다.

### REST URL

`GET /api/v1/stations/{stationId}/navigation-check`

### Path 파라미터

| 파라미터 | 예시 | 설명 |
| --- | --- | --- |
| `stationId` | `ST-001` | 길 안내 전 상태를 확인할 충전소 ID |

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

**주의:** 백엔드는 상태만 확인한다. 목적지 변경과 경로 계산은 NaviHelper를 사용하는 프론트 책임. 이미 선택한 충전소의 상태 변경도 확인해야 한다. 확인 실패 시 허용/차단 정책은 기획 확인 필요.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-05"></a>

## 05. 충전 시작·완료 이벤트 기록

**구분:** P0 / 저장 방식 추가 설계  
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

**주의:** REQ_ST_008: 충전 시작 위치가 충전소 반경 100m 이내이고, 같은 충전소 하루 최대 1회. MVP 차량 충전 상태는 Mock이므로 이벤트도 시연용 입력임을 표시. 한 이벤트가 여러 충전소 반경에 해당할 때의 우선순위는 미정.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-06"></a>

## 06. 차량 상태·누적 주행거리 저장

**구분:** P0 / 로컬 DB 미사용 가정  
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

### 응답 JSON

```json
{
  "snapshotId": "SNAP-001",
  "saved": true,
  "recordedAt": "2026-11-11T01:30:00+09:00"
}
```

**주의:** 원문 REQ_JD_002는 프론트 구현으로 지정되어 있으며 로컬 DB 금지 요구는 스프레드시트에 없다. 이 API는 대화에서 정한 서버 저장 가정을 적용한 추가 설계. drivableRangeKm의 실제 확보 방법은 SDK/데이터 연동 확인 필요.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-07"></a>

## 07. 최근 차량 기록 조회

**구분:** P0 / 로컬 DB 미사용 가정  
**연결 요구사항:** REQ_JD_002, REQ_LG_002, REQ_CM_006

**목적:** 서버에 저장된 최근 8주 이하 차량 기록을 반환하여 프론트가 일별 주행거리·평균·다음 충전일을 계산하게 한다.

### REST URL

`GET /api/v1/vehicles/snapshots`

### Query 파라미터

| 파라미터 | 예시 | 설명 |
| --- | --- | --- |
| `vehicleId` | `mock-vehicle-001` | 차량 ID(Mock) |
| `from` | `2026-10-28` | 조회 시작일(YYYY-MM-DD) |
| `to` | `2026-11-11` | 조회 종료일(YYYY-MM-DD) |

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

**주의:** 14일 미만 기록이면 일평균·예비거리·잔여일수는 산출하지 않는다. 운행하지 않은 날(0km)과 미수집일을 구분해야 하므로 전체 날짜별 수집 여부를 클라이언트에서 판단할 수 있도록 응답 확장 필요. 서버 저장소만으로 완전 오프라인 갱신은 불가능하다.

[↑ 목차로 돌아가기](#목차)

---

<a id="api-08"></a>

## 08. 최근 충전 이력 조회

**구분:** P0 / 로컬 DB 미사용 가정  
**연결 요구사항:** REQ_VS_005, REQ_LG_002

**목적:** 마지막 충전 완료 시각과 완료 당시 누적 주행거리를 제공한다.

### REST URL

`GET /api/v1/charging-events/latest`

### Query 파라미터

| 파라미터 | 예시 | 설명 |
| --- | --- | --- |
| `vehicleId` | `mock-vehicle-001` | 차량 ID(Mock) |

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

**주의:** 충전완료 이벤트가 없거나 Mock 데이터만 있다면 null 또는 Mock 출처 표시가 필요하다. 계산된 충전 후 주행거리는 누적 오도미터의 차분이다.

[↑ 목차로 돌아가기](#목차)