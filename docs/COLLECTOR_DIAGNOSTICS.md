# 충전소 수집과 STATUS_STALE 진단

`getChargerStatus(period=10)`은 설정 지역의 최근 변경분이다. 5분마다 호출에 성공해도 변경되지 않아 응답에 없는 충전기의 `statusObservedAt`을 새 시각으로 덮어쓰지 않는다. 해당 상태가 계속 유효하다는 근거는 별도의 지역 기준점과 연속 동기화로 관리한다.

## 응답 시각을 비교할 때

- 목록의 `fetchedAt`: 목록 응답을 만든 서버 시각.
- 상세의 `chargers[].statusUpdatedAt`: 환경공단의 상태 변경 시각.
- 상세의 `chargers[].statusObservedAt`: 그 충전기를 실제 수집한 다운로드 시작 시각.
- 목록·상세의 최상위 `statusObservedAt`: 유효 충전기들의 가장 오래된 실제 관측 시각.
- 길 안내의 `checkedAt`: 길 안내 정책을 검사한 서버 시각.

시각의 의미가 다르므로 서로 일치시키지 않는다. UTC의 `Z`와 한국 시각 `+09:00`도 구분한다. 예를 들어 목록 `fetchedAt=2026-11-12T15:51:57Z`는 길 안내 `checkedAt=2026-11-13T05:05:29Z`보다 약 13시간 전 응답이다. 상세 관측 `2026-11-13T03:27:42Z`는 그 길 안내 검사보다 약 98분 전이므로, 지역 근거가 무효라면 상태가 AVAILABLE이어도 `STATUS_STALE`이다.

## 한 번의 타임아웃으로 연속성이 끊기던 원인

기존에는 상태 수집도 실패 종료 후 5분부터 지수 백오프했다. 5분 뒤 시도한 요청이 20초 후 실패하고 다시 5분을 기다리면, 직전 성공부터 다음 응답까지 10분 20초 이상이 된다. 환경공단의 최대 `period=10`으로 그 공백을 메울 수 없다.

상태 수집은 실패 후 1·2·4·8분 순서로 백오프하고 최대 6시간으로 제한한다. 메타데이터는 기존 5·10·20분 순서와 최대 6시간을 유지한다. 평소 상태 수집 간격은 계속 5분 이상이다. 재시도도 동일 호출 예산을 소비하며 예산 소진은 다음 한국 날짜까지 대기한다. 재배포는 이미 DB에 저장된 `retry_after`를 변경하지 않는다.

이 조정은 짧은 장애 후 실제로 겹치는 조회 범위를 확보할 가능성을 높인다. 여러 번 실패하거나 전체 페이지 다운로드가 길어 실제 공백이 생긴 경우에는 최신성을 인정하지 않는다. 이미 잃은 기준점은 변경분 수집 성공만으로 복구되지 않는다.

## 배포 서버 로그

```bash
docker compose --env-file /opt/altercharge/.env -f /opt/altercharge/current/compose.prod.yaml logs --since=2h --follow app 2>&1 | grep --line-buffered 'Collector'
```

`Collector status completed`는 반환된 변경분의 DB 커밋 완료다. 응답에 없는 충전기까지 최신성이 증명됐다는 뜻은 아니다. `stage=SYNC_COVERAGE`는 다음 고정 원인 코드로 구분한다. 해당 진단은 트랜잭션 안에서 출력하므로 최종 결과는 완료 로그와 커밋된 DB 값으로 확인한다.

| reason | 의미 |
| --- | --- |
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

변경분 검증에 여러 문제가 있으면 우선 검출한 원인을 남긴다. 페이지 완전성을 확인하지 못한 경우에는 `UNPROVEN_PAGES`가 우선한다. 기존 배포 버전의 `UNPROVEN_OR_GAPPED_SYNC`만으로는 어느 조건인지 확정할 수 없다. 인증키·요청 URL·원천 응답·개별 식별자는 진단 로그에 남기지 않는다.

현재 배포 설정의 내부 DB 포트가 기본값 5432이고 DB 이름이 altercharge일 때, 다음 읽기 전용 명령으로 전역 작업과 지역 근거를 확인한다.

```bash
docker compose --env-file /opt/altercharge/.env -f /opt/altercharge/current/compose.prod.yaml exec -T postgres psql -U postgres -d altercharge -c "SELECT task, succeeded_at, retry_after, consecutive_failures, sync_baseline_at, last_status_sync_at, status_sync_valid FROM collection_state ORDER BY task;"
```

`region:11`의 `succeeded_at`은 전체 응답을 DB에 처리한 수집 시작 시각이다. 최근 변경분의 성공은 전역 `status.succeeded_at`에 기록되며, 지역 연속성이 인정된 시각은 `region:11.last_status_sync_at`이다. 이 값들은 역할이 서로 다르다.

## 기존 기준점 복구

최초 기준점이 없고 해당 지역의 전체 처리 기록도 없을 때만 자동으로 전체 수집을 한 번 앞당긴다. 이미 처리한 뒤 잃은 기준점은 정기 전체 수집을 기다리거나 운영자가 [전체 메타데이터 수집 일정 복구 명령](../README.md#운영자가-전체-메타데이터-수집-일정을-앞당길-때)을 직접 실행한다. 이 명령은 수집기와 같은 advisory lock 아래 전역 metadata의 기존 non-NULL 성공 시각만 옮긴다.

관측 시각·지역 기준점을 현재 시각으로 임의 채우거나 `status_sync_valid=true`를 직접 설정하지 않는다. API 실패·원천 시각 누락·중복 등이 계속되면 새 전체 수집 뒤에도 지역 근거가 다시 무효화될 수 있으므로 위 원인 코드를 함께 확인한다. 재시도 시각·호출 예산은 유지한다.

로컬 서버 실행 방법은 [LOCAL_RUN.md](LOCAL_RUN.md)에 있다.
