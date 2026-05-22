# 라이브클래스 BE-A — 수강 신청 시스템

> 라이브클래스 프로덕트 엔지니어 채용 과제 BE-A 제출물입니다.
> 마감: 2026-05-24 23:59

---

## 프로젝트 개요

본 시스템은 강의 등록, 수강 신청, 결제 확정, 대기열 자동 승격을 다루는 백엔드 API입니다.

평가 기준이 명시한 핵심 평가 포인트(상태 전이, 정원 관리, 동시성 제어)를 중심으로 설계했으며, 선택 구현 4가지(취소 가능 기간 제한, 대기열, 크리에이터 수강생 목록, 페이지네이션) 모두 포함했습니다.

### 주요 기능

- 강의 라이프사이클 — `DRAFT` → `OPEN` → `CLOSED`
- 수강 신청 — 정원 내면 `Enrollment(PENDING)`, 정원 초과면 `Waitlist(WAITING)` 자동 분기
- 결제 확정 — `PENDING` → `CONFIRMED`
- 수강 취소 — 기간 내 취소 + 대기자 자동 승격 (race 시 다음 후보로 fallback)
- 본인 신청/대기 목록 조회, 크리에이터의 강의별 수강생/대기자 목록

### 동시성 핵심 시연

- **CC-1**: capacity=10 강의에 50명 동시 신청 → 정확히 10명 ENROLLED + 40명 WAITLISTED + `occupied_count = 10` 정합성 유지
- **CC-3**: 자동 승격 ↔ 대기 취소 race — fallback으로 다음 후보 자동 승격
- **CC-5**: 동일 enrollment 동시 cancel — 정확히 1건만 성공, 나머지 `ALREADY_CANCELLED`

전체 시나리오와 상세는 [`docs/CONCURRENCY.md`](docs/CONCURRENCY.md) 참조.

---

## 기술 스택

| 항목 | 선택 | 핵심 이유 |
|---|---|---|
| 언어 | Java 21 LTS | Spring Boot 3.5.x 권장 baseline |
| 빌드 | Gradle (Groovy DSL) | 스캐폴드 기본 |
| 프레임워크 | Spring Boot 3.5.14 | 안정성 + 풍부한 레퍼런스 |
| DB | PostgreSQL 16 (Docker) | 실 DB의 row lock + partial unique index로 동시성 검증 |
| ORM | Spring Data JPA | 도메인 상태 전이 + `@Lock(PESSIMISTIC_WRITE)` 선언적 사용 |
| 마이그레이션 | Flyway | 스키마/시드 이력 코드 관리 |
| 테스트 | JUnit 5 + AssertJ + Testcontainers | 실 PostgreSQL에서 동시성 테스트 |
| API 문서 | Springdoc OpenAPI 2.8.4 | `/swagger-ui.html` 자동 생성 |
| 인증 | `X-USER-ID` 헤더 + `users.role` (Spring Security 미도입) | 과제 명시 허용, 시간 절약 |

선택 근거 전체는 [`docs/기술스택선정.md`](docs/기술스택선정.md) 참조.

---

## 요구사항 해석 및 가정

과제 명세를 다음과 같이 해석했습니다. 명시되지 않은 부분은 설계 결정으로 보강하고 본 섹션에 명시합니다.

### 1. 사용자 식별

- 인증/인가는 과제 안내가 허용한 헤더 방식 사용
- `X-USER-ID` 헤더로 사용자 식별 → 서버가 `users.role`을 조회해 권한 검증
- role은 헤더로 받지 않음 (스푸핑 방지, 단일 진실 원천)

### 2. 정원 카운팅 정책

- `PENDING`과 `CONFIRMED` 모두 정원을 점유한다 (`occupied_count`에 반영)
- `PENDING`이 점유에서 제외되면 "결제 단계에서 정원 초과로 결제 실패"하는 UX가 발생하므로 신청 시점에 자리를 보장
- `CANCELLED`는 미점유 (취소 시 즉시 `occupied_count -= 1`)

### 3. 자동 마감 정책

- 정원이 가득 차도 `course.status`는 `OPEN`을 유지
- 신규 신청은 자동으로 대기열로 흘러들며, 마감은 크리에이터가 `POST /close`로 명시적으로 수행
- "정원 초과 시 신청 불가"라는 요구사항을 "정원 내 enrollment는 불가, 대기 등록은 가능"으로 해석

### 4. 취소 가능 기간

