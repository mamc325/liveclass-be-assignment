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

- **마지막 자리 경합**: 정원 10명 강의에 50명이 동시에 신청해도 정확히 10명만 등록되고 나머지 40명은 대기로 흐른다. 강의의 현재 인원이 정원을 초과하지 않는다.
- **자동 승격과 대기 취소의 충돌**: 누군가 취소해 자리가 생긴 순간 다른 사용자가 대기를 취소해도, 그 다음 대기자를 자동으로 승격시켜 자리를 비워두지 않는다.
- **같은 신청을 동시에 취소**: 한 사용자가 빠르게 여러 번 취소를 누르거나 동시 요청이 와도, 한 번만 처리되고 나머지는 "이미 취소됨" 응답으로 일관되게 처리된다.

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

강의에는 "현재 인원"(코드상 `occupied_count`)을 두고, 이 값이 정원을 넘지 못하게 막습니다.

- **결제 전 신청(`PENDING`)과 결제 완료(`CONFIRMED`) 모두 현재 인원에 포함**합니다. 신청 시점에 자리를 잡아두지 않으면, 사용자가 결제 단계에 가서야 "정원이 찼습니다"를 보게 되는 UX가 생깁니다.
- **취소(`CANCELLED`)는 현재 인원에서 즉시 빠집니다.**

### 3. 자동 마감 정책

- **정원이 가득 차도 강의 상태는 "모집 중"을 유지**합니다. 자동으로 닫히지 않고, 크리에이터가 직접 모집 마감 API를 호출해야 닫힙니다.
- 정원이 찬 뒤 들어온 신청은 자동으로 대기열로 흐릅니다.
- 과제의 "정원 초과 시 신청 불가" 조건을 "정원 내 신청은 불가, 대기 등록은 가능"으로 해석했습니다.

### 4. 취소 가능 기간

- **결제 완료한 신청**은 "결제 확정 시각 + 강의별 취소 가능 기간"(기본 7일, 강의별 설정 가능) 안에서만 취소할 수 있습니다.
- **결제 전 신청**은 아직 결제하지 않았으므로 기간 제한 없이 언제든 취소할 수 있습니다.
- **경계값 처리**: 현재 시각이 마감 시각과 같은 순간이면 허용합니다(`<=`). 1초 차이로 거부되는 케이스를 줄이기 위함입니다.

### 5. CLOSED 강의에서의 후속 동작

- CLOSED는 **신규 신청과 대기자 자동 승격만 차단**
- 이미 생성된 `PENDING`의 결제 확정과 `PENDING`/`CONFIRMED`의 취소는 그대로 허용 (사용자가 이미 잡은 자리/결제를 막으면 손해)
- CLOSED 후의 취소도 `occupied_count`는 정상 감소, 단 대기자 자동 승격은 발동되지 않음

### 6. DRAFT 강의 노출

- 강의 목록 조회는 누구나 가능하며, 모집 시작 전·모집 중·마감 강의가 모두 조회됩니다.
- 실서비스라면 학생 역할에게는 모집 시작 전 강의를 숨기는 권한별 필터링을 추가하는 것이 자연스럽습니다.

### 7. 시간 표현

- DB 저장: `TIMESTAMPTZ` (PostgreSQL 내부 UTC 정규화)
- API 응답: KST(`+09:00`) ISO 8601 형식 (`2026-05-22T11:00:00+09:00`)
- 서비스 시간 비교는 `Clock` 빈을 통해서만 — DB의 `now()`는 직접 사용하지 않음 (테스트 시 mock 가능)

### 8. 가격

- 원화(KRW) 정수 단위로 저장합니다.
- 추후 다국가 확장이 필요하면 컬럼 구조를 바꾸어 대응할 수 있습니다.

---

## 설계 결정과 이유

핵심 결정 4가지. 상세 비교/대안은 각 문서 참조.

### 1. 동시성 — 강의 단위 비관적 락으로 정합성 보장

