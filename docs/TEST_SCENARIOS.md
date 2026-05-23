# BE-A 수강 신청 시스템 테스트 시나리오 설계

> 대상: 라이브클래스 BE-A 채용 과제

---

## 1. 문서 목적

본 문서는 구현 검증을 위한 **테스트 전략과 시나리오를 단일 진실 원천(SSoT)** 으로 정리한다.

다른 문서에 흩어진 테스트 관련 내용은 본 문서에서 통합·참조한다.

- 동시성 테스트 — `docs/CONCURRENCY.md` 10번 (구체 코드 패턴은 그쪽, 본 문서는 시나리오 일람)
- 예외 코드별 테스트 매트릭스 — `docs/ERROR_CODES.md` 9번 (코드별 시나리오는 그쪽, 본 문서는 레이어/방법론)
- 상태 전이 검증 — `docs/STATE_TRANSITIONS.md` (각 전이의 선결 조건이 테스트의 명세)

본 문서는 추가로 다음을 다룬다:
- **도메인 단위 테스트** (Entity 상태 전이 메서드)
- **Repository 통합 테스트** (제약, 인덱스, 락 동작)
- **컨트롤러 통합 테스트** (MockMvc + ProblemDetail 응답)
- **API 인수 테스트** (여러 API를 연결한 사용자 흐름)
- 테스트 데이터/시드, `Clock` mock, 격리 정책, 명명 규칙, 우선순위

---

## 2. 테스트 전략 개요

세 축으로 구성한다.

| 축 | 도구 | 목적 | 데이터베이스 |
|---|---|---|---|
| **단위 테스트** | JUnit 5 + AssertJ | 도메인 엔티티 상태 전이 규칙 검증 (POJO) | 없음 |
| **통합 테스트** | `@SpringBootTest` + Testcontainers + MockMvc | Repository·서비스·컨트롤러 전 흐름 검증 | 실제 PostgreSQL (Testcontainers) |
| **동시성 테스트** | JUnit 5 + Testcontainers + `ExecutorService` + `CountDownLatch` | 다중 트랜잭션 race 검증 | 실제 PostgreSQL (Testcontainers) |

H2는 사용하지 않는다. partial unique index, row lock 등 PostgreSQL 고유 동작이 핵심이므로 실제 DB로만 검증.

---

## 3. 테스트 레이어와 책임

| 레이어 | 검증 내용 | 본 문서 절 |
|---|---|---|
| 도메인 엔티티 단위 | 상태 전이 메서드의 선결 조건 / 부수 효과 | 5 |
| Repository 통합 | partial unique index / `@Lock(PESSIMISTIC_WRITE)` / 정렬 정책 | 6 |
| 서비스 통합 | 트랜잭션 흐름 / 자동 승격 / `occupied_count` 정합성 | 7 |
| 컨트롤러 통합 | API 표면 / 권한·소유권 검증 / ProblemDetail 응답 | 8 |
| **API 인수** | **여러 API를 연결한 사용자 흐름** (강의 등록 → 모집 시작 → 신청 → 결제 → 조회) | **8.5** |
| 동시성 통합 | 마지막 자리 경합 / confirm-cancel race / 승격-대기 취소 race | 9 |
| 예외 매핑 | 17개 `ErrorCode` 각각의 응답 검증 | 10 |

---

## 4. 테스트 인프라

### 4.1 의존성

`build.gradle`에 이미 포함된 항목.

- `spring-boot-starter-test` — JUnit 5, AssertJ, MockMvc, Mockito
- `spring-boot-testcontainers` — Spring과 Testcontainers 연동
- `testcontainers/junit-jupiter`, `testcontainers/postgresql` — PostgreSQL 컨테이너

### 4.2 공통 베이스 클래스 (제안)

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
abstract class IntegrationTestBase {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected DataSource dataSource;

