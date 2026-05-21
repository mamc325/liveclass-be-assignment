# BE-A 수강 신청 시스템 구현 계획 (커밋 단위)

> 작성일: 2026-05-21
> 마감: 2026-05-24 23:59 (잔여 ~3일)

---

## 0. 시간 배분

| 일자 | 핵심 작업 |
|---|---|
| **5/21 (수, 잔여)** | Phase 1 |
| **5/22 (목)** | Phase 2 + Phase 3 + Phase 4 절반 |
| **5/23 (금)** | Phase 4 완료 + Phase 5 + Phase 6 Must |
| **5/24 (토)** | Phase 6 Should/Could + Phase 7 + 제출 |

---

## 1. 커밋 규칙 및 빌드 정책

### 메시지 형식
```
<type>(<scope>): <설명>
```

| type | 용도 |
|---|---|
| `feat` | 새 기능/코드 |
| `test` | 테스트만 추가 |
| `chore` | 빌드/설정/패키지 구조 |
| `docs` | 문서 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 변경 |

`scope`: 패키지명 (`course`, `enrollment`, `waitlist`, `common`, `auth`) 또는 `infra`, `db`, `test`, `swagger`.

### 빌드 검증 정책 (현실화)

| 시점 | 명령 | 목적 |
|---|---|---|
| **각 커밋 직전** | `./gradlew test` 또는 해당 모듈 컴파일/테스트 | 최소 compile + test green |
| **Phase 종료 시** | `./gradlew build` | 전체 빌드 + 모든 테스트 + 정적 분석 |
| **최종 제출 직전** | clean docker + `./gradlew clean build && ./gradlew bootRun` | 평가자 환경 재현 |

`./gradlew build`를 매 커밋마다 돌리면 Testcontainers 부팅 비용이 누적되므로 위 정책으로 현실화한다. **"각 커밋은 최소 compile/test green" 원칙은 유지**.

---

## 2. 전체 커밋 개요

| 범주 | 커밋 수 |
|---|---|
| Must + Should | **51** |
| Could 포함 (전체) | **54** |

| Phase | Must+Should 수 | 누적 | 예상 시점 |
|---|---|---|---|
| 1. Foundation | 5 | 5 | 5/21 저녁 |
| 2. Core Domain | 13 | 18 | 5/22 오전~오후 |
| 3. Infrastructure | 6 | 24 | 5/22 오후 |
| 4. Business Services | 10 | 34 | 5/22 저녁 ~ 5/23 오후 |
| 5. Controllers + Swagger | 10 | 44 | 5/23 오후~저녁 |
| 6. Concurrency + Acceptance (Must+Should) | 3 (+Could 3) | 47 (50) | 5/23 저녁 ~ 5/24 오전 |
| 7. Polish & Submission | 4 | 51 (54) | 5/24 오전~오후 |

---

## 3. 명시적 미구현 항목

평가 시 의문 방지를 위해 README에도 명시.

| 항목 | 이유 |
|---|---|
| Spring Security | 과제 명시 허용 + 핵심 평가 무관 |
| Redis 분산 락 | 단일 인스턴스 전제 + 과한 복잡도 |
| 외부 결제 PG 연동 | 과제 명시 (단순 상태 변경) |
| Course 정보 수정 API | 과제 요구사항 외 |
| User CRUD API | 시드 사용 |
| 405 별도 에러 코드 | Spring 기본 ProblemDetail 위임 |
| LOCK_TIMEOUT | 운영 확장 시 검토 |
| 시청 이력 기반 취소 제한 | 별도 도메인 필요 |
| i18n 메시지 | 한국어 고정 |
| 모든 `ErrorCode` 개별 통합 테스트 | Could 우선순위 |

---

## 4. Phase 1: Foundation (5 commits, 5/21 저녁)

| # | type(scope) | 메시지 | 주요 파일 / 비고 |
|---|---|---|---|
| 1 | chore(infra) | docker-compose postgres:16-alpine + healthcheck | `docker-compose.yml`. 버전 고정(`latest` 금지) |
| 2 | chore(test) | Testcontainers 이미지 `postgres:latest` → `postgres:16-alpine` | `TestcontainersConfiguration.java` 수정. **현재 코드 latest로 되어 있어 반드시 변경** |
| 3 | chore(config) | application.yml + application-test.yml + KST 타임존 / Flyway / JPA `validate` | `src/main/resources/`, `src/test/resources/` |
| 4 | feat(db) | Flyway V1 schema — 생성 순서 `users → courses → waitlists → enrollments` (FK 순방향) + partial unique + composite index | `db/migration/V1__init_schema.sql`. ERD 9번 마이그레이션 순서 준수 |
| 5 | feat(db) | Flyway V2 seed users (creator 2명, student 10명) | `db/migration/V2__seed_users.sql`. README에 id 매핑 명시 예정 |

