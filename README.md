# AlterCharge 백엔드

Java 21 · Spring Boot 4.1.2 · Gradle Wrapper 9.8.2 · PostgreSQL. 구현된 업무 API는 충전소 조회 GET 2개다. 환경공단 XML을 정기 수집해 DB에 저장하고 앱 요청은 DB만 조회한다.

기존 8개 API 초안은 [후속 API 제안서](docs/api-proposals.md)에 보관했다. 구현 범위에 맞춰 `radiusKm`은 `Double`, 방문 데이터가 없는 `visitCount`는 `Int?` 및 `null`로 확정했다. 복합 규격 보존을 위해 상세 응답에 `connectorTypes`와 원본 코드를 추가했다. 추천·차량·이벤트·요금·인증 및 AWS 배포는 후속 범위다.

## 실행

Docker Desktop의 Linux 컨테이너 엔진과 Docker Compose가 필요하다. 저장소 루트에서 최초 한 번 설정한다.

```powershell
Copy-Item .env.example .env
```

`.env`에 `POSTGRES_DB`, 일반 앱 계정 `POSTGRES_USER`, 서로 다른 강한 `POSTGRES_PASSWORD`와 `POSTGRES_ADMIN_PASSWORD`, 발급받은 `DATA_GO_KR_SERVICE_KEY`를 입력한다. 키는 디코딩 또는 URL 인코딩 키를 사용할 수 있다. 인증키·비밀번호는 커밋하거나 공유 로그에 넣지 않는다. 기본 `DATA_GO_KR_ZCODES=11`은 서울만 수집한다. 키 없이 구동만 확인하려면 `DATA_GO_KR_ENABLED=false`로 설정하며, 초기 데이터가 없으면 업무 API는 503이다.

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 app
docker compose logs --tail=100 caddy
docker compose down
```

`docker compose down` 후에도 DB와 Caddy CA는 named volume에 남는다. **`docker compose down -v`는 DB와 CA를 삭제한다.** 계정 생성은 새 DB 볼륨의 첫 시작 때 실행하므로 이후 `.env` 암호만 바꾸면 기존 DB 계정 암호는 바뀌지 않는다. 앱은 DB 준비 후, Caddy는 앱 health 확인 후 시작한다. 앱 DB 계정은 비관리자이며 해당 DB·스키마 권한만 갖는다.

호스트에는 기본 `127.0.0.1:8443` HTTPS 포트만 열린다. 앱 8080과 DB 5432는 Docker 내부에서만 접근한다. `HTTPS_PORT`를 변경할 수 있다.

## 로컬 HTTPS 인증서 신뢰

Caddy는 `localhost`에 `tls internal` 인증서를 발급한다. 로컬 CA는 호스트나 Android에서 자동으로 신뢰되지 않으므로 구동 후 공개 루트 인증서를 복사한다.

```powershell
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./local-ca.crt
curl.exe --cacert ./local-ca.crt https://localhost:8443/actuator/health
curl.exe --cacert ./local-ca.crt 'https://localhost:8443/api/v1/stations?lat=37.5500&lng=127.1400'
```

이 방법은 해당 요청에서만 이 CA를 신뢰한다. Windows 브라우저에서도 사용하려면 인증서가 이 로컬 Caddy의 CA인지 확인한 뒤 현재 사용자 루트 저장소에 등록할 수 있다.

Windows의 Schannel 기반 `curl.exe`는 로컬 CA의 폐기 목록이 없어서 `revocation status is unknown` 오류를 낼 수 있다. 이 경우 Python 3의 표준 TLS 검증으로 CA와 호스트 이름을 확인할 수 있다. 저장소 루트에서 실행한다.

```python
import ssl
import urllib.request

context = ssl.create_default_context(cafile="local-ca.crt")
with urllib.request.urlopen("https://localhost:8443/actuator/health", context=context, timeout=10) as response:
    print(response.status, response.read().decode())
```

Windows 브라우저 신뢰 등록 명령:

```powershell
certutil -user -addstore Root .\local-ca.crt
```

사용 종료 시 Windows 인증서 관리자(`certmgr.msc`)에서 해당 Caddy 로컬 CA를 확인해 제거한다. CA 개인키는 복사하거나 공유하지 않는다. 별도 저장소를 쓰는 브라우저에는 공개 CA를 별도로 등록한다. [Caddy 로컬 TLS 문서](https://caddyserver.com/docs/automatic-https#local-https)를 참고한다.

Android Automotive 에뮬레이터의 `10.0.2.2`는 `localhost`와 다르므로 현재 인증서를 그 주소나 EC2 IP에 그대로 사용할 수 없다. 에뮬레이터·실차 연동은 실제 접근하는 호스트 이름에 맞는 인증서와 [Android 디버그 전용 CA 신뢰 설정](https://developer.android.com/privacy-and-security/security-config#debug-overrides)을 별도로 구성해야 한다. TLS 검증은 유지한다. 도메인·공인 인증서·배포는 이번 범위에 포함하지 않는다.

Swagger는 `https://localhost:8443/swagger-ui.html`, OpenAPI JSON은 `https://localhost:8443/v3/api-docs`다. 브라우저에 CA를 등록하거나 위 `--cacert` 방식으로 접근한다.