수강 신청·취소·자동 승격처럼 강의의 현재 인원이 바뀌는 흐름은, 강의 한 행을 비관적 락(`SELECT FOR UPDATE`)으로 잠근 뒤 처리합니다. 같은 강의에 대한 요청이 동시에 들어와도 한 줄로 직렬화되므로, 정원을 초과해 신청이 받아지거나 현재 인원과 실제 등록 수가 어긋나는 일이 발생하지 않습니다.

낙관적 락(`@Version`)도 검토했으나, 마지막 자리 경합처럼 충돌이 자주 일어나는 시나리오에서는 재시도/대기열 전환 로직이 과도하게 복잡해져 비관적 락이 본 도메인에 더 자연스럽다고 판단했습니다. 상세: [`docs/CONCURRENCY.md`](docs/CONCURRENCY.md) 3절.

### 2. 가드 조건 UPDATE 패턴

한 행만 바꾸는 상태 전이(결제 확정, 대기 취소, 강의 상태 변경)는 락을 잡지 않고, "현재 상태가 기대한 상태일 때만 바꾸도록" 한 SQL로 처리합니다.

```sql
UPDATE enrollments SET status='CONFIRMED', ...
 WHERE id=? AND status='PENDING'
```

바뀐 행이 0이면 다른 요청이 먼저 상태를 바꾼 것으로 보고 409로 분기합니다. 단일 UPDATE는 PostgreSQL이 원자적으로 보장하므로 별도 락 없이도 안전합니다.

### 3. 자동 승격에서 다음 후보 시도

수강 취소로 자리가 비면 가장 먼저 대기한 사람을 자동 승격시킵니다. 이때 그 사람이 동시에 본인이 직접 대기를 취소해 가드 조건 UPDATE가 실패하면, 그 다음 대기자를 시도합니다. 빈 자리가 생겼는데 승격이 누락되는 상태를 막기 위함입니다.

### 4. URL 스타일 — RESTful 우선, 도메인 액션은 액션 엔드포인트로 분리

URL은 RESTful 자원 모델을 우선했습니다. 자원의 생성/조회는 표준 REST 형태입니다.

- **자원 CRUD**: `POST /api/courses`, `GET /api/courses/{id}` 등

상태 전이는 자원의 단순 속성 변경이 아니라 부수효과(자동 승격, 결제 확정 등)를 동반하는 도메인 행위이므로, 의미가 URL에 직접 드러나도록 액션 엔드포인트를 별도로 두었습니다.

- **도메인 액션**: `POST /api/enrollments/{id}/confirm`, `POST /api/enrollments/{id}/cancel`, `POST /api/courses/{id}/open` 등

이렇게 분리하면 핸들러 단위로 인증·권한·트랜잭션 경계가 명확하게 잡히고, 부수효과가 있는 동작을 단순 PATCH로 숨기지 않을 수 있습니다. 상세: [`docs/API.md`](docs/API.md) 6절.

---

## 미구현 / 제약사항

본 과제의 핵심 범위에 집중하기 위해 다음은 의도적으로 구현하지 않았습니다. 도입이 어려운 영역이 아니라, 시간 예산과 평가 초점 정렬을 위한 선택입니다.

| 항목 | 사유 |
|---|---|
| **Spring Security** | 과제 명시가 헤더 기반 간략 인증을 허용. 핵심 평가가 인증 체계가 아닌 정원·상태 전이라 도입하지 않음 |
| **외부 결제 PG 연동** | 과제 명시 (단순 상태 변경으로 대체) |
| **강의 정보 수정 API** | 과제 요구사항 외 |
| **User CRUD API** | 시드 사용자(12명)로 평가 가능 |
| **LOCK_TIMEOUT** | 단일 인스턴스 + 짧은 트랜잭션 환경에서 측정 가능한 케이스 거의 없음. 운영 확장 시 검토 |
| **405 별도 도메인 코드** | HTTP method 오류는 도메인 규칙 위반이 아니라 프로토콜 레벨. Spring 기본 ProblemDetail에 위임 |
| **JaCoCo 등 커버리지 도구** | 과제 의무 아님. 핵심 불변식 검증을 우선 (총 121개 테스트 작성) |

---

## API 목록 및 예시

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

## 데이터 모델 설명

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

## 테스트 실행 방법

