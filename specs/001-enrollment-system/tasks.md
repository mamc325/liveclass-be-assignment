# 작업 목록: 수강 신청 시스템

**기능**: `001-enrollment-system`
**참조 문서**: `docs/IMPLEMENTATION_PLAN.md`, `docs/TEST_SCENARIOS.md`, `docs/API.md`

`[P]`는 충돌 범위만 명확하면 병렬 진행 가능한 작업이다.

## 0단계 - Spec Kit 기준 문서

- [x] T001 `.specify/memory/constitution.md`에 프로젝트 원칙 작성
- [x] T002 `specs/001-enrollment-system/spec.md`에 기능 명세 작성
- [x] T003 `specs/001-enrollment-system/plan.md`에 구현 계획 작성
- [x] T004 `specs/001-enrollment-system/tasks.md`에 작업 목록 작성
- [x] T005 기존 상세 `docs/` 문서를 Spec Kit 진입점에서 참조

## 1단계 - 기반 작업

- [x] T006 Java 21 기반 Gradle Spring Boot 프로젝트 설정
- [x] T007 PostgreSQL 런타임과 Testcontainers 테스트 의존성 설정
- [x] T008 Docker Compose PostgreSQL 설정 추가
- [x] T009 application/test profile 설정 추가
- [x] T010 users, courses, waitlists, enrollments용 Flyway V1 스키마 추가
- [x] T011 Flyway V2 시드 사용자 추가

## 2단계 - 핵심 도메인

- [x] T012 `UserRole`, `CourseStatus`, `EnrollmentStatus`, `WaitlistStatus` 추가
- [x] T013 `BaseTimeEntity`와 JPA Auditing 추가
- [x] T014 `User` 엔티티와 Repository 추가
- [x] T015 `Course` 엔티티와 생명주기/정원 메서드 추가
- [x] T016 Course 도메인 테스트 추가
- [x] T017 `CourseRepository.findByIdForUpdate` 추가
- [x] T018 `Enrollment` 엔티티와 상태 전이 메서드 추가
- [x] T019 Enrollment 도메인 테스트 추가
- [x] T020 `EnrollmentRepository` 가드 조건 UPDATE 쿼리 추가
- [x] T021 `Waitlist` 엔티티와 상태 전이 메서드 추가
- [x] T022 Waitlist 도메인 테스트 추가
- [x] T023 `WaitlistRepository` 가드 조건 UPDATE 쿼리 추가
- [x] T024 partial unique index와 정렬 정책 Repository 통합 테스트 추가

## 3단계 - 공통 인프라

- [x] T025 `ErrorCode` 카탈로그 추가
- [x] T026 `DomainException` 추가
- [x] T027 `GlobalExceptionHandler` 추가
- [x] T028 전역 예외 처리 테스트 추가
- [x] T029 `Clock` Bean 추가
- [x] T030 `CurrentUser`, `AuthUser`, ArgumentResolver, WebConfig, `PageResponse` 추가

## 4단계 - 비즈니스 서비스

- [x] T031 Course 등록 DTO와 Service 메서드 추가
- [x] T032 Course open/close Service 메서드 추가
- [x] T033 Course 목록/상세 Service 메서드 추가
- [x] T034 Course Service 통합 테스트 추가
- [x] T035 Course 락, 중복 검증, 대기 분기를 포함한 Enrollment apply Service 흐름 추가
- [x] T036 Enrollment apply 통합 테스트 추가
- [x] T037 Enrollment confirm 가드 조건 UPDATE 흐름 추가
- [x] T038 Course/Enrollment 락과 자동 승격을 포함한 Enrollment cancel 흐름 추가
- [x] T039 Enrollment confirm/cancel/자동 승격 통합 테스트 추가
- [x] T040 Waitlist cancel Service와 통합 테스트 추가

## 5단계 - API 표면

- [x] T041 CourseController create/open/close 엔드포인트 추가
- [x] T042 CourseController list/detail 엔드포인트 추가
- [x] T043 CourseController 강의별 수강생/대기자 목록 엔드포인트 추가
- [x] T044 CourseController 통합 테스트 추가
- [ ] T045 `EnrollmentController` 신청 엔드포인트 추가: `POST /api/courses/{id}/enrollments`
- [ ] T046 `EnrollmentController` 결제 확정 엔드포인트 추가: `POST /api/enrollments/{id}/confirm`
- [ ] T047 `EnrollmentController` 취소 엔드포인트 추가: `POST /api/enrollments/{id}/cancel`
- [ ] T048 `EnrollmentController` 성공, 역할, 소유권, ProblemDetail 실패 통합 테스트 추가
- [ ] T049 `WaitlistController` 대기 취소 엔드포인트 추가: `POST /api/waitlists/{id}/cancel`
- [ ] T050 `WaitlistController` 통합 테스트 추가
- [ ] T051 `MeController` 내 수강 신청 목록 엔드포인트 추가: `GET /api/me/enrollments`
- [ ] T052 `MeController` 내 대기 목록 엔드포인트 추가: `GET /api/me/waitlists`
- [ ] T053 `MeController` 통합 테스트 추가
- [ ] T054 Springdoc OpenAPI 의존성과 설정 추가
- [ ] T055 Springdoc을 포함한다면 Swagger UI에 공개 API가 표시되는지 확인

## 6단계 - 동시성 및 인수 테스트

- [ ] T056 마지막 자리 경합 테스트 추가: 여러 수강생이 동시에 신청해도 활성 신청 수가 정원을 넘지 않음
- [ ] T057 [P] 크리에이터 등록/open, 수강생 신청, confirm, 조회 흐름 인수 테스트 추가
- [ ] T058 [P] 정원 초과, 대기 등록, 취소, 자동 승격 흐름 인수 테스트 추가
- [ ] T059 [P] CLOSED 강의 취소 시 자동 승격이 발생하지 않는 인수 테스트 추가
- [ ] T060 [P] 고정 `Clock`을 사용한 취소 기간 초과 인수 테스트 추가
- [ ] T061 [P] 선택: confirm/cancel race 테스트 추가
- [ ] T062 [P] 선택: waitlist promotion/cancel race 테스트 추가

## 7단계 - 제출 정리

- [ ] T063 README에 실행 방법, 시드 사용자, API 예시, 설계 결정 정리
- [ ] T064 명시적 미구현 항목과 범위 제외 항목 문서화
- [ ] T065 마지막으로 변경한 API 작업 단위의 집중 테스트 실행
- [ ] T066 `./gradlew clean build` 실행
- [ ] T067 로컬 PostgreSQL 기준 애플리케이션 기동 확인
- [ ] T068 docs/spec/tasks 일관성 최종 점검

## 다음 추천 작업 단위

T045-T048부터 시작한다.

1. `EnrollmentController`를 추가한다.
2. apply/confirm/cancel을 MockMvc로 검증한다.
3. 기존 `EnrollmentService` DTO와 ProblemDetail 처리를 재사용한다.

이 작업은 이미 구현된 핵심 수강 신청 기능을 API로 노출하며, 현재 저장소에서 가장 큰 노출 영역의 빈틈을 닫는다.
