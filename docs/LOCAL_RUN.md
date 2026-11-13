# 로컬 서버 실행

저장소 루트에서 다음 **한 줄**로 PostgreSQL·Spring Boot·Caddy를 빌드하고 시작한다. Python 3, Docker Compose v2 이상과 실행 중인 Docker 엔진이 필요하다. Java 21과 Gradle은 Docker 빌드 안에서 사용한다.

```sh
python scripts/run-local.py
```

Windows PowerShell에서는 위 명령을 사용한다. macOS/Linux에서 `python` 명령이 없으면 `python3 scripts/run-local.py`를 사용한다.

실행기는 로컬 `.env`를 검증하고 `compose.yaml`을 사용한다. 기존 `.env`·DB 비밀번호·DB 볼륨은 변경하거나 초기화하지 않는다. 필수 값이 `needed`이면 실행을 중단한다. AWS에 접속하거나 운영 설정을 전송하지 않는다.

## 최초 1회 설정

로컬 `.env`가 없을 때만 예제에서 초기화한다. 이 초기화는 새 DB용 앱·관리자 비밀번호를 각각 안전한 난수로 생성한다. 기존 DB를 사용 중이라면 기존 비밀번호를 유지한다.

```sh
python scripts/lib/env.py init --file .env --example .env.example --mode local
```

로컬 `.env`에서 `DATA_GO_KR_SERVICE_KEY`에 본인의 실제 환경공단 키를 입력하고, `APP_DOMAIN=localhost`, `HTTPS_PORT=8443`을 확인한다. 키를 명령행이나 이 문서에 넣지 않는다. EC2 `/opt/altercharge/.env`를 복사하거나 덮어쓰지 않는다. `.env`는 Git 추적 대상이 아니다.

다시 `python scripts/run-local.py`를 실행하면 DB 준비와 앱 health 확인까지 기다린 뒤 Swagger 주소를 표시한다. 기본 주소는 `https://localhost:8443/swagger-ui/index.html`이다. 최초 충전소 전체 수집은 앱 기동 후 별도로 진행되므로 health UP 직후에는 충전소 API가 `STATION_DATA_UNAVAILABLE`을 반환할 수 있다.

`APP_DOMAIN`은 `localhost` 또는 `.localhost`로 끝나는 로컬 도메인만 허용한다. 다른 위치의 로컬 설정 파일을 사용하려면 다음처럼 명시한다.

```sh
python scripts/run-local.py --env-file /path/to/local/.env
```

## 로컬 HTTPS 인증서

최초 접속 전 Caddy의 로컬 CA 인증서를 내보낸 뒤 OS의 신뢰할 수 있는 루트 인증 기관에 등록한다. Windows에서는 인증서를 열어 현재 사용자의 루트 인증 기관에 설치할 수 있다. TLS 검증을 우회하는 옵션은 사용하지 않는다.

```sh
docker compose --env-file .env -f compose.yaml cp caddy:/data/caddy/pki/authorities/local/root.crt ./local-ca.crt
```

## 로그와 종료

수집 로그는 다음 명령으로 확인한다. `Collector status completed`는 변경분과 캐시 확인 시각의 반영 완료다. `STATUS_POLL_CONFIRMED`와 적용 행 수로 지역 전체 활성 충전기 시각 갱신을 확인하고, 기존 지역 연속성 진단은 `SYNC_COVERAGE`로 구분한다. [수집 진단 문서](COLLECTOR_DIAGNOSTICS.md)를 참고한다.

```sh
docker compose --env-file .env -f compose.yaml logs --since=1h --follow app
```

중지할 때는 다음 명령을 사용한다. DB·인증서 볼륨은 보존된다.

```sh
docker compose --env-file .env -f compose.yaml down
```
