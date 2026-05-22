# 수강 신청 시스템 프로젝트 원칙

> 최종 수정일: 2026-05-21
> 범위: BE-A 라이브클래스 수강 신청 과제

## 핵심 원칙

1. 데이터 정합성을 최우선으로 한다.
   - 동시 요청 상황에서도 강의 정원은 절대 초과하면 안 된다.
   - 활성 수강 신청과 활성 대기는 애플리케이션 검증과 DB 제약으로 모두 막는다.

2. 상태 전이는 명시적이고 단방향이어야 한다.
   - `Course`: `DRAFT -> OPEN -> CLOSED`
   - `Enrollment`: `PENDING -> CONFIRMED`, `PENDING -> CANCELLED`, `CONFIRMED -> CANCELLED`
   - `Waitlist`: `WAITING -> PROMOTED`, `WAITING -> CANCELLED`
   - 허용되지 않는 전이는 안정적인 도메인 에러 코드로 실패해야 한다.

3. 정원은 수강 신청 성공 시점에 선점한다.
   - `PENDING`과 `CONFIRMED`는 모두 정원을 점유한다.
   - `CANCELLED`는 정원을 점유하지 않는다.
   - 대기열은 자동 승격되어 신청이 생성되기 전까지 정원을 점유하지 않는다.

4. 동시성 제어는 단순하고 설명 가능하며 테스트 가능해야 한다.
   - PostgreSQL 기본 격리 수준인 `READ COMMITTED`를 사용한다.
   - `occupied_count` 또는 자동 승격이 바뀌는 흐름은 `courses` row 비관적 락으로 직렬화한다.
   - 단일 row 상태 전이는 가드 조건 UPDATE로 처리한다.
   - 락 획득 순서는 `courses -> enrollments -> waitlists`로 고정한다.

5. API 실패 응답은 일관된 ProblemDetail 형식을 사용한다.
   - 도메인 실패는 `ErrorCode`와 `DomainException`으로 표현한다.
   - API 에러 응답에는 안정적인 `code` 속성을 포함한다.
   - Controller는 Entity를 직접 반환하지 않는다.

6. 테스트는 설계의 일부로 취급한다.
   - 도메인 규칙은 단위 테스트로 검증한다.
   - Repository 제약과 Service 트랜잭션은 PostgreSQL 통합 테스트로 검증한다.
   - 동시성 주장은 실제 PostgreSQL 기반 동시성 테스트로 검증한다.

7. 과제 범위를 엄격하게 유지한다.
   - 요구사항이 바뀌지 않는 한 Spring Security, Redis 분산 락, 외부 결제 PG, User CRUD는 도입하지 않는다.
   - 넓은 인프라 추가보다 명확한 로컬 설계 결정을 우선한다.

## 작업 규칙

- 요구사항은 `specs/001-enrollment-system/spec.md`에 둔다.
- 기술 결정은 `specs/001-enrollment-system/plan.md`와 상세 `docs/` 문서를 기준으로 한다.
- 실행 상태는 `specs/001-enrollment-system/tasks.md`에서 추적한다.
- 새 작업 단위를 구현하기 전, 관련 task와 설계 문서를 먼저 확인한다.
- 구현 후에는 가장 좁은 의미 있는 테스트부터 실행하고, 단계 종료 시 더 넓은 검증으로 확장한다.

## 참조 문서

- API 표면: `docs/API.md`
- 데이터 모델: `docs/ERD.md`
- 상태 전이: `docs/STATE_TRANSITIONS.md`
- 동시성: `docs/CONCURRENCY.md`
- 에러 계약: `docs/ERROR_CODES.md`
- 테스트 시나리오: `docs/TEST_SCENARIOS.md`
- 구현 계획: `docs/IMPLEMENTATION_PLAN.md`