- `CONFIRMED` 상태는 `confirmed_at + course.cancellation_deadline_days` 이내에만 취소 가능 (기본 7일, 강의별 설정)
- `PENDING`은 결제 전이므로 기간 제한 없이 취소 가능
- 경계값 정책: `now <= deadline`이면 허용 (정확히 같은 인스턴트도 허용)

### 5. CLOSED 강의에서의 후속 동작

- CLOSED는 **신규 신청과 대기자 자동 승격만 차단**
- 이미 생성된 `PENDING`의 결제 확정과 `PENDING`/`CONFIRMED`의 취소는 그대로 허용 (사용자가 이미 잡은 자리/결제를 막으면 손해)
- CLOSED 후의 취소도 `occupied_count`는 정상 감소, 단 대기자 자동 승격은 발동되지 않음

### 6. DRAFT 강의 노출

- `GET /api/courses` 권한이 `ANY`라 모든 상태(`DRAFT`/`OPEN`/`CLOSED`)가 조회된다
- 실서비스라면 `STUDENT`에게 `DRAFT`를 숨기는 권한별 필터링이 자연스러우나, 본 과제에서는 단순화

### 7. 시간 표현

- DB 저장: `TIMESTAMPTZ` (PostgreSQL 내부 UTC 정규화)
- API 응답: KST(`+09:00`) ISO 8601 형식 (`2026-05-22T11:00:00+09:00`)
- 서비스 시간 비교는 `Clock` 빈을 통해서만 — DB의 `now()`는 직접 사용하지 않음 (테스트 시 mock 가능)

### 8. 가격

- 원화(KRW) 정수 단위 (`BIGINT`)
- 다국가 확장 시 `NUMERIC + currency` 분리로 대응 가능

---

## 설계 결정과 이유

핵심 결정 8가지. 상세 비교/대안은 각 문서 참조.

### 1. 동시성 — `courses` row에 PESSIMISTIC_WRITE 락

수강 신청·취소·자동 승격 흐름에서 `courses` row에 `SELECT FOR UPDATE`로 락을 잡아 강의 단위 직렬화. 단일 인스턴스 + PostgreSQL row-level lock으로 정합성 확실히 보장.

낙관적 락(@Version)도 검토했으나, 마지막 자리 경합처럼 충돌 빈도가 높은 시나리오에서는 재시도/Waitlist 전환 로직이 복잡해져 부적합. 비관적 락이 본 도메인에 더 자연스러움. 상세: [`docs/CONCURRENCY.md`](docs/CONCURRENCY.md) 3절.

### 2. 가드 조건 UPDATE 패턴

단일 row 상태 전이(결제 확정, 대기 취소, 강의 상태 변경)는 `SELECT FOR UPDATE` 없이 가드 조건 UPDATE로 처리:

```sql
UPDATE enrollments SET status='CONFIRMED', ...
 WHERE id=? AND status='PENDING'
```

영향 받은 row가 0이면 race 발생 → 적절한 409로 분기. PostgreSQL 단일 UPDATE의 atomic 보장 덕분에 락 없이도 안전.

### 3. 자동 승격 fallback

cancel 후 자동 승격 시 가드 조건 UPDATE 실패(=동시 대기 취소로 인한 race)면 **다음 후보**(`created_at ASC, id ASC`)를 시도. 빈 자리가 있는데 승격이 누락되는 데드 상태 회피.

### 4. ErrorCode enum + ProblemDetail (RFC 7807)

17개 도메인 에러 코드를 enum으로 한곳에 정의 + 단일 `DomainException` 클래스. `@RestControllerAdvice`에서 ProblemDetail로 변환하며 `code` 확장 필드로 클라이언트 분기 지원.

```json
{
  "type": "about:blank",
  "title": "Course Not Open",
  "status": 409,
  "detail": "강의가 모집 중 상태가 아닙니다.",
  "instance": "/api/courses/12/enrollments",
  "code": "COURSE_NOT_OPEN"
}
```

상세: [`docs/ERROR_CODES.md`](docs/ERROR_CODES.md).

### 5. URL — RESTful + Action Endpoint 혼합

- **CRUD 조회/생성**: 정통 RESTful (`POST /api/courses`, `GET /api/courses/{id}` 등)
- **도메인 액션**: action endpoint (`POST /enrollments/{id}/confirm`, `/cancel`, `/courses/{id}/open` 등)

도메인 의미(취소·결제 확정)가 path에 직접 드러나고 side-effect가 다른 액션을 핸들러로 분리할 수 있어 가독성과 테스트 용이성이 높음. Stripe, GitHub, Google Cloud 같은 업계 표준 API도 동일 패턴 사용. 상세: [`docs/API.md`](docs/API.md) 6절.

### 6. Clock 주입으로 시간 제어