    @AfterEach
    void cleanup() {
        // 각 테스트 후 명시적 데이터 정리 (13절 참조)
    }
}
```

### 4.3 컨테이너 재사용

같은 빌드 안에서 PostgreSQL 컨테이너를 재사용하기 위해 `static` 컨테이너 + Spring `@ServiceConnection` 조합 사용. 매 테스트 클래스마다 컨테이너를 새로 띄우면 빌드 시간이 폭증.

---

## 5. 도메인 단위 테스트 시나리오

엔티티의 상태 전이 메서드(`open()`, `close()`, `confirm()`, `cancel()`, `promote()`)는 순수 POJO 단위 테스트로 검증한다.

### 5.0 예외 타입 정책

- **Entity 단위 테스트**: 도메인 메서드가 던지는 예외는 `IllegalStateException`(또는 도메인 의미를 가진 예외 서브타입)으로 검증한다. Entity는 `ErrorCode`/`DomainException`을 알지 못한다.
- **Service/API 테스트**: 서비스가 Entity 예외를 받아 `DomainException(ErrorCode.X)`로 감싸 던진다. 컨트롤러 응답의 `code` 필드와 ProblemDetail로 검증 (`docs/ERROR_CODES.md` 참조).

이 분리는 Entity의 도메인 순수성과 ErrorCode/ProblemDetail의 인터페이스 책임을 분리하기 위함이다.

### 5.1 Course

| # | 시나리오 | 기대 결과 |
|---|---|---|
| C-U-1 | `DRAFT` 상태에서 `open()` 호출 | status가 `OPEN`으로 전이, `updatedAt` 갱신 |
| C-U-2 | `OPEN`/`CLOSED` 상태에서 `open()` 호출 | `IllegalStateException` |
| C-U-3 | `OPEN` 상태에서 `close()` 호출 | status가 `CLOSED`로 전이 |
| C-U-4 | `DRAFT`/`CLOSED` 상태에서 `close()` 호출 | `IllegalStateException` |
| C-U-5 | `incrementOccupiedCount()` 호출 | `occupiedCount += 1` |
| C-U-6 | `decrementOccupiedCount()` 호출 시 `occupiedCount == 0` | `IllegalStateException` (음수 방지) |
| C-U-7 | `occupiedCount == capacity` 상태에서 `incrementOccupiedCount()` | `IllegalStateException` (정원 초과 방지) |

### 5.2 Enrollment

| # | 시나리오 | 기대 결과 |
|---|---|---|
| E-U-1 | `PENDING` 상태에서 `confirm()` 호출 | status가 `CONFIRMED`로 전이, `confirmedAt` 기록 |
| E-U-2 | `CONFIRMED`/`CANCELLED` 상태에서 `confirm()` 호출 | `IllegalStateException` |
| E-U-3 | `PENDING` 상태에서 `cancel(now, deadlineDays)` 호출 | status `CANCELLED`, `cancelledAt` 기록 |
| E-U-4 | `CONFIRMED` 상태 + `now <= confirmedAt + deadlineDays`에서 `cancel()` | `CANCELLED`로 전이 |
| E-U-5 | `CONFIRMED` 상태 + `now > confirmedAt + deadlineDays`에서 `cancel()` | `IllegalStateException` (기간 초과) |
| E-U-6 | `CANCELLED` 상태에서 `cancel()` 호출 | `IllegalStateException` (이미 취소) |
| E-U-7 | promoted creation 생성 시 `promotedFromWaitlistId` 설정 | 설정값 보유, 이후 변경 불가 |

### 5.3 Waitlist

| # | 시나리오 | 기대 결과 |
|---|---|---|
| W-U-1 | `WAITING` 상태에서 `promote()` 호출 | status `PROMOTED`, `promotedAt` 기록 |
| W-U-2 | `PROMOTED`/`CANCELLED` 상태에서 `promote()` 호출 | `IllegalStateException` |
| W-U-3 | `WAITING` 상태에서 `cancel()` 호출 | status `CANCELLED`, `cancelledAt` 기록 |
| W-U-4 | `PROMOTED`/`CANCELLED` 상태에서 `cancel()` 호출 | `IllegalStateException` |

### 5.4 작성 패턴 예시

```java
class EnrollmentDomainTest {

    @Test
    void confirm은_PENDING_상태에서만_허용() {
        Enrollment e = newEnrollment(EnrollmentStatus.PENDING);
        e.confirm(fixedNow());
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(e.getConfirmedAt()).isEqualTo(fixedNow());
    }

