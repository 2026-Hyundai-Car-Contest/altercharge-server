# 충전소 수집과 STATUS_STALE 진단

`getChargerStatus(period=10)`은 설정 지역의 최근 변경분이다. 사용자 지정 정책에 따라, 전체 페이지·일관된 전체 건수를 확인한 상태 수집이 DB에 성공적으로 반영되면 해당 지역의 **모든 활성 충전기 `statusObservedAt`을 수집 시작 시각으로 갱신**한다. 변경분 0건과 응답에 없는 충전기도 포함하며, 상태값과 `statusUpdatedAt`은 기존 원천 시각 비교 규칙으로만 병합한다.

`statusObservedAt`은 **캐시 상태 확인 시각**이다. 미반환 항목은 저장된 상태가 유지됐다고 간주한다. 이는 개별 장비가 새 상태를 보고했다는 뜻이 아니며, 장애 중 놓친 변경분이나 실제 빈자리를 보장하지 않는다. 실패·미완료 페이지·페이지 근거 불명확 시에는 전체 시각을 갱신하지 않고, 삭제된 충전기와 다른 지역도 제외한다. 변경분·지역별 벌크 UPDATE·성공 기록은 같은 트랜잭션으로 커밋하거나 함께 롤백한다.

## 응답 시각을 비교할 때

- 목록의 `fetchedAt`: 목록 응답을 만든 서버 시각.
- 상세의 `chargers[].statusUpdatedAt`: 환경공단의 상태 변경 시각.
- 상세의 `chargers[].statusObservedAt`: 전체 정보의 재확인 또는 정상 상태 수집의 시작 시각. 정상 상태 수집은 해당 지역의 미반환 활성 항목도 갱신.
- 목록·상세의 최상위 `statusObservedAt`: 유효 충전기들의 가장 오래된 캐시 상태 확인 시각.
- 길 안내의 `checkedAt`: 길 안내 정책을 검사한 서버 시각.

시각의 의미가 다르므로 서로 일치하지 않을 수 있다. UTC의 `Z`와 한국 시각 `+09:00`도 구분한다. 예를 들어 목록 `fetchedAt=2026-11-12T15:51:57Z`는 길 안내 `checkedAt=2026-11-13T05:05:29Z`보다 약 13시간 전 응답이다. 새 정책에서 04:58 상태 수집이 정상 커밋되면 해당 지역의 활성 충전기 `statusObservedAt`도 그 수집 시작 시각으로 바뀐다. 저장된 상태가 AVAILABLE이면 05:05 길 안내는 최근 확인 시각으로 `true`·`OK`를 반환할 수 있다.

길 안내는 유효기간(`STATION_STATUS_MAX_AGE_MINUTES`, 예시 15분) 안의 확인 시각을 가진 AVAILABLE이 하나라도 있으면 OK다. 없고 최신성이 불명확한 유효 충전기가 있으면 STATUS_STALE, 나머지는 NO_AVAILABLE_CHARGER다. NULL·미래 확인 시각은 유효하게 인정하지 않는다. 이전 캐시의 지역 연속성 보조 판정은 유지하지만, 정상 상태 수집으로 갱신된 확인 시각이 우선하므로 `BASELINE_MISSING`·`SYNC_WINDOW_GAP` 자체가 안내 차단 조건은 아니다.

## 한 번의 타임아웃으로 연속성이 끊기던 원인

기존에는 상태 수집도 실패 종료 후 5분부터 지수 백오프했다. 5분 뒤 시도한 요청이 20초 후 실패하고 다시 5분을 기다리면, 직전 성공부터 다음 응답까지 10분 20초 이상이 된다. 환경공단의 최대 `period=10`으로 그 공백을 메울 수 없다.

상태 수집은 실패 후 1·2·4·8분 순서로 백오프하고 최대 6시간으로 제한한다. 메타데이터는 기존 5·10·20분 순서와 최대 6시간을 유지한다. 평소 상태 수집 간격은 계속 5분 이상이다. 재시도도 동일 호출 예산을 소비하며 예산 소진은 다음 한국 날짜까지 대기한다. 재배포는 이미 DB에 저장된 `retry_after`를 변경하지 않는다.

이 조정은 짧은 장애 후 실제로 겹치는 조회 범위를 확보할 가능성을 높인다. 여러 번 실패하거나 다운로드가 길어 공백이 생기면 지역 연속성 진단은 무효가 되고, 이미 잃은 진단 기준점은 전체 메타데이터로 복구한다. 새 캐시 확인 정책에서는 그 뒤 정상 상태 수집이 성공하면 지역 전체 `statusObservedAt` 갱신을 재개하므로, AVAILABLE 안내를 허용하는 데 진단 기준점 복구를 기다릴 필요가 없다.

## 배포 서버 로그

```bash
docker compose --env-file /opt/altercharge/.env -f /opt/altercharge/current/compose.prod.yaml logs --since=2h --follow app 2>&1 | grep --line-buffered 'Collector'
```