`Clock` 빈을 `Asia/Seoul`로 등록하고 모든 서비스가 `OffsetDateTime.now(clock)`로 시간 생성. native UPDATE 쿼리에도 DB `now()` 대신 `:now` 파라미터로 전달해 테스트 시 mock 가능.

### 7. `TIMESTAMPTZ` + KST 응답

- DB는 `TIMESTAMPTZ`로 저장 (PostgreSQL이 내부 UTC 정규화)
- 세션 타임존을 `Asia/Seoul`로 설정해 응답은 KST(`+09:00`) 형식
- Jackson `default-property-inclusion: non_null`로 null 필드 자동 제외

### 8. DB CHECK 제약 vs 코드 검증의 경계

- **정적 필드 검증**(`price >= 0`, `start_date <= end_date`, status enum)은 DB CHECK 제약으로 일관성 유지
- **동적 비즈니스 불변식**(`occupied_count <= capacity`)은 서비스 레이어 비관적 락 하에서만 검증 — 같은 규칙을 DDL/코드 양쪽에 중복 두지 않음 (운영 확장 시 CHECK 추가 가능)
- **다중 row race 방어**(중복 활성 신청/대기, 이중 승격)는 PostgreSQL **partial unique index**로 DB 레벨에서도 차단

---

## 미구현 / 제약사항

본 과제의 핵심 범위에 집중하기 위해 다음은 의도적으로 구현하지 않았습니다. 도입이 어려운 영역이 아니라, 시간 예산과 평가 초점 정렬을 위한 선택입니다.

| 항목 | 사유 |
|---|---|
| **Spring Security** | 과제 명시가 헤더 기반 간략 인증을 허용. 핵심 평가가 인증 체계가 아닌 정원·상태 전이라 도입하지 않음 |
| **Redis 분산 락** | 단일 인스턴스 전제. PostgreSQL row lock으로 정합성 충분 |
| **외부 결제 PG 연동** | 과제 명시 (단순 상태 변경으로 대체) |
| **강의 정보 수정 API** | 과제 요구사항 외 |
| **User CRUD API** | 시드 사용자(12명)로 평가 가능 |
| **시청 이력 기반 취소 제한** | 별도 학습 진도 도메인 필요 — 본 범위 외 |
| **DB CHECK `occupied_count <= capacity`** | 코드 레벨 검증으로 처리. 트래픽 증가/다중 진입점 추가 시 안전망으로 도입 검토 |
| **LOCK_TIMEOUT** | 단일 인스턴스 + 짧은 트랜잭션 환경에서 측정 가능한 케이스 거의 없음. 운영 확장 시 검토 |
| **405 별도 도메인 코드** | HTTP method 오류는 도메인 규칙 위반이 아니라 프로토콜 레벨. Spring 기본 ProblemDetail에 위임 |
| **i18n** | 한국어 메시지 고정 |
| **JaCoCo 등 커버리지 도구** | 과제 의무 아님. 핵심 불변식 검증을 우선 (총 122개 테스트 작성) |

---

## API 목록 (요약)

| # | 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|---|
| 1 | POST | `/api/courses` | CREATOR | 강의 등록 (항상 DRAFT) |
| 2 | POST | `/api/courses/{id}/open` | CREATOR | DRAFT → OPEN |
| 3 | POST | `/api/courses/{id}/close` | CREATOR | OPEN → CLOSED |
| 4 | GET | `/api/courses` | ANY | 강의 목록 (status 필터) |
| 5 | GET | `/api/courses/{id}` | ANY | 강의 상세 |
| 6 | POST | `/api/courses/{id}/enrollments` | STUDENT | 수강 신청 (자동으로 ENROLLED/WAITLISTED 분기) |
| 7 | POST | `/api/enrollments/{id}/confirm` | STUDENT | 결제 확정 |
| 8 | POST | `/api/enrollments/{id}/cancel` | STUDENT | 수강 취소 (대기자 자동 승격 포함) |
| 9 | GET | `/api/me/enrollments` | STUDENT | 내 신청 목록 |
| 10 | POST | `/api/waitlists/{id}/cancel` | STUDENT | 대기 취소 |
| 11 | GET | `/api/me/waitlists` | STUDENT | 내 대기 목록 |
| 12 | GET | `/api/courses/{id}/enrollments` | CREATOR | 강의별 수강생 목록 |
| 13 | GET | `/api/courses/{id}/waitlists` | CREATOR | 강의별 대기자 목록 |

상세 요청/응답 스펙은 [`docs/API.md`](docs/API.md), 실행 후 [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html)에서 직접 호출 가능.