**Phase 1 종료 확인**: `docker compose up -d && ./gradlew bootRun` 성공 → `SELECT count(*) FROM users` = 12.

> 패키지 스켈레톤 단독 커밋은 생략. 각 도메인 파일을 만들면서 패키지가 자연 생성된다.

---

## 5. Phase 2: Core Domain (13 commits, 5/22 오전~오후)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 6 | feat(domain) | enum 4종 (UserRole, CourseStatus, EnrollmentStatus, WaitlistStatus) | |
| 7 | feat(common) | BaseTimeEntity (`@MappedSuperclass`, `createdAt`/`updatedAt` 공통) + JPA Auditing 활성화 | 진짜 코드가 들어 있어 스켈레톤 commit을 대체 |
| 8 | feat(user) | User entity + UserRepository | |
| 9 | feat(course) | Course entity + 도메인 메서드 (`open()`, `close()`, `incrementOccupiedCount()`, `decrementOccupiedCount()`) | `IllegalStateException` 던지기 |
| 10 | test(course) | Course 도메인 단위 (C-U-1~7) | |
| 11 | feat(course) | CourseRepository + `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) | |
| 12 | feat(enrollment) | Enrollment entity + 도메인 메서드 (`confirm(now)`, `cancel(now, deadlineDays)`) | |
| 13 | test(enrollment) | Enrollment 도메인 단위 (E-U-1~7) | |
| 14 | feat(enrollment) | EnrollmentRepository + 가드 조건 native UPDATE (`@Modifying(clearAutomatically=true, flushAutomatically=true)`) | confirm/cancel UPDATE 쿼리 |
| 15 | feat(waitlist) | Waitlist entity + 도메인 메서드 (`promote(now)`, `cancel(now)`) | |
| 16 | test(waitlist) | Waitlist 도메인 단위 (W-U-1~4) | |
| 17 | feat(waitlist) | WaitlistRepository + 가드 조건 UPDATE | promote/cancel native UPDATE |
| 18 | test(repo) | Partial unique 통합 테스트 (R-U-1~5) + 정렬 정책 검증 (R-S-1, R-S-2) | `@SpringBootTest` + Testcontainers |

**Phase 2 종료 확인**: `./gradlew build` 통과. 도메인 단위 + Repository 통합 테스트 green.

---

## 6. Phase 3: Infrastructure (6 commits, 5/22 오후)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 19 | feat(common) | ErrorCode enum (17개, `HttpStatus` 기반) | |
| 20 | feat(common) | DomainException 단일 클래스 (`super(message)` 위임) | `customMessage` 필드 없이 단순화 |
| 21 | feat(common) | GlobalExceptionHandler — DomainException + Bean Validation 7종 + DataIntegrityViolation constraint name 분기 + Throwable | |
| 22 | test(common) | GlobalExceptionHandler 단위 테스트 (INTERNAL_ERROR mock 포함) | Controller 없이 검증 가능 |
| 23 | feat(config) | ClockConfig — `Clock.system(Asia/Seoul)` Bean | |
| 24 | feat(auth) | CurrentUser 애노테이션 + ArgumentResolver + AuthUser 객체 + PageResponse 공통 DTO + WebConfig 등록 | Resolver는 단위/슬라이스 테스트로만 검증 |

**Phase 3 종료 확인**: `./gradlew build`. GlobalExceptionHandler 단위 테스트와 ArgumentResolver 단위 테스트 통과.

> **"헤더 누락 시 401" end-to-end 검증은 Controller가 생기는 Phase 5 이후로 이동**. Phase 3에서는 단위 테스트 수준만 확보.

---

## 7. Phase 4: Business Services (10 commits, 5/22 저녁 ~ 5/23 오후)

### 7.1 CourseService

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 25 | feat(course) | CourseService.register + DTO (`CourseCreateRequest` with `@Valid` 어노테이션) | DTO 생성 시점에 Bean Validation 같이 적용 |
| 26 | feat(course) | CourseService.open / close (`Clock` 주입, 가드 조건 UPDATE) | |
| 27 | feat(course) | CourseService.list / detail (Pageable, status 필터) | |
| 28 | test(course) | CourseService 통합 테스트 (S-C-1~5) | |

### 7.2 EnrollmentService (핵심)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 29 | feat(enrollment) | EnrollmentService.apply — course 락 + 정원 분기 + 중복 검증 + ENROLLED/WAITLISTED 응답 | 도메인 핵심 흐름 |
| 30 | test(enrollment) | apply 통합 (S-E-1~5) | |
| 31 | feat(enrollment) | EnrollmentService.confirm (가드 조건 UPDATE) | |
| 32 | feat(enrollment) | EnrollmentService.cancel — course 락 + enrollment 락 + 자동 승격 + fallback (다음 후보 시도) | 가장 복잡한 흐름 |
| 33 | test(enrollment) | confirm + cancel + 자동 승격 + fallback 통합 (S-E-6~13, S-P-1~2) | |

### 7.3 WaitlistService

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 34 | feat(waitlist) | WaitlistService.cancel + 통합 테스트 (S-W-1, S-W-2) | |

**Phase 4 종료 확인**: `./gradlew build`. 7절(Service 통합) Must/Should 시나리오 모두 green.

---

## 8. Phase 5: Controllers + Swagger (10 commits, 5/23 오후~저녁)

### 8.1 CourseController

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 35 | feat(course) | CourseController POST + open/close + DTO Bean Validation | DTO에 `@Valid`, `@NotBlank`, `@Min` 등 적용 |
| 36 | feat(course) | CourseController list/detail + Creator-only 목록 (GET /courses/{id}/enrollments, /waitlists) | |
| 37 | test(course) | CourseController 통합 (happy + 권한 + 소유권 + ProblemDetail + V-1~6 중 course 관련) | |

### 8.2 EnrollmentController (apply/confirm/cancel 분리)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 38 | feat(enrollment) | EnrollmentController.apply (POST /courses/{id}/enrollments) + DTO Validation | |
| 39 | feat(enrollment) | EnrollmentController.confirm (POST /enrollments/{id}/confirm) | |
| 40 | feat(enrollment) | EnrollmentController.cancel (POST /enrollments/{id}/cancel) | |
| 41 | test(enrollment) | EnrollmentController 통합 (apply/confirm/cancel + 권한 + ProblemDetail) | |

### 8.3 WaitlistController + Me

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 42 | feat(waitlist) | WaitlistController + 통합 테스트 | |
| 43 | feat(me) | MeController (GET /me/enrollments, /me/waitlists) + 페이지네이션 + 통합 테스트 | |

### 8.4 Swagger (당김)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 44 | chore(swagger) | Springdoc OpenAPI 의존성 + OpenApiConfig + Swagger UI 접근 확인 | Phase 7 제출 직전이 아닌 **Controller 완성 직후로 당김** — Swagger 설정 문제를 일찍 발견 |

**Phase 5 종료 확인**: `./gradlew build`. 13개 엔드포인트 모두 MockMvc happy + 권한 + ProblemDetail green. `/swagger-ui.html` 접근 시 13개 엔드포인트 표시.

---

## 9. Phase 6: Concurrency + Acceptance (5/23 저녁 ~ 5/24 오전)

### 9.1 Must (필수)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 45 | test(concurrency) | CC-1 마지막 자리 50명 경합 (capacity=10) — **setup에서 학생 50명 동적 생성** (시드 10명만으로 부족) | latch 기반 |

### 9.2 Should (인수 테스트)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 46 | test(acceptance) | AT-1 + AT-2 (등록→open→신청→confirm→조회 / 정원1명+대기+자동 승격) | |
| 47 | test(acceptance) | AT-3 + AT-4 (CLOSED 강의 취소 / Clock mock 기간 초과) | |

### 9.3 Could (시간 여유 시)

| # | type(scope) | 메시지 |
|---|---|---|
| 48 | test(concurrency) | CC-2A/B/C confirm-cancel race |
| 49 | test(concurrency) | CC-3 자동 승격 ↔ 대기 취소 race |
| 50 | test(concurrency) | CC-4A/B + CC-5/6/7 잔여 동시성 |

**Phase 6 종료 확인 (Must 기준)**: CC-1 + AT-1~4 green. Could는 시간 여유 봐서.

---

## 10. Phase 7: Polish & Submission (4 commits, 5/24 오전~오후)

| # | type(scope) | 메시지 | 비고 |
|---|---|---|---|
| 48 (또는 51) | docs(readme) | README 초안 — 개요/기술스택/요구사항해석/설계결정/미구현 | 과제 템플릿 항목 |
| 49 (또는 52) | docs(readme) | README — 실행 방법 (docker compose + gradle) + Seed 사용자 매핑 + API curl 예시 | |
| 50 (또는 53) | docs(readme) | README — AI 활용 내역 + docs/* 링크 (ERD/API/STATE_TRANSITIONS 등) | |
| 51 (또는 54) | chore | 최종 검수 (clean docker + clean build + bootRun + Swagger 확인) | 필요 시 |

> 번호가 두 개인 이유: Could (48~50) 포함 여부에 따라 시작 번호가 달라짐. Must+Should만이면 48부터, Could 포함이면 51부터.

**Phase 7 종료 확인 (제출 직전)**:
- clean Docker 환경에서 `docker compose up -d && ./gradlew clean build` 그린
- `./gradlew bootRun` 후 `/swagger-ui.html` 접근 가능
- README의 curl 예시 모두 동작
- AI 활용 내역 채워짐
- **제출 페이지에 GitHub URL 입력**

---

## 11. 위험 대응

| 위험 | 대응 |
|---|---|
| Phase 2에서 도메인 메서드 시그니처가 흔들림 | ERD/STATE_TRANSITIONS 다시 확인. 변경 시 docs를 먼저 수정 후 코드 |
| Phase 4의 `EnrollmentService.cancel` 자동 승격이 시간 잡아먹음 | 자동 승격을 별도 메서드로 분리 → 단순 케이스부터 통과시키고 fallback 루프는 마지막에 |
| Phase 5 Controller 커밋이 많아 시간 부족 | Creator-only 목록(#36 일부)이나 페이지네이션 디테일은 후순위로 |
| Phase 6 CC-1이 flaky | latch 타이밍 조정, threadpool 크기 ↑, await 시간 ↑. CC-1만 안정화하고 나머지는 시간 봐서 |
| Swagger 설정이 꼬임 | Phase 5 마지막에 #44로 당겨놨으니 일찍 발견. 안 풀리면 manual API docs로 대체 |
| Phase 7 README 시간 부족 | docs/* 링크로 미루고 README는 최소 템플릿만. 평가자에게는 "상세는 docs/ 참조" 안내 |

---

## 12. 문서 ↔ 코드 매핑

| 코드 위치 | 명세 문서 |
|---|---|
| Flyway V1 DDL | `docs/ERD.md` |
| Entity 도메인 메서드 | `docs/STATE_TRANSITIONS.md` |
| Repository 쿼리/락 | `docs/STATE_TRANSITIONS.md` 8절, `docs/CONCURRENCY.md` 6절 |
| Service 트랜잭션 흐름 | `docs/STATE_TRANSITIONS.md` 6·7·11절, `docs/CONCURRENCY.md` 6.4·6.5 |
| Controller API | `docs/API.md` |
| `ErrorCode` / Handler | `docs/ERROR_CODES.md` |
| Test 클래스 | `docs/TEST_SCENARIOS.md` (시나리오 ID 주석으로 추적) |

---

## 13. 시작 체크리스트

- [ ] 모든 설계 문서가 commit + push 됨
- [ ] `docker compose up -d`로 PostgreSQL 띄울 수 있음
- [ ] `./gradlew build`가 현재 통과
- [ ] IDE에 프로젝트 import 완료
- [ ] 본 구현 계획 이해 + 동의
- [ ] **Phase 1 #2에서 Testcontainers `postgres:latest` → `postgres:16-alpine` 수정 필요 인지**
- [ ] **CC-1은 학생 50명을 setup에서 동적 생성한다는 점 인지**
- [ ] **Phase 3 종료 확인은 Controller 이후로 미뤘다는 점 인지**

---

## 14. 피드백 반영 요약 (이 버전에서 조정된 항목)

본 계획은 직전 리뷰 피드백 8개를 반영한 v2.

| # | 조정 사항 |
|---|---|
| 1 | 커밋 수 정확히 표기 — Must+Should 51개, Could 포함 54개 |
| 2 | V1 schema 생성 순서 `users → courses → waitlists → enrollments` 명시 |
| 3 | Testcontainers 이미지 `postgres:latest → postgres:16-alpine` 별도 커밋(#2) |
| 4 | Phase 3 종료 확인을 Controller 이후로 이동, 단위 테스트만 확보 |
| 5 | DTO Bean Validation을 Controller DTO 생성 시점에 같이 적용 (#35, #38) |
| 6 | CC-1에 학생 50명 동적 생성 명시 (#45) |
| 7 | EnrollmentController를 apply/confirm/cancel 3개 커밋으로 분리 (#38~40) |
| 8 | 빌드 정책을 "매 커밋 build" → "커밋별 test, Phase 종료 build, 최종 clean build"로 현실화 |
| 추가 | 패키지 스켈레톤 단독 커밋 제거, BaseTimeEntity 같은 실제 코드 커밋(#7)으로 대체 |
| 추가 | Springdoc을 Phase 7 → Phase 5 끝(#44)으로 당겨 일찍 검증 |