## API 계약

### `GET /api/v1/stations`

| Query | 타입 | 규칙 |
| --- | --- | --- |
| `lat` | Double | 필수, 유한수, -90~90 |
| `lng` | Double | 필수, 유한수, -180~180 |
| `radiusKm` | Double | 기본 3, 0 초과 3 이하 |
| `keyword` | String? | 선택, 충전소명·주소 검색 |
| `page` | Int | 기본 0, 0 이상 |
| `size` | Int | 기본 20, 1~200 |

Haversine 거리로 반경 내 결과를 필터링하고 거리 오름차순·충전소 ID 순으로 정렬한 뒤 페이지를 나눈다. `size`는 DB 응답 개수이며 외부 호출량과 무관하다. 예시는 가상 데이터다.

```json
{
  "items": [{
    "stationId": "ST-001", "name": "예시 충전소", "address": "서울특별시 예시 주소",
    "lat": 37.551, "lng": 127.141, "distanceMeters": 142,
    "availableCount": 1, "totalValidCount": 2,
    "chargerTypes": ["AC_SLOW", "DC_COMBO"], "maxOutputKw": 100.0,
    "visitCount": null, "statusObservedAt": "2026-11-11T20:00:00+09:00"
  }],
  "page": 0, "size": 20, "hasNext": false,
  "fetchedAt": "2026-11-11T20:01:00+09:00"
}
```

### `GET /api/v1/stations/{stationId}`

`stationId`는 환경공단의 실제 문자열 `statId`다. 충전기 ID의 앞자리 0을 보존한다.

```json
{
  "stationId": "ST-001", "name": "예시 충전소", "address": "서울특별시 예시 주소",
  "availableCount": 1, "totalValidCount": 2,
  "chargers": [{
    "chargerId": "01", "connectorType": "DC_COMBO", "connectorTypes": ["DC_COMBO"],
    "chgerType": "04", "outputKw": 100.0, "status": "AVAILABLE", "stat": "2",
    "statusUpdatedAt": "2026-11-11T20:00:00+09:00"
  }, {
    "chargerId": "02", "connectorType": "AC_SLOW", "connectorTypes": ["AC_SLOW"],
    "chgerType": "02", "outputKw": null, "status": "CHARGING", "stat": "3",
    "statusUpdatedAt": "2026-11-11T19:59:00+09:00"
  }],
  "statusObservedAt": "2026-11-11T20:00:00+09:00"
}
```

단일 `connectorType`은 기존 필드를 유지하며 복합 규격은 `+`로 연결하고 `connectorTypes`에 전체 유형을 제공한다. `chgerType`·`stat`은 원본 코드다. 상세에는 위치 `lat`·`lng`와 충전기별 `statusObservedAt`도 포함한다. 미제공 출력과 방문 횟수는 `null`이다. 삭제되거나 유효하지 않은 충전기는 총 유효 수·가용 수에서 제외한다. 상태 `2`만 가용 수로 계산한다.

| stat | status |
| --- | --- |
| 0, 그 외 | UNKNOWN |
| 1 | COMMUNICATION_ERROR |
| 2 | AVAILABLE |
| 3 | CHARGING |
| 4 | STOPPED |
| 5 | MAINTENANCE |
| 6 | RESERVED |
| 9 | UNCONFIRMED |

연결 규격은 `01` CHAdeMO, `02` AC 완속, `03` CHAdeMO+AC 3상, `04` DC 콤보, `05` CHAdeMO+DC 콤보, `06` 세 규격 복합, `07` AC 3상으로 정규화한다. 다른 규격은 `UNKNOWN`과 원본 코드를 함께 제공한다.

`statusUpdatedAt`은 원천 상태 변경 시각이고 `statusObservedAt`은 서버의 상태 관측 시각이다. 충전소는 유효 충전기의 가장 오래된 관측 시각을 반환한다. 시각 미제공은 `null`일 수 있다. 변경분에 없는 충전기의 관측 시각은 새 수집 시각으로 바꾸지 않는다. 오래된 캐시도 마지막 자료와 관측 시각을 반환하므로 실시간 빈자리 보장이 아니다.

