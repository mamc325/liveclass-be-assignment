# 구현 계획: 수강 신청 시스템

**기능**: `001-enrollment-system`
**작성일**: 2026-05-21

## 요약

명시적인 상태 머신, PostgreSQL 기반 정합성 보장, 정원 변경 흐름의 비관적 락, 단일 row 상태 전이의 가드 조건 UPDATE, Testcontainers 기반 검증을 갖춘 Spring Boot 수강 신청 API를 구현한다.

이 저장소에는 이미 `docs/` 아래 상세 설계 문서가 존재한다. 이 Spec Kit 계획서는 구현 진입점 역할을 하며, 더 자세한 근거는 기존 문서를 참조한다.

## 기술 컨텍스트

- 언어: Java 21
- 프레임워크: Spring Boot 3.5.14
- 영속성: Spring Data JPA, Hibernate, Flyway
- 데이터베이스: PostgreSQL 16
- 테스트 데이터베이스: Testcontainers PostgreSQL
- API 검증: Jakarta Bean Validation
- 에러 형식: Spring `ProblemDetail` + 도메인 `code`
- 시간: 주입받은 `Clock`, KST 설정
- 빌드: Gradle

## 프로젝트 원칙 점검

- 데이터 정합성 우선: partial unique index, `occupied_count`, 서비스 레벨 락으로 보장한다.
- 명시적 상태 전이: 도메인 메서드와 Repository 가드 조건 UPDATE로 구현한다.
- 신청 생성 시점 정원 선점: `PENDING`과 `CONFIRMED` 모두 정원을 점유한다.
- 설명 가능한 동시성: 정원 변경 흐름은 `Course` row 비관적 락, 결제 확정/대기 취소는 가드 조건 UPDATE를 사용한다.
- 일관된 API 에러: `ErrorCode`, `DomainException`, `GlobalExceptionHandler`를 사용한다.
- 위험도에 맞는 테스트: 도메인, Repository, Service, Controller, 동시성, 인수 테스트를 `tasks.md`에서 추적한다.

## 참조 문서

- 요구사항과 API 계약: `docs/API.md`
- 스키마와 엔티티 관계: `docs/ERD.md`
- 상태 머신: `docs/STATE_TRANSITIONS.md`
- 동시성 전략: `docs/CONCURRENCY.md`
- 에러 카탈로그: `docs/ERROR_CODES.md`
- 테스트 매트릭스: `docs/TEST_SCENARIOS.md`
- 커밋/단계 계획: `docs/IMPLEMENTATION_PLAN.md`

## 아키텍처

```text
src/main/java/com/example/enrollment_system
├── common
│   ├── auth
│   ├── error
│   └── web
├── config
├── course
├── enrollment
├── user
└── waitlist
```

Controller는 DTO만 노출한다. Service는 트랜잭션, 역할 외 권한 검증, 도메인 흐름 조합을 담당한다. Repository는 조회 쿼리, 비관적 락 조회, 가드 조건 UPDATE를 제공한다.

## 데이터 모델

핵심 테이블은 다음과 같다.

- `users`
- `courses`
- `waitlists`
- `enrollments`

DB 제약은 설계의 일부다.

- `uq_active_enrollment_per_user_course`
- `uq_waiting_waitlist_per_user_course`
- `uq_enrollments_promoted_from_waitlist`
- 모든 상태 컬럼에 대한 check/status 제약

상세 내용은 `specs/001-enrollment-system/data-model.md`와 `docs/ERD.md`를 참조한다.

## API 계약

기준 URL: `/api`

필수 호출자 헤더:

- `X-USER-ID`

계획된 엔드포인트:

- `POST /api/courses`
- `POST /api/courses/{id}/open`
- `POST /api/courses/{id}/close`
- `GET /api/courses`
- `GET /api/courses/{id}`
- `POST /api/courses/{id}/enrollments`
- `POST /api/enrollments/{id}/confirm`
- `POST /api/enrollments/{id}/cancel`
- `GET /api/me/enrollments`
- `POST /api/waitlists/{id}/cancel`
- `GET /api/me/waitlists`
- `GET /api/courses/{id}/enrollments`
- `GET /api/courses/{id}/waitlists`

상세 내용은 `specs/001-enrollment-system/contracts/api.md`와 `docs/API.md`를 참조한다.

## 동시성 계획

- PostgreSQL 기본 격리 수준 `READ COMMITTED`를 유지한다.
- 정원이 바뀌는 신청/취소 흐름은 `CourseRepository.findByIdForUpdate()`를 사용한다.
- 결제 확정과 대기 취소는 `UPDATE ... WHERE status = expected` 가드 조건 UPDATE를 사용한다.
- 활성 중복 방지의 최종 안전망으로 DB partial unique index를 사용한다.
- 자동 승격 중 대기자가 동시에 취소되면 다음 후보로 fallback한다.

## 현재 구현 상태

구현 완료:

- Flyway 스키마와 시드 데이터.
- 핵심 도메인 엔티티와 enum.
- 에러 카탈로그와 전역 예외 처리.
- 인증 사용자 ArgumentResolver.
- Course, Enrollment, Waitlist 서비스.
- CourseController.
- 도메인, Repository, Service, CourseController 테스트.

남은 핵심 작업:

- EnrollmentController.
- WaitlistController.
- MeController.
- 평가자 편의를 위한 Springdoc/OpenAPI 설정.
- 마지막 자리 경합 동시성 테스트.
- API 인수 테스트.
- README/제출 정리.

## 검증 전략

점진적으로 실행한다.

1. 변경한 작업 단위의 좁은 단위/통합 테스트.
2. 관련 패키지 테스트. 예: `./gradlew test --tests '*Enrollment*'`
3. 전체 `./gradlew test`.
4. 최종 제출 전 `./gradlew clean build`.

Testcontainers 테스트는 Docker 실행 상태가 필요할 수 있다.