```bash
./gradlew test       # 전체 테스트 (Testcontainers PostgreSQL 자동 기동)
./gradlew build      # 빌드 + 전체 테스트
```

총 121개 테스트 — 도메인 단위, Repository 통합, 서비스 통합, 컨트롤러 통합(MockMvc), 인수 테스트, 동시성. 상세: [`docs/TEST_SCENARIOS.md`](docs/TEST_SCENARIOS.md).

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
| [`specs/001-enrollment-system/`](specs/001-enrollment-system/) | Spec Kit으로 생성한 초기 스펙 진입점 (상세 진실 원천은 `docs/`) |

---

## 회고 — 락 설계에 대한 고민

이번 과제에서 가장 오래 고민한 영역은 동시성 제어 방식이었습니다. 정원이 10명인 강의에 50명이 동시에 신청해도 정확히 10명만 받아져야 한다는 요구가 본 시스템의 핵심이었고, 이를 어떻게 보장할지가 설계의 출발점이었습니다. 낙관적 락은 충돌이 잦은 마지막 자리 경합에서 재시도와 대기 전환 로직이 복잡해져 부적합했고, Redis 분산 락은 단일 인스턴스 환경에서 외부 의존성을 추가할 가치가 크지 않다고 봤습니다. 결국 정원이 바뀌는 흐름에는 강의 행에 비관적 락을 잡아 강의 단위로 직렬화하고, 한 행만 바꾸는 상태 전이에는 락 없이 가드 조건 UPDATE를 쓰는 하이브리드 방식을 선택했습니다. 비관적 락 안의 작업은 강의 조회, 정원 확인, 한 줄 INSERT, 현재 인원 증감으로 짧게 유지해 락 보유 시간이 수 밀리초 수준이 되도록 했습니다.

이론만으로는 부족하다고 보고 다섯 가지 동시성 시나리오를 테스트로 작성했는데, 그 중 하나에서 실제로 버그가 잡혔습니다. 수강 취소 흐름에서 사전 조회를 한 뒤 잠금 조회를 하면 ORM의 1차 캐시가 사전 조회 시점의 상태를 그대로 반환해, 동시 취소 시나리오에서 두 번째 요청이 잘못된 상태로 권위 검증을 통과해버리는 문제였습니다. 잠금 조회 직후 강제 재조회를 추가해 해결했는데, 락 설계가 이론적으로 옳아도 ORM 캐시 동작이 그 옳음을 깨뜨릴 수 있다는 것을 직접 경험한 사례였습니다.

한 강의에 초당 수백 건 이상의 신청이 들어오는 단계에서는 강의 단위 직렬화가 병목이 될 수 있어, 그 시점에는 강의 목록 조회 캐싱이나 강의별 대기 인원 카운터 캐싱, 승격 알림 비동기 큐 같은 형태로 Redis를 보조 인프라로 도입할 수 있다고 생각합니다. 또한 강의별 수강생 목록처럼 연관 엔티티 조회가 늘어나는 영역에서는 fetch 전략을 명시적으로 잡아 N+1을 사전에 방지하고, 부하 테스트로 실제 응답 시간 분포를 측정해 캐싱과 쿼리 튜닝의 우선순위를 정하는 작업이 자연스러운 다음 단계라고 봅니다.

---

## AI 활용 범위

본 과제는 **Claude (Anthropic)** 를 페어 프로그래밍 파트너로 적극 활용했습니다. 과제 안내가 "AI를 사용한 후 결과물을 얼마나 자기 것으로 만들었는지"를 본다고 명시했으므로, 사용 범위와 본인 판단·검증 포인트를 투명하게 기재합니다.

### 사용 범위

| 영역 | AI 활용 |
|---|---|
| **설계 문서** | ERD, API, 상태 전이, 동시성, 에러 코드, 테스트 시나리오 문서 — AI가 초안을 생성하고 본인이 피드백/수정 반복 |
| **구현 코드** | 엔티티, Repository, Service, Controller, 테스트 — AI가 초안을 생성하고 본인이 검토 후 커밋 |
| **리뷰** | 각 설계 문서·코드에 대해 다른 AI 시각으로 동료 리뷰를 받아 반복 개선 |
| **에러 디버깅** | 빌드/테스트 실패 분석, 원인 추정, 해결안 제시 |
| **문서 한국어 정리** | 변역 및 톤 통일 |