---

## 데이터 모델

4개 테이블 — `users`, `courses`, `waitlists`, `enrollments`.

| 테이블 | 역할 |
|---|---|
| `users` | 시드 기반 사용자 (CREATOR/STUDENT 역할 구분) |
| `courses` | 강의 정보 + `occupied_count` (점유 인원) + 모집 상태 |
| `enrollments` | 수강 신청 + 결제/취소 상태 + `promoted_from_waitlist_id` (자동 승격 추적) |
| `waitlists` | 대기 등록 + 승격/취소 상태 |

### 핵심 제약/인덱스

- **Partial Unique**: 활성 상태(`PENDING`/`CONFIRMED`/`WAITING`)에서만 중복 차단
  - `uq_active_enrollment_per_user_course`
  - `uq_waiting_waitlist_per_user_course`
  - `uq_enrollments_promoted_from_waitlist` (대기열 이중 승격 방지)
- **복합 인덱스**: `(course_id, status, created_at DESC, id DESC)` 등 페이지네이션과 정렬 정책 정렬

전체 ERD/제약/인덱스 설계와 의사결정 로그는 [`docs/ERD.md`](docs/ERD.md), 상태 전이 정밀 명세는 [`docs/STATE_TRANSITIONS.md`](docs/STATE_TRANSITIONS.md) 참조.

---

## 실행 방법

### 사전 준비

- Java 21 (LTS) — `java -version`으로 확인
- Docker (Docker Desktop 또는 동등 환경)
- `./gradlew` (Gradle Wrapper 포함)

### 1. PostgreSQL 기동 (Docker Compose)

```bash
docker compose up -d
```

- `postgres:16-alpine` 이미지 + healthcheck + 포트 5432 노출
- `enrollment` DB / `enrollment` 사용자 / `enrollment` 비밀번호 (로컬 평가용)
- 타임존: `Asia/Seoul`

컨테이너 상태 확인:
```bash
docker compose ps  # postgres가 (healthy) 상태인지 확인
```

### 2. 애플리케이션 기동

```bash
./gradlew bootRun
```

기동 후 로그에서:
- `Migrating schema "public" to version "1 - init schema"` — Flyway 마이그레이션 성공
- `Migrating schema "public" to version "2 - seed users"` — 시드 사용자 12명 주입
- `Tomcat started on port 8080 (http)` — 서버 ready

### 3. API 호출 확인

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

### 정리

```bash
# 앱 종료: Ctrl+C
docker compose down       # 컨테이너만 종료 (데이터 유지)
docker compose down -v    # 컨테이너 + 볼륨 삭제 (clean restart 시)
```

---

## Seed 사용자

Flyway V2가 다음 12명을 고정 ID로 주입합니다. `X-USER-ID` 헤더에 그대로 사용 가능.

| ID | name | role | 용도 |
|---|---|---|---|
| 1 | creator1 | CREATOR | 강의 등록 / 모집 시작·마감 |
| 2 | creator2 | CREATOR | 다른 강의 소유자 (소유권 검증 테스트용) |
| 10 | student01 | STUDENT | 수강 신청 |
| 11 | student02 | STUDENT | 두 번째 학생 (대기열/race 테스트용) |
| 12~19 | student03~10 | STUDENT | 다수 학생 시나리오용 |

동시성 테스트에서 학생이 더 필요한 경우(예: 50명) 테스트 setup에서 동적으로 생성하며 ID는 101+부터 부여됩니다 (`DELETE FROM users WHERE id > 100`으로 정리).

---

## API 호출 예시 (curl)

### 시나리오 1 — 등록 → 모집 → 신청 → 결제 확정 → 조회

```bash
# 1. 강의 등록 (creator=1) → 201, id=1 가정
curl -X POST http://localhost:8080/api/courses \
  -H "X-USER-ID: 1" -H "Content-Type: application/json" \
  -d '{
    "title": "Java 마스터 클래스",
    "description": "Spring Boot 실전",
    "price": 150000,
    "capacity": 2,
    "startDate": "2026-06-01",
    "endDate": "2026-08-31",
    "cancellationDeadlineDays": 7
  }'

# 2. 모집 시작 → 200, status: OPEN
curl -X POST http://localhost:8080/api/courses/1/open \
  -H "X-USER-ID: 1"

# 3. 수강 신청 (student=10) → 201, outcome: ENROLLED, enrollment.id=1
curl -X POST http://localhost:8080/api/courses/1/enrollments \
  -H "X-USER-ID: 10"

# 4. 결제 확정 → 200, status: CONFIRMED, confirmedAt 채워짐
curl -X POST http://localhost:8080/api/enrollments/1/confirm \
  -H "X-USER-ID: 10"

# 5. 내 신청 목록 → 200, totalElements=1
curl http://localhost:8080/api/me/enrollments \
  -H "X-USER-ID: 10"
```