오류는 `{ "error": "STATION_NOT_FOUND", "message": "해당 충전소를 찾을 수 없습니다." }` 형태다. 잘못된 입력은 400, 수집 완료 후 없는 충전소는 404, 초기 캐시 없음은 503, 예상하지 못한 오류는 500이며 내부 오류 내용은 노출하지 않는다. 외부 수집 실패 시 기존 캐시 조회는 계속 가능하다.

## 수집 범위와 호출 예산

[한국환경공단 공공데이터](https://www.data.go.kr/data/15076352/openapi.do)의 `getChargerInfo`와 `getChargerStatus` XML을 사용한다. 기본 수집 지역은 **서울(`11`)만**이다. 다른 지역은 `DATA_GO_KR_ZCODES`에 시도 코드를 쉼표로 설정해야 수집된다. 미수집 지역까지 전국 데이터가 제공된다고 해석하면 안 된다.

메타데이터는 최초 수집과 하루 1회, 상태 변경분은 5분 주기를 목표로 수집한다. 스케줄러·페이지 처리 지연으로 변경분을 놓치지 않도록 상태 조회는 허용 범위 내 `period=10`으로 겹쳐 받고 이전 시각의 상태로 되돌리지 않는다. 완전한 메타데이터에서 삭제·누락·무효 항목을 반영하며, 상태 변경분에 없다는 이유로 기존 충전기를 삭제하지 않는다. 완전히 빈 메타데이터 응답은 기존 자료를 보존하고 재시도한다. XML 외부 엔터티·DTD를 차단하고 HTTP 시간 제한을 적용한다. 실패는 제한된 백오프로 다음 주기에 재시도한다. 요청 URL·인증키·원문 오류 응답은 로그에 기록하지 않는다.

지역 설정을 바꿔도 이전에 DB에 수집한 지역 자료는 보존되며 조회될 수 있다. 초기 수집 자체가 유효 자료 없이 실패한 경우 503이고, 수집 성공 뒤 충전기가 모두 삭제된 경우 빈 목록 또는 404다.

개발계정 한도는 하루 1,000회로 가정한다. 기본 일일 예산은 900회이며 100회 여유를 둔다. DB에 일별 호출량과 수집 시각을 저장해 재시작 후에도 예산을 유지하고 DB 잠금으로 중복 수집을 막는다. 페이지마다 실제 요청을 차감하며 지역 수·페이지 수를 포함해 남은 예산을 확인한다. `numOfRows` 기본 9999로 페이지 수를 줄인다.

상태가 지역당 1페이지면 1개 지역은 하루 약 288회, 3개 지역은 약 864회에 메타데이터·실패 요청 비용이 추가된다. 한 지역이라도 여러 페이지면 비용이 늘어난다. 예산이 부족하면 수집 주기를 건너뛸 수 있어 5분 최신성을 보장하지 않는다. 장시간 중단 중 발생한 변경은 일일 메타데이터 수집 때 복구될 수 있다.

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| `DATA_GO_KR_ENABLED` | true | 정기 수집 사용 여부 |
| `DATA_GO_KR_ZCODES` | 11 | 수집할 시도 코드 |
| `DATA_GO_KR_NUM_OF_ROWS` | 9999 | 외부 API 페이지 크기, 10~9999 |
| `DATA_GO_KR_DAILY_BUDGET` | 900 | 하루 최대 호출 예산, 최대 900 |
| `DATA_GO_KR_METADATA_BUDGET` | 100 | 메타데이터에 사용할 일일 예산 |

예산은 이 앱이 같은 DB로 실행한 요청 기준이다. 같은 키를 다른 앱에서도 쓰면 전체 사용량을 따로 고려해야 한다. DB의 예산 기록을 지워도 상위 API의 실제 일일 한도는 초기화되지 않는다.

## 테스트·CI·자동 커밋

JDK 21에서 서버 디렉터리의 Gradle Wrapper를 사용한다. PowerShell에서는 `-D` 인자를 따옴표로 감싼다.

```powershell
cd altercharger-server
.\gradlew.bat test --no-daemon '-Dspring.profiles.active=ci'
.\gradlew.bat bootJar --no-daemon -x test
```

Linux/macOS에서는 `./gradlew`를 사용한다. 테스트는 XML fixture 파싱·XXE 차단, 상태 집계와 거리 검색, 컨트롤러 입력 및 오류 계약을 검사한다. 실제 환경공단 호출·API 키·PostgreSQL·AWS 자격증명 없이 실행된다. `ci` 프로필에서는 수집이 꺼진다.

`.github/workflows/ci.yml`은 `main`의 push·pull_request에서 Java 21 설정과 Gradle 캐시 후 테스트·실행 JAR 패키징만 수행한다. [AGENTS.md](AGENTS.md)에 따라 작업하는 Codex가 검증한 변경을 한국어 Conventional Commits로 직접 커밋한다.