    @Test
    void confirm은_PENDING이_아니면_IllegalStateException() {
        Enrollment e = newEnrollment(EnrollmentStatus.CANCELLED);
        assertThatThrownBy(() -> e.confirm(fixedNow()))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

---

## 6. Repository 통합 테스트 시나리오

Repository 통합 테스트는 원칙적으로 **`@SpringBootTest` + Testcontainers PostgreSQL**을 사용한다. `@DataJpaTest`도 가능하지만 기본적으로 embedded test DB(H2)로 자동 대체될 수 있고, Flyway migration·PostgreSQL partial index·Service와 동일한 profile 구성을 보장하기 어렵다. `@DataJpaTest`를 쓰려면 반드시 `@AutoConfigureTestDatabase(replace = NONE)`를 명시한다.

### 6.1 Partial Unique Index

| # | 시나리오 | 기대 결과 |
|---|---|---|
| R-U-1 | 동일 (user, course)에 `PENDING` enrollment 2건 INSERT | 두 번째 INSERT가 `DataIntegrityViolationException` 발생 (`uq_active_enrollment_per_user_course`) |
| R-U-2 | 동일 (user, course)에 `PENDING` + `CANCELLED` enrollment 각 1건 | 정상 INSERT (partial unique는 CANCELLED 제외) |
| R-U-3 | 동일 (user, course)에 `WAITING` waitlist 2건 INSERT | 두 번째 INSERT가 violation (`uq_waiting_waitlist_per_user_course`) |
| R-U-4 | 동일 `promoted_from_waitlist_id`를 가진 enrollment 2건 INSERT | violation (`uq_enrollments_promoted_from_waitlist`) |
| R-U-5 | `promoted_from_waitlist_id = NULL` enrollment 다수 INSERT | 정상 (partial unique는 NULL 제외) |

### 6.2 PESSIMISTIC_WRITE 락 동작 (선택)

> `SELECT FOR UPDATE` 자체의 블로킹 동작은 PostgreSQL 기본 기능이므로 필수 테스트로 두지 않고, **CC-1 마지막 자리 경합 테스트(9절)를 통해 간접 검증**한다. 별도 락 대기 테스트는 시간 여유가 있을 때 선택적으로 작성한다.

선택적으로 작성한다면 다음과 같이 latch 기반으로 구성한다 (wall-clock 측정은 flaky).

| # | 시나리오 | 기대 결과 |
|---|---|---|
| R-L-1 | T1이 course에 `SELECT FOR UPDATE` 후 latch에서 대기. T2가 동일 course에 `SELECT FOR UPDATE` 시도 | T2의 future가 일정 시점에 미완료 상태임을 확인 → T1 release 후 future 완료 |
| R-L-2 | T1과 T2가 서로 다른 course에 동시 `SELECT FOR UPDATE` | 두 future 모두 즉시 완료 (대기 없음) |

구현 패턴 — `CountDownLatch` + `CompletableFuture.supplyAsync`로 두 트랜잭션을 별도 스레드에서 실행하고, `future.isDone()`으로 블로킹 여부 검증.

### 6.3 정렬 정책 검증

본 테스트는 인덱스 사용 여부 자체를 검증하기보다, **API/Repository에서 정의한 정렬 기준이 안정적으로 적용되는지** 검증한다. 실제 인덱스 사용 여부는 필요 시 `EXPLAIN ANALYZE`로 별도 확인한다.

| # | 시나리오 | 기대 결과 |
|---|---|---|
| R-S-1 | 동일 `created_at`인 enrollment 다수에 대해 `created_at DESC, id DESC` 정렬 조회 | id 기준 안정적 페이지네이션 (동률 시 id로 결정적) |
| R-S-2 | waitlist를 `created_at ASC, id ASC`로 조회 | FIFO 순서 보장 |

---

## 7. 서비스 통합 테스트 시나리오

`@SpringBootTest` + Testcontainers. 서비스 메서드를 직접 호출하고 DB 상태 + 반환값 검증.

### 7.1 Course 라이프사이클

| # | 시나리오 | 기대 결과 |
|---|---|---|
| S-C-1 | 강의 등록 | `Course(DRAFT, occupiedCount=0)` 생성 |
| S-C-2 | `DRAFT → open` | `OPEN`으로 전이 |
| S-C-3 | `OPEN → close` | `CLOSED`로 전이 |
| S-C-4 | `DRAFT → close` 시도 | `INVALID_TRANSITION` 도메인 예외 |
| S-C-5 | 본인 강의 아닌 사용자가 `open` 시도 | `NOT_COURSE_OWNER` |

### 7.2 Enrollment 라이프사이클

| # | 시나리오 | 기대 결과 |
|---|---|---|
| S-E-1 | 정원 내 신청 | `Enrollment(PENDING)` 생성, `occupiedCount += 1`, outcome=ENROLLED |
| S-E-2 | 정원 초과 신청 | `Waitlist(WAITING)` 생성, `occupiedCount` 변화 없음, outcome=WAITLISTED |
| S-E-3 | `DRAFT` 강의에 신청 | `COURSE_NOT_OPEN` |
| S-E-4 | 활성 신청 중복 | `DUPLICATE_ENROLLMENT` |
| S-E-5 | 활성 대기 중복 | `DUPLICATE_WAITLIST` |
| S-E-6 | `PENDING → confirm` | `CONFIRMED`, `confirmedAt` 기록, `occupiedCount` 불변 |
| S-E-7 | `PENDING → cancel` (대기자 없음) | `CANCELLED`, `occupiedCount -= 1`, 자동 승격 없음 |
| S-E-8 | `PENDING → cancel` (대기자 있음, OPEN) | `CANCELLED` + Waitlist `PROMOTED` + 새 `Enrollment(PENDING)` 생성. `occupiedCount` 순변동 0 |
| S-E-9 | `CONFIRMED → cancel` 기간 내 (대기자 있음) | `CANCELLED` + 자동 승격. `occupiedCount` 순변동 0 |
| S-E-10 | `CONFIRMED → cancel` 기간 내 (대기자 없음) | `CANCELLED`, `occupiedCount -= 1` 만 반영 |
| S-E-11 | `CONFIRMED → cancel` 기간 초과 | `CANCEL_DEADLINE_EXCEEDED`. **상태와 `occupiedCount` 모두 불변** |
| S-E-12 | `CLOSED` 강의에서 `PENDING → cancel` | 취소 정상 수행, `occupiedCount -= 1`, **자동 승격은 발동 안 됨** |
| S-E-13 | `CLOSED` 강의에서 `PENDING → confirm` | 정상 수행 (CLOSED는 결제 확정을 막지 않음) |

### 7.3 Waitlist 라이프사이클

| # | 시나리오 | 기대 결과 |
|---|---|---|
| S-W-1 | 대기 등록 후 `cancel` | `CANCELLED`, occupiedCount 변화 없음 |
| S-W-2 | 자동 승격된 waitlist가 다시 `cancel` 시도 | `INVALID_STATUS` (이미 PROMOTED) |
| S-W-3 | 자동 승격 시 대기열 FIFO 순서 검증 | 가장 오래된 `WAITING`이 먼저 승격 |

### 7.4 자동 승격 fallback

| # | 시나리오 | 기대 결과 |
|---|---|---|
| S-P-1 | 모든 `WAITING` 후보가 이미 `CANCELLED`인 상태에서 enrollment 취소 | 승격 없이 종료, `occupiedCount -= 1` 만 반영 |
| S-P-2 | 일부 후보가 `CANCELLED`일 때 다음 후보로 fallback | 다음 `WAITING` 후보가 승격 성공 |

---

## 8. 컨트롤러 통합 테스트 시나리오

`MockMvc`로 API 표면을 직접 호출. ProblemDetail 응답 검증 포함.

### 8.1 Happy Path

각 13개 엔드포인트(API.md 5번 표 참조)에 대해 정상 응답 검증.

| 검증 항목 | 방법 |
|---|---|
| HTTP 상태 코드 | `andExpect(status().is(...))` |
| 응답 본문 필드 | `jsonPath` 또는 응답 역직렬화 후 AssertJ |
| 응답 시각 형식 | `+09:00` offset 포함 |
| DB 부수 효과 | 후속 SELECT로 확인 |

### 8.2 권한 검증

| # | 시나리오 | 기대 |
|---|---|---|
| A-1 | `X-USER-ID` 헤더 누락 | 401 `UNAUTHENTICATED` |
| A-2 | 존재하지 않는 user id | 401 `UNAUTHENTICATED` |
| A-3 | STUDENT가 `POST /courses` 호출 | 403 `FORBIDDEN` |
| A-4 | CREATOR가 `POST /enrollments` 호출 | 403 `FORBIDDEN` |
| A-5 | 다른 사용자의 강의에 `POST /open` | 403 `NOT_COURSE_OWNER` |
| A-6 | 다른 사용자의 enrollment에 `POST /confirm` | 403 `NOT_ENROLLMENT_OWNER` |
| A-7 | 다른 사용자의 waitlist에 `POST /cancel` | 403 `NOT_WAITLIST_OWNER` |

### 8.3 입력 검증

| # | 시나리오 | 기대 |
|---|---|---|
| V-1 | `title` 누락 | 400 `INVALID_REQUEST` + `errors[].field == "title"` |
| V-2 | `capacity = 0` | 400 + errors |
| V-3 | `startDate > endDate` | 400 + 필드 간 검증 메시지 |
| V-4 | `price` 음수 | 400 + errors |
| V-5 | `status` 필터에 알 수 없는 enum 값 | 400 (`MethodArgumentTypeMismatchException`) |
| V-6 | `page=-1`, `size=200` | 400 (`ConstraintViolationException`) |

### 8.4 ProblemDetail 응답 형식

각 에러 응답마다 다음 필드를 검증:

```java
.andExpect(jsonPath("$.type").value("about:blank"))
.andExpect(jsonPath("$.title").value("Course Not Open"))
.andExpect(jsonPath("$.status").value(409))
.andExpect(jsonPath("$.detail").value("강의가 모집 중 상태가 아닙니다."))
.andExpect(jsonPath("$.instance").value("/api/courses/12/enrollments"))
.andExpect(jsonPath("$.code").value("COURSE_NOT_OPEN"));
```

### 8.5 API 인수 테스트

MockMvc를 사용해 여러 API를 연결한 사용자 관점의 시나리오를 검증한다. 단일 API 검증(8.1~8.4)과 달리, 인수 테스트는 도메인 라이프사이클을 end-to-end로 검증한다.

| # | 시나리오 | 검증 |
|---|---|---|
| AT-1 | 강의 등록 → `open` → 신청 → `confirm` → 내 신청 목록 조회 | 최종 enrollment 상태 `CONFIRMED`, 목록에 포함 |
| AT-2 | 정원 1명 강의 → A 신청(ENROLLED) → B 신청(WAITLISTED) → A 취소 | B의 waitlist `PROMOTED`, B의 새 enrollment `PENDING`, `occupiedCount=1` 유지, AT-1과 동일하게 응답 본문 검증 |
| AT-3 | OPEN 강의에서 `close` → 기존 PENDING 취소 | `occupiedCount` 감소, 대기자 자동 승격 없음, 응답 `promoted = null` |
| AT-4 | 결제 확정 후 `Clock` mock으로 기간 초과 시각으로 이동 → 취소 시도 | 409 `CANCEL_DEADLINE_EXCEEDED`, 상태 변화 없음 |

---

## 9. 동시성 통합 테스트 시나리오

상세 패턴/코드는 `docs/CONCURRENCY.md` 10절. 본 문서는 시나리오 일람.

### 9.1 핵심 시나리오

| # | 시나리오 | 기대 |
|---|---|---|
| CC-1 | capacity=10, 동시 신청 50명 | ENROLLED 10, WAITLISTED 40, `occupiedCount=10` |
| CC-2A | 동일 enrollment에 cancel이 먼저 성공한 뒤 confirm 시도 | confirm은 `INVALID_STATUS`, 최종 상태 `CANCELLED` |
| CC-2B | 동일 enrollment에 confirm이 먼저 성공한 뒤 기간 내 cancel | 둘 다 성공, 최종 상태 `CANCELLED` |
| CC-2C | 동일 enrollment에 confirm이 먼저 성공한 뒤 기간 초과 cancel | cancel `CANCEL_DEADLINE_EXCEEDED`, 최종 상태 `CONFIRMED`, `occupiedCount` 불변 |
| CC-3 | 자동 승격 ↔ 대기 취소 race | 정확히 한 명만 승격되거나, 대기 취소 성공 시 다음 후보가 승격 |
| CC-4A | **정원이 남은 강의**에 동일 사용자가 동시 신청 | 1건 ENROLLED, 나머지 `DUPLICATE_ENROLLMENT` |
| CC-4B | **이미 정원이 찬 강의**에 동일 사용자가 동시 신청 | 1건 WAITLISTED, 나머지 `DUPLICATE_WAITLIST` |
| CC-5 | 동일 enrollment 동시 cancel | 1건만 성공, 나머지 `ALREADY_CANCELLED` |
| CC-6 | CLOSED 강의에서 동시 cancel | 모두 occupiedCount 감소만, 자동 승격 발동 없음 |
| CC-7 | 동시 다중 cancel + 다중 자동 승격 | 각 cancel당 1명씩 승격, 최종 occupiedCount 정확 |

### 9.2 진짜 동시 race 시나리오 (선택)

CC-2A/B/C는 도착 순서를 인위적으로 제어한 시나리오다. 실제 환경에서는 도착 순서가 비결정적이므로 **두 결과 모두 불변식을 깨지 않는지** 확인하는 것이 핵심이다. 진짜 동시 race(타이밍 비제어)는 결과 순서를 단정할 수 없어 선택 테스트로만 다룬다.

---

## 10. 예외 매핑 테스트 시나리오

상세는 `docs/ERROR_CODES.md` 9번. 본 문서에서는 검증 방법만 정리.

각 `ErrorCode`마다:
- 발생 조건을 만족하는 요청 호출
- 응답 본문의 `code`/`status`/`title`이 카탈로그와 일치 검증

`INTERNAL_ERROR`만 `GlobalExceptionHandler` 단위 테스트 (mock된 `Throwable` 입력).

---

## 11. 테스트 데이터 / 시드 정책

### 11.1 시드 사용자

`README.md`에 명시될 고정 시드 사용자:

| id | name | role |
|---|---|---|
| 1 | creator1 | CREATOR |
| 2 | creator2 | CREATOR |
| 10~19 | student01~10 | STUDENT |

Flyway migration으로 주입한다.

**시드 사용자는 테스트 cleanup 시 삭제하지 않는다** — 다음 테스트의 인증이 깨지지 않게 하기 위함. 부득이하게 삭제해야 하는 테스트가 있다면 `@Sql(executionPhase = AFTER_TEST_METHOD)`로 다시 주입한다.

### 11.2 동시성 테스트용 대량 사용자

동시성 테스트(예: CC-1의 50명 동시 신청)는 시드 사용자(10명)만으로 부족하다. 이 경우 **테스트 setup에서 추가 학생을 동적으로 생성**한다.

```java
@BeforeEach
void setupParticipants() {
    participants = IntStream.range(0, 50)
        .mapToObj(i -> userRepository.save(
            new User("student_" + i + "_" + UUID.randomUUID(), Role.STUDENT)))
        .toList();
}
```

이렇게 생성한 사용자는 cleanup에서 삭제 가능 (시드 사용자가 아니므로).

### 11.3 테스트 데이터 픽스처

테스트별 `Course`/`Enrollment`/`Waitlist`는 테스트 setup에서 직접 생성한다. 픽스처 빌더(`CourseFixtures`, `EnrollmentFixtures`)를 두어 boilerplate 축소.

```java
Course course = CourseFixtures.openCourse(creatorId, capacity);
courseRepository.save(course);
```

---

## 12. Clock 주입 정책

### 12.1 정책

- 서비스 코드의 시간 비교는 **모두 `Clock`을 통해**서만 사용 (`OffsetDateTime.now(clock)`)
- DB의 `now()`는 직접 호출하지 않음. `Clock`에서 만든 `now`를 쿼리 파라미터로 전달 (`docs/CONCURRENCY.md` 6.4 참조)
- 테스트에서는 `Clock.fixed(instant, zone)` 또는 `MutableClock`을 빈으로 주입해 시각 조작

### 12.2 사용 예시

```java
@TestConfiguration
static class FixedClockConfig {
    @Bean @Primary
    Clock clock() {
        // KST 2026-05-21T10:00:00+09:00 = UTC 2026-05-21T01:00:00Z
        // confirmed_at(2026-05-14T10:00:00+09:00) + 7일 = 2026-05-21T10:00:00+09:00
        // 따라서 now가 정확히 경계값 ⇒ 취소 허용 (<=)
        return Clock.fixed(
            Instant.parse("2026-05-21T01:00:00Z"),
            ZoneId.of("Asia/Seoul")
        );
    }
}

@Test
void 취소_기간_경계_테스트() {
    // confirmed_at = 2026-05-14T10:00:00+09:00
    // deadline = 7일
    // 마감 시각 = 2026-05-21T10:00:00+09:00
    // now      = 2026-05-21T10:00:00+09:00 → 정확히 경계 → 허용 (<=)
    ...
}

@Test
void 취소_기간_1초_초과_테스트() {
    // Clock을 1초 더 진행시킨 시각으로 주입한 별도 테스트 구성 또는 MutableClock 사용
    // 기대: CANCEL_DEADLINE_EXCEEDED
    ...
}
```

### 12.3 동시성 테스트에서의 Clock

`Clock.fixed`는 모든 스레드에서 동일 시각을 반환하므로 동시성 테스트에서 시간 격차로 인한 race가 사라진다. 의도된 동작.

---

## 13. 테스트 격리 정책

### 13.1 `@Transactional` 사용 정책

- **서비스 메서드 자체**에는 `@Transactional`이 적용되어 있어야 한다 (운영 코드 책임)
- **일반 테스트 메서드**에는 원칙적으로 `@Transactional`을 붙이지 않는다 — 서비스 트랜잭션의 commit 결과를 검증하기 위함
- **동시성 테스트**는 commit이 필수이므로 절대 `@Transactional`로 감싸지 않는다. 대신 명시적 데이터 정리

### 13.2 데이터 정리 전략

| 전략 | 적용 | 비고 |
|---|---|---|
| `@AfterEach`에서 명시적 `DELETE` | 동시성 테스트 / 트랜잭션 경계가 여러 개인 테스트 | 자식 → 부모 순서로 (다음 항목 참조) |
| `@Sql(executionPhase=AFTER_TEST_METHOD)` | 동일 | 외부 SQL 파일로 정리 |

### 13.3 cleanup 삭제 순서

`enrollments.promoted_from_waitlist_id`가 `waitlists.id`를 참조하므로, **enrollments를 먼저 삭제**해야 한다.

```
enrollments → waitlists → courses
(users는 시드 보존을 위해 삭제하지 않음; 11.2의 동적 생성 학생만 별도 삭제)
```

```sql
-- @AfterEach 예시
DELETE FROM enrollments;
DELETE FROM waitlists;
DELETE FROM courses;
DELETE FROM users WHERE id > 100;  -- 동적 생성 사용자만 (시드는 id <= 19)
```

### 13.4 컨테이너 격리

각 테스트 클래스마다 컨테이너 새로 띄우지 않음. 같은 PostgreSQL 컨테이너에서 데이터 정리로 격리.

---

## 14. 테스트 명명 규칙

### 14.1 한국어 메서드명 권장

도메인 테스트는 의도를 한국어로 명확히.

```java
@Test
void 정원이_남으면_Enrollment_PENDING이_생성된다() { ... }

@Test
void 정원이_차면_Waitlist_WAITING이_생성된다() { ... }
```

### 14.2 시나리오 ID 주석 권장

본 문서의 시나리오 ID를 테스트 메서드 위 주석으로 남기면 추적이 쉬워진다.

```java
// S-E-8: PENDING → cancel (대기자 있음, OPEN) → 자동 승격
@Test
void 수강_취소시_대기자가_있으면_자동_승격된다() { ... }
```

### 14.3 동시성 테스트 명명

`동시_NNN_concurrency` 형태로 묶어 grep으로 골라낼 수 있게.

```java
@Test
void 동시_마지막_한_자리_경합_concurrency() { ... }
```

---

## 15. 테스트 우선순위

테스트는 모두 동일 우선순위가 아니라 **Must / Should / Could**로 나누어 구현 리스크를 관리한다. 시간이 부족하면 Could부터 줄인다.

| 우선순위 | 항목 |
|---|---|
| **Must** | 서비스 핵심 흐름 (S-E-1, S-E-2, S-E-6, S-E-7, S-E-8) / 정원 초과 방지 / 자동 승격 / 취소 가능 기간 (S-E-11) / 마지막 자리 동시 신청 (CC-1) / 도메인 단위 테스트 (5절 전부) |
| **Should** | API 권한·검증·ProblemDetail (8.1~8.4) / 중복 신청·중복 대기 (S-E-4, S-E-5) / CLOSED 취소 정책 (S-E-12) / 정렬 정책 (R-S-1, R-S-2) / API 인수 테스트 (AT-1, AT-2, AT-3) |
| **Could** | confirm-cancel race (CC-2A/B/C) / 자동 승격-대기 취소 race (CC-3) / 동시 사용자 중복 신청 (CC-4A/B) / 동시 중복 취소 (CC-5) / 동시 다중 cancel (CC-7) / 모든 `ErrorCode` 개별 통합 테스트 / Repository 락 테스트 (R-L-1, R-L-2) |

### 커버리지 목표

수치 자체보다 **핵심 불변식 검증**을 우선한다.

- 도메인 상태 전이 메서드: 주요 분기 대부분 검증
- 서비스 레이어: 핵심 정상/예외 흐름 검증
- 컨트롤러: 대표 happy path와 주요 예외 응답 검증
- 동시성: 마지막 자리 경합(CC-1)은 반드시 검증, 나머지는 시간 여유에 따라 확장

Jacoco 등 커버리지 도구는 본 과제 범위에서 의무는 아니지만, Must + Should를 모두 작성하면 자연스럽게 핵심 커버리지가 채워진다.

---

## 16. 구현 체크리스트

- [ ] `IntegrationTestBase` 추상 클래스 작성 (Testcontainers + MockMvc + ObjectMapper + cleanup hook)
- [ ] 도메인 엔티티 단위 테스트 작성 (Course/Enrollment/Waitlist) — **Must**
- [ ] Repository 통합 테스트 작성 (partial unique / 정렬) — partial unique는 **Must**, 정렬은 **Should**, 락은 **Could**
- [ ] 서비스 통합 테스트 작성 (Course/Enrollment/Waitlist 라이프사이클 + 자동 승격 fallback) — 핵심 흐름 **Must**
- [ ] 컨트롤러 통합 테스트 작성 (happy path + 권한 + 검증 + ProblemDetail) — **Should**
- [ ] API 인수 테스트 작성 (AT-1, AT-2, AT-3) — **Should**
- [ ] 동시성 통합 테스트 — CC-1은 **Must**, 나머지는 **Could**
- [ ] 예외 매핑 테스트 작성 (ERROR_CODES.md 9.2 매트릭스 + 9.3 unique violation) — **Could**
- [ ] `@TestConfiguration`으로 `Clock.fixed` 빈 주입 패턴 정립
- [ ] `@AfterEach` cleanup 로직 (자식 테이블부터 삭제, 시드 사용자 유지)
- [ ] 동시성 테스트용 동적 사용자 생성 헬퍼
- [ ] 시드 사용자 SQL을 Flyway test profile 또는 별도 `@Sql`로 분리
- [ ] 한국어 메서드명 + 시나리오 ID 주석 컨벤션 적용
- [ ] 일반 테스트는 `@Transactional` 미사용, 서비스 메서드에는 명시. 동시성 테스트는 절대 `@Transactional` 사용 금지

---

## 17. 다른 문서와의 관계

| 문서 | 역할 분담 |
|---|---|
| `docs/ERD.md` | 데이터 모델 정의 — 테스트는 본 모델을 가정 |
| `docs/API.md` | API 표면 — 8번 컨트롤러 테스트의 명세 |
| `docs/STATE_TRANSITIONS.md` | 상태 전이 규칙 — 5번 도메인 단위 테스트의 명세 |
| `docs/CONCURRENCY.md` | 동시성 흐름 — 9번 동시성 테스트의 명세 (구체 코드 패턴 포함) |
| `docs/ERROR_CODES.md` | 예외 코드 카탈로그 — 10번 예외 매핑 테스트의 명세 |
| **`docs/TEST_SCENARIOS.md`** (본 문서) | **테스트 전략과 시나리오 일람** — 모든 레이어를 묶는 인덱스 |

---

## 18. 테스트 설계 결정 요약

1. 테스트 목표는 라인 커버리지보다 **핵심 불변식 검증**이다.
2. 핵심 불변식은 **정원 초과 방지, 중복 신청 방지, 상태 전이 제한, 대기열 FIFO 승격, 취소 가능 기간 정확성**이다.
3. DB row lock, partial unique index, PostgreSQL `TIMESTAMPTZ` 동작을 검증해야 하므로 **H2는 사용하지 않는다.**
4. 동시성 테스트는 반드시 Testcontainers PostgreSQL에서 수행한다.
5. **테스트 메서드에는 `@Transactional`을 붙이지 않고**, 서비스 트랜잭션의 commit 결과를 검증한다. 서비스 메서드 자체에는 `@Transactional`을 명시한다.
6. 시간 기반 테스트는 `Clock`을 주입해 경계값을 안정적으로 검증한다. DB `now()`는 사용하지 않는다.
7. 모든 테스트를 동일 우선순위로 두지 않고 **Must / Should / Could**로 나누어 구현 리스크를 관리한다.
8. Entity 단위 테스트는 `IllegalStateException`을, Service/API 테스트는 `DomainException(ErrorCode)`/ProblemDetail을 검증한다 — 도메인과 인터페이스 책임 분리.
9. 시드 사용자는 cleanup에서 삭제하지 않으며, 동시성 테스트용 대량 사용자는 setup에서 별도 생성한다.
10. cleanup 삭제 순서는 `enrollments → waitlists → courses` (FK 방향 자식 → 부모).
