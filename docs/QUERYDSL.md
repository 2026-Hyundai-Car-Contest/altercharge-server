# Querydsl 저장소 구현

Java 21·Jakarta Persistence·Hibernate 7 환경에서 `io.github.openfeign.querydsl:querydsl-jpa:7.7`을 사용한다. Spring Data JPA 저장소에 사용자 정의 fragment를 연결하고 `QuerydslConfig`의 `JPAQueryFactory`와 컴파일 시 생성한 Q 클래스로 조건을 조합한다. DB 스키마와 8개 API의 계약은 그대로 유지한다.

## 적용 범위

| 저장소 | 구현 | 쿼리 역할 |
| --- | --- | --- |
| StationRepository | StationQueriesImpl | 활성 충전기가 있는 충전소 조회, 위·경도 후보 범위 조회, 충전기 전체 fetch join을 포함한 상세 조회 |
| ChargerRepository | ChargerQueriesImpl | 복합 키 목록 조회와 충전소 fetch join, 지역별 논리 삭제, 활성 충전기의 캐시 확인 시각 갱신 |

주변 조회는 일반 경도 범위와 날짜변경선을 넘는 두 범위를 Java 조건으로 선택한다. 활성 충전기 존재 여부는 별도 EXISTS 조건으로 검사하며, fetch join에는 삭제된 충전기를 포함해 관리 컬렉션이 일부만 채워지지 않게 한다. DTO로 변환할 때 삭제 행을 제외한다.

DB에서는 보수적인 위·경도 범위로 후보를 제한하고, 기존 서비스가 정확한 Haversine 거리·키워드 검색·거리 및 ID 정렬·페이지 처리를 수행한다. 컬렉션 fetch join에 DB 페이지 제한을 추가하지 않는다. 목록·추천·충전 이벤트 위치 매칭이 같은 후보 조회를 사용한다.

지역별 UPDATE는 충전소 ID의 하위 쿼리로 대상을 제한한다. 실행 전에 EntityManager를 flush하고 성공 후 clear해, 미반영 변경분이 유실되거나 관리 엔티티가 갱신 전 값을 유지하지 않게 한다. 별도 트랜잭션을 열지 않고 기존 수집 트랜잭션에 참여하므로 변경분·벌크 갱신·성공 기록이 함께 커밋되거나 롤백된다. 전체 시각 갱신의 성공·실패 및 삭제 제외 정책은 [COLLECTOR_DIAGNOSTICS.md](COLLECTOR_DIAGNOSTICS.md)를 따른다.

차량·일별 호출량의 잠금 조회, 지역 동기화 행 조회, 실제 방문 집계, 만료 스냅샷의 벌크 삭제는 조건이 고정된 JPQL로 유지한다. 단순 조회는 기존 Spring Data 파생 메서드를 사용한다. 방문 중복을 막는 `INSERT ... ON CONFLICT DO NOTHING`은 PostgreSQL 고유 제약과 결합된 기존 native 쿼리를 유지한다.

## 빌드와 검증

Gradle annotationProcessor에 `querydsl-apt:7.7:jpa`와 Jakarta Persistence API를 등록한다. Q 클래스는 `build/generated/sources/annotationProcessor/java/main` 아래에 생성하며 직접 작성하거나 Git에 추가하지 않는다. `compileJava`, `test`, `bootJar`와 기존 Docker 빌드가 필요한 생성을 자동으로 수행한다.

기본 `test`에 포함된 `StationPersistenceTests`는 Docker Engine에서 별도 PostgreSQL 17 컨테이너를 시작한다. 현재 Flyway 마이그레이션으로 초기화한 뒤 Hibernate validate로 검증하며, 테스트용 비밀번호는 실행마다 무작위로 생성한다. 테스트가 끝나면 전용 컨테이너를 종료하며 기존 로컬·운영 DB를 사용하지 않는다. Docker가 없으면 통합 테스트를 생략하지 않고 실패한다.

검증 항목은 일반·날짜변경선·극지 후보 검색, 경계 포함, 중복 없는 전체 fetch join, 복합 키 쌍 구분, 정확한 거리와 키워드·페이지 동작, 빈 대상 집합, 지역별 벌크 갱신의 flush·clear와 엔티티 적재 방지, 삭제 및 다른 지역 제외, 원천 시각 보존, 빈 변경분의 확인 시각 갱신, 페이지 근거 불명확 시 갱신 금지, 트랜잭션 롤백이다. 기존 수집기의 마지막 페이지·실패·재시도 검증도 유지한다.

구성 근거는 [Spring Data JPA의 Querydsl 설정](https://docs.spring.io/spring-data/jpa/reference/repositories/core-extensions.html#core.extensions.querydsl)과 [OpenFeign Querydsl 7.7 릴리스](https://github.com/OpenFeign/querydsl/releases/tag/7.7)에서 확인할 수 있다.
