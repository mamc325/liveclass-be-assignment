# 기술 검토: 수강 신청 시스템

이 문서는 구현을 이끌어야 하는 핵심 결정을 기록한다. 자세한 근거는 `docs/기술스택선정.md`, `docs/CONCURRENCY.md`, `docs/ERROR_CODES.md`에 둔다.

## 결정 사항

### Java 21과 Spring Boot 3.5.x

결정: Java 21과 Spring Boot 3.5.14를 사용한다.

근거: 현재 저장소가 이미 이 스택을 사용한다. JPA, Validation, ProblemDetail, Testcontainers 기반 통합 테스트에 적합하다.

### H2 대신 PostgreSQL

결정: 로컬/런타임에서는 PostgreSQL을 사용하고, 통합 테스트는 Testcontainers PostgreSQL을 사용한다.

근거: partial unique index, row-level lock, PostgreSQL 동시성 동작이 도메인 정합성의 핵심이다. H2로는 중요한 정합성 주장을 검증할 수 없다.

### 정원 변경 흐름에는 비관적 락

결정: 수강 신청과 수강 취소 흐름에서는 `courses` row에 `SELECT FOR UPDATE`/`PESSIMISTIC_WRITE`를 사용한다.

근거: 해당 흐름은 `occupied_count`를 읽고 변경하며 관련 row를 생성할 수 있다. 강의 단위로 직렬화하면 정원 판단이 단순하고 방어 가능하다.

### 단일 row 상태 변경에는 가드 조건 UPDATE

결정: 결제 확정과 대기 취소는 가드 조건 UPDATE를 사용한다.

근거: 이 전이들은 한 row만 변경하므로 `UPDATE ... WHERE status = expected`의 원자성으로 안전하게 보호할 수 있다.

### ErrorCode를 포함한 ProblemDetail

결정: Spring `ProblemDetail`에 `ErrorCode` 기반 안정적인 `code` 속성을 추가한다.

근거: 많은 예외 클래스 계층을 만들지 않고도 API 실패 계약을 일관되게 유지할 수 있다.

### Redis 분산 락 제외

결정: 이 과제에서는 Redis 락을 추가하지 않는다.

근거: 과제는 단일 애플리케이션/DB 구성을 전제로 한다. 모델링된 동시성 요구사항은 PostgreSQL row lock으로 충분하다.
