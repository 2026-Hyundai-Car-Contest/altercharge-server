# 시연 주행·충전 기록 자동 보충

대상은 `mock-vehicle-001` 하나다. Flyway V4의 기존 시드는 보존하고, V5가 `refresh_demo_vehicle_history(timestamptz)` 함수와 최초 보충 실행을 관리한다. JPA 저장소가 같은 함수를 호출하며 기존 V1~V4의 내용과 체크섬은 바꾸지 않는다.

## 프론트가 사용하는 값

확인한 프론트는 `altercharge-app`의 main 커밋 `7c9b1779fa111b7b47d5cb57337acaca4018a932`다.

| 코드 | 확인한 동작 |
| --- | --- |
| [HomeCardsLoader](https://github.com/2026-Hyundai-Car-Contest/altercharge-app/blob/7c9b1779fa111b7b47d5cb57337acaca4018a932/app/src/main/java/com/altercharge/app/data/home/HomeCardsLoader.kt) | 스냅샷은 from/to 없이 기본 최근 56일을 요청하고 마지막 충전 이력도 함께 조회 |
| [DriveHistoryMapper](https://github.com/2026-Hyundai-Car-Contest/altercharge-app/blob/7c9b1779fa111b7b47d5cb57337acaca4018a932/app/src/main/java/com/altercharge/app/data/remote/mapper/DriveHistoryMapper.kt) | 원본 누적거리의 날짜별 차분에서 오늘을 제외하고 마지막 14일을 판정에 전달. 마지막 충전 경과 일수와 주행거리도 카드에 전달 |
| [ChargeJudgment](https://github.com/2026-Hyundai-Car-Contest/altercharge-app/blob/7c9b1779fa111b7b47d5cb57337acaca4018a932/app/src/main/java/com/altercharge/app/domain/judgment/ChargeJudgment.kt) | 14개 미만이면 Collecting. 14개 이상이면 평균·예비 거리·버틸 일수 계산 |
| [StationMapper](https://github.com/2026-Hyundai-Car-Contest/altercharge-app/blob/7c9b1779fa111b7b47d5cb57337acaca4018a932/app/src/main/java/com/altercharge/app/data/remote/mapper/StationMapper.kt) | 마지막 충전 완료 시각을 한국시간 시각 또는 날짜로 표시 |

앱은 서버 `completeDayCount`를 직접 판정에 쓰지 않는다. 현재 mapper는 첫 기록 이후 비어 있는 날을 0km로 채우므로, 고정 시드가 오래되면 실제 운행일이 부족해도 평균이 낮아질 수 있다. 자동 보충은 어제까지 14일에 실제 시연 측정값을 채워 이 문제도 방지한다.

현재 차량 목업의 SOC·주행가능거리·현재 누적거리는 앱에서 별도로 제공한다. 서버의 합성 기록을 차에서 관측한 실측값으로 간주하지 않는다.

## 보충 정책

- 매일 한국시간 기준 어제까지 최근 14일을 대상으로 한다. 오늘 밤이나 미래 측정값을 미리 만들지 않는다.
- 앱의 기본 최근 56일 조회에 더 오래된 기록이 남아 있으면 그 기록부터 어제까지 빠진 날짜도 보충한다. 과거와 새 기록 사이의 누적거리 증가분이 하루에 몰리지 않게 하며, 보충 범위는 최대 55개 완료 날짜로 제한한다.
- 하루 00:30·06:15·12:00·17:45·23:30에 5건, 간격은 5시간 45분이다. 이 구간만 조회하면 정상 시연 기록은 70건, 완성일은 14일이다.
- 하루 주행거리는 40km다. 기존 생성 기록의 누적거리와 SOC 주기를 이어 가며, 보관기간이 지나 가장 오래된 생성 기록이 없어져도 남은 생성 기록으로 이어 간다.
- 기존 생성 기록이 전혀 없으면 최근 14일을 20,000km에서 시작한다. 장기 중단 후에도 해당 조회 구간을 다시 채울 수 있다.
- 같은 차량·시각의 기존 기록은 덮어쓰지 않는다. 임의 입력을 보정하거나 다른 차량의 기록을 채우지 않는다. 시연 차량에 서로 모순되는 별도 측정값이 이미 있으면 그 값도 보존하므로 해당 날짜의 완성 판정은 달라질 수 있다.
- 주행 기록의 기존 56일 보관·정리는 유지한다. 기본 설정처럼 보관기간이 최근 14개 완료 날짜를 포함해야 한다.

생성된 스냅샷에서 전날 23:30보다 SOC가 오른 날의 00:30에 충전 완료 이벤트를 만든다. 완료 시각·SOC·누적거리는 같은 시점의 스냅샷과 일치한다. `source=MOCK`, `verified=false`, `matchedStationId=null`, `visitCountAdded=0`, `dailyDuplicate=false`이며 실제 방문 집계를 추가하지 않는다. 외부에서 전달된 임의의 SOC 상승으로 합성 완료 이벤트를 만들지 않는다.

완료 ID와 스냅샷 ID는 날짜별로 일정하다. 다시 실행하거나 여러 인스턴스가 실행해도 중복을 만들지 않는다. 차량 행 잠금으로 기존 스냅샷·이벤트 쓰기와 직렬화하고, 스냅샷과 완료 이벤트를 한 트랜잭션으로 반영한다. 완료 저장이 실패하면 앞서 추가한 스냅샷도 롤백한다. 충전 이벤트의 기존 보존 정책은 유지한다.

## 실행과 확인

`DemoHistoryRefresher`는 기동 후 1초부터 1분 간격으로 날짜를 확인한다. 해당 프로세스에서 한국시간 오늘의 보충이 성공한 뒤에는 당일 DB 보충을 반복하지 않는다. 다음 날짜가 되거나 프로세스를 재시작하면 다시 누락분을 확인한다. DB 실행·커밋 실패 시 성공 날짜를 기록하지 않고 다음 tick에서 재시도한다. 외부 환경공단 API는 호출하지 않는다.

일반 실행의 `app.history.demo-enabled`는 true, CI 프로필은 false다. 이 설정은 백그라운드 보충을 제어하며 Flyway V4·V5의 최초 실행은 별개다. 운영 `.env` 변경은 필요 없다.

배포 후 다음 로그로 성공 날짜와 추가 건수를 확인한다.

```bash
docker compose --env-file /opt/altercharge/.env -f /opt/altercharge/current/compose.prod.yaml logs --since=24h app 2>&1 | grep 'Demo history refresh'
```

API 07은 `vehicleId=mock-vehicle-001`로 조회한다. 어제까지 14일만 확인하려면 한국시간 날짜로 from=오늘−14일, to=어제를 지정한다. API 08에서는 마지막 완료 시각·완료 당시 누적거리·현재 저장 누적거리·충전 후 주행거리를 확인한다. 주행거리와 이벤트는 모두 시연용 데이터다.

PostgreSQL 통합 검증에는 한국시간 날짜 전환, 40일·90일 뒤 보충, 재실행 중복 방지, 누적거리 역전 방지, SOC 상승과 완료 이벤트 대응, 다른 차량·기존 값 보존, 실패 시 전체 롤백을 포함한다. 스케줄러는 자정 경계와 DB 실행·커밋 실패의 재시도를 검증한다.

별도로 위 프론트 커밋의 JSON 파서·mapper·충전 판정 함수를 그대로 JVM에서 실행했다. 실제 서버 서비스가 반환한 JSON을 사용해 최초 적용과 1·2·5·40·90일 뒤의 6가지 경우에서 최근 14일이 모두 40km이고, 판정이 Ready이며, 마지막 충전 날짜와 충전 후 주행거리가 표시되는 것을 확인했다.