### 시나리오 2 — 정원 1명 + 자동 승격

```bash
# 1. capacity=1 강의 등록 + open
curl -X POST http://localhost:8080/api/courses \
  -H "X-USER-ID: 1" -H "Content-Type: application/json" \
  -d '{"title":"Tiny","price":50000,"capacity":1,"startDate":"2026-06-01","endDate":"2026-06-30"}'
# (응답에서 id 받아 사용. 예: id=2)
curl -X POST http://localhost:8080/api/courses/2/open -H "X-USER-ID: 1"

# 2. A 신청 → ENROLLED (enrollment.id=2)
curl -X POST http://localhost:8080/api/courses/2/enrollments -H "X-USER-ID: 10"

# 3. B 신청 → WAITLISTED, position=1
curl -X POST http://localhost:8080/api/courses/2/enrollments -H "X-USER-ID: 11"

# 4. A 취소 → 200, promoted.userId=11, promoted.enrollmentId=3
curl -X POST http://localhost:8080/api/enrollments/2/cancel -H "X-USER-ID: 10"

# 5. B의 내 신청 목록 → 새 PENDING enrollment with promotedFromWaitlistId
curl http://localhost:8080/api/me/enrollments -H "X-USER-ID: 11"
```

### 에러 응답 예시 (ProblemDetail + `code`)

```bash
# X-USER-ID 누락 → 401 UNAUTHENTICATED
curl -X POST http://localhost:8080/api/courses \
  -H "Content-Type: application/json" -d '{}'

# STUDENT가 강의 등록 → 403 FORBIDDEN
curl -X POST http://localhost:8080/api/courses \
  -H "X-USER-ID: 10" -H "Content-Type: application/json" \
  -d '{"title":"x","price":1000,"capacity":1,"startDate":"2026-06-01","endDate":"2026-06-02"}'

# capacity=0 → 400 INVALID_REQUEST + errors[] 필드별 정보
curl -X POST http://localhost:8080/api/courses \
  -H "X-USER-ID: 1" -H "Content-Type: application/json" \
  -d '{"title":"x","price":1000,"capacity":0,"startDate":"2026-06-01","endDate":"2026-06-02"}'

# 이미 CANCELLED인 enrollment에 cancel → 409 ALREADY_CANCELLED
curl -X POST http://localhost:8080/api/enrollments/2/cancel -H "X-USER-ID: 10"
```

모든 에러 응답은 RFC 7807 `ProblemDetail` 형식이며 `code` 확장 필드로 분기 가능. 전체 에러 코드 카탈로그는 [`docs/ERROR_CODES.md`](docs/ERROR_CODES.md).

---

## 테스트 실행 방법

```bash
./gradlew test       # 전체 테스트 (Testcontainers PostgreSQL 자동 기동)
./gradlew build      # 빌드 + 전체 테스트
```

총 122개 테스트 — 도메인 단위, Repository 통합, 서비스 통합, 컨트롤러 통합(MockMvc), 인수 테스트(AT-1~4), 동시성(CC-1, CC-3, CC-5, CC-6, CC-7). 상세: [`docs/TEST_SCENARIOS.md`](docs/TEST_SCENARIOS.md).

---

## 문서 인덱스

| 문서 | 내용 |
|---|---|
| [`docs/기술스택선정.md`](docs/기술스택선정.md) | 기술 선정 근거 |
| [`docs/ERD.md`](docs/ERD.md) | 데이터 모델 / 제약 / 인덱스 |
| [`docs/API.md`](docs/API.md) | API 인터페이스 명세 |
| [`docs/STATE_TRANSITIONS.md`](docs/STATE_TRANSITIONS.md) | 도메인 상태 전이 정밀 명세 |
| [`docs/CONCURRENCY.md`](docs/CONCURRENCY.md) | 동시성 제어 흐름 + 시퀀스 다이어그램 + 락 전략 트레이드오프 |
| [`docs/ERROR_CODES.md`](docs/ERROR_CODES.md) | 17개 에러 코드 카탈로그 |
| [`docs/TEST_SCENARIOS.md`](docs/TEST_SCENARIOS.md) | 테스트 전략 / 우선순위 / 시나리오 ID |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | 커밋 단위 구현 계획 |

---

## AI 활용 범위

> 다음 커밋에서 보강 예정.
