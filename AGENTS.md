# 저장소 작업 지침

- 기존 `altercharger-server`의 Java 21, Spring Boot, Gradle Wrapper 구조를 유지한다.
- 현재 구현 범위는 충전소 목록·검색 및 충전소 상세·충전기 상태 조회 GET API 2개다. 배포·자동 릴리스·추천·사용자·차량·이벤트 API는 추가하지 않는다.
- 작업 시작 시 `git status`를 확인한다. 사용자의 미커밋 변경을 초기화하거나 덮어쓰거나 함께 커밋하지 않는다.
- API 인증키·DB 비밀번호·개인키는 소스·문서·테스트·로그에 기록하지 않는다. `.env`는 로컬에서만 사용하며 예제에는 자리표시자만 둔다.
- 변경한 작업 단위마다 필요한 검증을 수행한 뒤, 직접 수정한 파일을 경로별로 `git add`하고 `git commit`한다. `git add .`와 실패를 감추는 `--no-verify`는 사용하지 않는다.
- 커밋 제목은 Conventional Commits 형식 `<type>(<선택적 scope>): <한국어 설명>`으로 작성한다. 설명은 반드시 한국어이며 타입과 코드·파일명 같은 고유 식별자만 영어를 허용한다.
- 제목은 한 줄로 변경 내용을 구체적으로 쓴다. `update`, `changes`, `수정`처럼 내용이 없는 제목은 금지한다.
- 유형은 `feat` 기능, `fix` 버그, `test` 테스트, `docs` 문서, `chore` 유지보수, `ci` CI, `build` 빌드·Docker, `refactor` 동작 변경 없는 구조 개선으로 구분한다.
- 예: `feat(stations): 주변 충전소 검색 API 구현`, `build(docker): PostgreSQL과 HTTPS 실행 환경 구성`.
- 논리적으로 독립적인 변경은 여러 커밋으로 나눈다. 커밋이 불가능하면 변경 파일을 유지하고 이유를 보고한다.
- `git push`와 원격 저장소 설정 변경은 하지 않는다. GitHub Actions는 테스트·빌드만 수행하며 자동 커밋이나 배포를 실행하지 않는다.