### 본인 판단으로 결정한 사항 (AI 제안 거부 또는 수정)

설계 과정에서 AI 제안을 그대로 받지 않고 본인 판단으로 결정/수정한 대표 사례:

1. **선택 구현 4종 모두 포함** — 시간 부담에도 불구하고 대기열·자동 승격·크리에이터 목록·페이지네이션 전부 포함하기로 결정.
2. **시간 표현 UTC → KST 변경** — AI 초기 제안은 UTC. 한국 서비스 도메인 고려해 KST(`+09:00`)로 변경 + DB는 `TIMESTAMPTZ`로 안전 저장.
3. **URL 스타일 — RESTful + Action Endpoint 혼합** — 강의 상태 전이를 `PATCH /status`로 통합하지 않고 `POST /open`, `POST /close` 액션 엔드포인트로 분리.
4. **커밋 단위로 진행** — 한 번에 큰 변경을 받지 않고 51개 단위 커밋으로 쪼개 검토 + 빌드 통과 후 push.
5. **하이브리드 리뷰 정책** — Foundation/Domain/Infra는 커밋 하나씩 꼼꼼히, 패턴이 잡힌 Service/Controller는 batching 허용.

### 테스트가 발견한 실제 버그 (AI 코드의 결함을 본인이 발견·수정)

자동 테스트 작성/실행 과정에서 다음 버그를 발견하고 수정했습니다. 모두 AI가 초안 작성 시 놓친 것들이며, 본인이 테스트를 돌려보고 결과를 분석해 수정 방향을 결정했습니다.

| 발견 시점 | 버그 | 수정 |
|---|---|---|
| Phase 2 Repository 통합 테스트 | `JPA Auditing`이 `OffsetDateTime` 미지원 → `IllegalArgumentException` | `DateTimeProvider` 빈을 `JpaConfig`에 등록해 `OffsetDateTime` 반환 |
| Phase 2 cleanup 검증 | HikariCP `auto-commit: false`로 `JdbcTemplate.TRUNCATE`가 commit 안 됨 | `auto-commit` 명시 제거 (HikariCP 기본값 `true` 복원) |
| Phase 5 Controller 테스트 | `EnrollmentSummary`에 `confirmedAt`/`cancelledAt` 필드 누락 (API.md 7.7 스펙과 불일치) | 두 필드 추가 (Jackson `non_null`로 null 시 자동 제외) |
| Phase 6 CC-5 동시성 테스트 | `cancel()`의 사전 `findById` → `findByIdForUpdate`가 1차 캐시의 stale PENDING을 반환해 권위 검증을 우회 → `decrementOccupiedCount()`에서 `IllegalStateException` 발생 | `entityManager.refresh()`로 강제 재조회. **이 버그가 평가에서 가장 위험한 결함이었고 동시성 테스트로만 발견 가능했음** |

### AI를 거의 안 쓴 영역 (직접 결정/판단)

- 기능 범위 결정 — 선택 구현 포함 여부, 미구현 항목 경계
- 우선순위 — Must/Should/Could 분류, 시간 배분, 리뷰 깊이 vs 속도
- 평가자 관점 설계 — 어떤 결정이 평가자에게 가치 있을지 (예: 의사결정 로그, 한국어 메시지, Swagger UI)
- 최종 검수 — 수동 스모크 테스트, 에러 응답 형식 확인

### 사용 모델

- **Claude Opus 4.7** (Anthropic Claude Code CLI)
- 대화 단위로 설계 → 구현 → 리뷰 → 수정을 반복

### 한 줄 요약

AI는 **타이핑과 초안 작성의 부담**을 덜어주는 도구로 활용했고, **설계 방향, 트레이드오프 결정, 평가 가치 판단, 실제 결함 발견**은 본인이 수행했습니다. 결과물의 모든 코드와 문서를 직접 검토했고, 동시성 버그를 포함한 4건의 실제 결함을 테스트 과정에서 본인이 발견·수정했습니다.