`Collector status completed`는 변경분과 캐시 시각의 DB 반영 완료다. `stage=OBSERVATION_REFRESH reason=STATUS_POLL_CONFIRMED`는 해당 지역 전체 활성 충전기 확인 시각을 갱신한 로그이며 `chargers`에 적용 행 수가 나온다. `stage=SYNC_COVERAGE`는 기존 지역 연속성 진단이다. 두 로그는 트랜잭션 안에서 출력하므로 최종 결과는 완료 로그와 커밋된 DB 값으로 확인한다. 로그의 `reason`은 길 안내 응답의 `reasonCode`와 별개다.

| reason | 의미 |
| --- | --- |
| STATUS_POLL_CONFIRMED | 전체 페이지가 확인된 정상 상태 수집으로 지역 전체 활성 충전기의 캐시 확인 시각 갱신 |
| CONTINUOUS_SYNC | 검증된 변경분 조회 범위가 이전 동기화와 이어짐 |
| BASELINE_ESTABLISHED | 검증된 전체 메타데이터로 지역 기준점 설정 |
| BASELINE_MISSING | 기준점 또는 이전 지역 동기화 시각 없음 |
| SYNC_WINDOW_GAP | 마지막 응답의 10분 조회 범위가 이전 지역 동기화까지 닿지 않음 |
| INVALID_SYNC_WINDOW / SYNC_CLOCK_REVERSED | 시각 범위가 잘못되거나 역전됨 |
| UNPROVEN_PAGES | 전체 건수·페이지 완전성을 입증하지 못함 |
| SOURCE_TIME_MISSING / SOURCE_TIME_FUTURE | 변경분의 원천 변경 시각이 없거나 응답 완료보다 미래임. 아직 메타데이터에 없는 식별자도 검증 |
| CACHED_SOURCE_TIME_FUTURE | 기존 저장된 원천 변경 시각이 이번 응답 완료보다 미래임 |
| REGION_MISMATCH | 알려진 충전기가 요청 지역과 다름. 그 행은 반영 거부 |
| INVALID_IDENTITY / INVALID_STATUS | 변경분의 식별자 또는 상태가 부적합함 |
| DUPLICATE_DELTA_IDENTITY | 같은 지역 변경분에서 충전기 식별자가 중복됨 |
| DELTA_IDENTITY_LIMIT_EXCEEDED | 지역별 검증 식별자 100,000개 한도 초과 |

변경분 검증에 여러 문제가 있으면 우선 검출한 원인을 남긴다. 페이지 완전성을 확인하지 못한 경우에는 `UNPROVEN_PAGES`가 우선하며 지역 전체 시각도 갱신하지 않는다. 원천 시각·중복 등 행 문제는 기존 병합 규칙과 지역 연속성 진단에 반영하지만, 페이지가 완전한 정상 수집의 전체 활성 행 캐시 확인은 별도 정책으로 적용한다. 따라서 STATUS_POLL_CONFIRMED와 BASELINE_MISSING 또는 행 검증 실패 진단이 함께 출력될 수 있다. 기존 배포 버전의 `UNPROVEN_OR_GAPPED_SYNC`만으로는 어느 조건인지 확정할 수 없다. 인증키·요청 URL·원천 응답·개별 식별자는 진단 로그에 남기지 않는다.

현재 배포 설정의 내부 DB 포트가 기본값 5432이고 DB 이름이 altercharge일 때, 다음 읽기 전용 명령으로 전역 작업과 지역 근거를 확인한다.

```bash
docker compose --env-file /opt/altercharge/.env -f /opt/altercharge/current/compose.prod.yaml exec -T postgres psql -U postgres -d altercharge -c "SELECT task, succeeded_at, retry_after, consecutive_failures, sync_baseline_at, last_status_sync_at, status_sync_valid FROM collection_state ORDER BY task;"
```

`region:11`의 `succeeded_at`은 전체 응답을 DB에 처리한 수집 시작 시각이다. 최근 변경분의 성공은 전역 `status.succeeded_at`에 기록되며, 지역 연속성이 인정된 시각은 `region:11.last_status_sync_at`이다. 이 값들은 역할이 서로 다르다.

## 기존 기준점 복구

최초 기준점이 없고 해당 지역의 전체 처리 기록도 없을 때만 자동으로 전체 수집을 한 번 앞당긴다. 놓친 상태 변경과 지역 진단 기준점을 복구하려면 정기 전체 수집을 기다리거나 운영자가 [전체 메타데이터 수집 일정 복구 명령](../README.md#운영자가-전체-메타데이터-수집-일정을-앞당길-때)을 직접 실행한다. 이 명령은 수집기와 같은 advisory lock 아래 전역 metadata의 기존 non-NULL 성공 시각만 옮긴다. 새 정책의 길 안내는 다음 정상 상태 수집이 확인 시각을 갱신하면 AVAILABLE을 허용한다.

캐시 확인 시각은 정상 수집 경로가 관리하고, 지역 진단 기준점은 실제 전체 수집과 연속성 검증으로 관리한다. 수동 복구 SQL은 이 값이나 `status_sync_valid`를 직접 설정하지 않는다. 재시도 시각·호출 예산은 유지한다.

로컬 서버 실행 방법은 [LOCAL_RUN.md](LOCAL_RUN.md)에 있다.
