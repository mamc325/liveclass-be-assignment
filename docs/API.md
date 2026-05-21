# BE-A 수강 신청 시스템 API 설계

> 작성일: 2026-05-20
> 대상: 라이브클래스 BE-A 채용 과제

---

## 1. 개요

- Base URL: `/api`
- 요청/응답 Content-Type: `application/json; charset=UTF-8`
- JSON 키 규칙: **camelCase**
- 식별자(ID): `Long` (양의 정수)
- 시간/날짜: ISO 8601 (KST 기준, `+09:00` offset 포함, 예: `2026-05-20T14:30:00+09:00`)
- 날짜만 사용하는 필드: ISO 8601 date (예: `2026-06-01`)
- 서버는 timestamp를 KST 기준으로 응답한다. DB는 `TIMESTAMPTZ`로 저장하고(PostgreSQL이 내부적으로 UTC 정규화), 세션 타임존을 `Asia/Seoul`로 설정해 일관된 응답을 보장한다
- 화폐: 원화(KRW) 정수 (`Long`)
- 응답 본문은 공통 래퍼 없이 **API 응답 DTO**를 직접 반환한다. Entity는 그대로 노출하지 않는다
- 에러 응답은 RFC 7807 `ProblemDetail` 기반 + 도메인용 `code` 확장

---

## 2. 인증 / 인가

### 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---|---|
| `X-USER-ID` | 모든 요청 | 호출 사용자의 `users.id` |

`role`은 헤더로 받지 않고, 서버가 `users.role`을 조회해서 권한을 검증한다. (스푸핑 방지 + 단일 진실 원천)

평가/테스트용 유효 사용자 ID는 `README.md`의 "Seed 데이터" 섹션에서 고정값으로 제공한다.

### 권한 분류

| 권한 | 설명 |
|---|---|
| `ANY` | 인증된 사용자라면 누구나 (목록/상세 조회 등) |
| `CREATOR` | `users.role == 'CREATOR'` 만 허용 |
| `STUDENT` | `users.role == 'STUDENT'` 만 허용 |

### 소유권 검증

리소스 변경/삭제 API는 본인이 소유한 리소스인지 추가로 검증한다.

- 강의: `course.creator_id == X-USER-ID`
- 수강 신청: `enrollment.user_id == X-USER-ID`
- 대기열: `waitlist.user_id == X-USER-ID`

소유권 위반 시 `403`을 반환한다.

---

## 3. 공통 응답 포맷

### 3.1 성공 응답

API 응답 DTO를 그대로 반환. 별도 래퍼 없음. Entity는 직접 노출하지 않는다.

```json
{
  "id": 12,
  "title": "Java 마스터 클래스",
  "status": "OPEN",
  "createdAt": "2026-05-20T14:30:00+09:00"
}
```

### 3.2 에러 응답 (RFC 7807 ProblemDetail)

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

| 필드 | 설명 |
|---|---|
| `type` | 에러 유형 URI. 별도 분류가 없으면 `about:blank` |
| `title` | 에러 유형의 사람 친화적 요약 |
| `status` | HTTP 상태 코드 (본문에도 명시) |
| `detail` | 상황 설명 (한국어) |
| `instance` | 에러가 발생한 요청 경로 |
| `code` | **도메인 확장 필드**. 클라이언트 분기용 안정 식별자 |

`code` 카탈로그는 별도 문서(예정: `docs/ERROR_CODES.md`)에서 관리한다.

---

## 4. 공통 페이지네이션

### 요청 파라미터

| 파라미터 | 타입 | 기본 | 비고 |
|---|---|---|---|
| `page` | int | 0 | 0부터 시작 |
| `size` | int | 20 | 최대 100 |

### 응답 본문

```json
{
  "content": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 153,
  "totalPages": 8,
  "hasNext": true
}
```

정렬은 API별 고정. 별도로 지정하지 않은 경우 `created_at DESC, id DESC`.

---

## 5. API 목록 요약

| # | 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|---|
| 1 | `POST` | `/api/courses` | CREATOR | 강의 등록 (항상 DRAFT) |
| 2 | `POST` | `/api/courses/{id}/open` | CREATOR | 강의 모집 시작 (DRAFT → OPEN) |
| 3 | `POST` | `/api/courses/{id}/close` | CREATOR | 강의 모집 마감 (OPEN → CLOSED) |
| 4 | `GET` | `/api/courses` | ANY | 강의 목록 (status 필터) |
| 5 | `GET` | `/api/courses/{id}` | ANY | 강의 상세 |
| 6 | `POST` | `/api/courses/{id}/enrollments` | STUDENT | 수강 신청 (또는 대기 등록) |
| 7 | `POST` | `/api/enrollments/{id}/confirm` | STUDENT | 결제 확정 |
| 8 | `POST` | `/api/enrollments/{id}/cancel` | STUDENT | 수강 취소 (대기자 자동 승격 포함) |
| 9 | `GET` | `/api/me/enrollments` | STUDENT | 내 수강 신청 목록 |
| 10 | `POST` | `/api/waitlists/{id}/cancel` | STUDENT | 대기 취소 |
| 11 | `GET` | `/api/me/waitlists` | STUDENT | 내 대기 목록 |
| 12 | `GET` | `/api/courses/{id}/enrollments` | CREATOR | 강의별 수강생 목록 |
| 13 | `GET` | `/api/courses/{id}/waitlists` | CREATOR | 강의별 대기자 목록 |

> **자동 마감 정책**: 정원이 가득 차도 `course.status`는 `OPEN`을 유지한다. 새 신청은 대기열로 흘러들어가며, 마감은 크리에이터가 `POST /close`로 명시적으로 수행한다.

---

## 6. API 설계 원칙

본 API는 두 가지 스타일을 의도적으로 혼합한다.

- **CRUD 조회/생성**: 정통 RESTful — `POST /api/courses`, `GET /api/courses/{id}`, `GET /api/me/enrollments` 등
- **도메인 액션**: action endpoint — `POST /enrollments/{id}/confirm`, `POST /enrollments/{id}/cancel`, `POST /courses/{id}/open` 등

이는 strict REST 원칙(`자원=명사, 액션=HTTP 메서드`)에서 부분적으로 벗어난 결정으로, 다음 트레이드오프 검토를 거쳤다.

### 6.1 검토한 대안

| 옵션 | 형태 | 평가 |
|---|---|---|
| **A. action endpoint (선택)** | `POST /enrollments/{id}/cancel` | 도메인 의도가 path에 명시. 핸들러 분리가 깔끔. side-effect를 가지는 도메인 액션과 잘 어울림 |
| B. PATCH로 통일 | `PATCH /enrollments/{id}` body `{ "status": "CANCELLED" }` | REST 정통. 그러나 모든 상태 변경이 한 엔드포인트로 모이며, 의도/권한/검증 분기가 핸들러 내부에서 복잡해짐. 결제 확정과 단순 필드 수정이 같은 엔드포인트로 섞임 |
| C. 별도 자원 모델링 | `POST /payments` body `{ "enrollmentId": 100 }` | REST 정통 + 감사 추적 자연스러움. 그러나 도메인 모델에 `payments` 엔티티가 없어 가상 자원이 되며, 우리 규모에 비해 과한 모델링 |

### 6.2 A안을 선택한 이유

1. **도메인 의미가 path에 직접 드러난다.** `POST /enrollments/{id}/cancel`은 별도 문서 없이도 의도가 즉시 파악된다.
2. **각 액션이 독립 핸들러.** 결제 확정과 취소는 부수효과(자동 승격, `occupied_count` 변동)와 권한 검증이 서로 다르다. 핸들러 분리로 단위 테스트와 동시성 분석이 명료해진다.
3. **상태 전이 규칙 친화적.** 본 도메인은 단순 필드 변경이 아니라 정밀한 전이 규칙(예: `PENDING → CONFIRMED`, `confirmed_at + N일` 이내만 취소 가능)을 가진다. PATCH 통합은 핸들러에서 분기 폭증을 야기한다.
4. **업계 표준 일치.** Stripe (`POST /payment_intents/{id}/confirm`), GitHub (`POST /issues/{n}/lock`), Google Cloud (`:undelete`) 등 비슷한 도메인의 표준 API들이 동일한 패턴을 채택한다.

### 6.3 강의 상태 전이를 단일 PATCH가 아닌 두 액션으로 분리한 이유

처음에는 `PATCH /courses/{id}/status` body `{ "to": "OPEN" }` 같은 단일 엔드포인트 + body 값 분기 형태도 검토했다. 결과적으로 다음 이유로 **두 개의 액션 엔드포인트**로 분리했다.

```
POST /api/courses/{id}/open    (DRAFT → OPEN)
POST /api/courses/{id}/close   (OPEN  → CLOSED)
```

- 다른 액션 엔드포인트(`/confirm`, `/cancel`)와 **패턴 일관성** 확보.
- 각 전이가 가지는 비즈니스 의미와 부수효과가 다르다. 특히 `close`는 이후 발생하는 취소에 대해 **대기자 자동 승격을 중단**시키는 부수효과가 있어, 단순 status 필드 변경과 결이 다르다.
- 향후 "강의 정보 수정" `PATCH /courses/{id}`가 추가되어도 경로/의미 충돌이 없다.

### 6.4 RESTful 부분은 그대로 유지

다음 엔드포인트는 RESTful 컨벤션을 그대로 따른다.

- **자원 생성**: `POST /api/courses`, `POST /api/courses/{id}/enrollments`
- **자원 조회**: `GET /api/courses`, `GET /api/courses/{id}`, `GET /api/me/enrollments` 등

즉, **CRUD는 REST를 따르되 도메인 액션만 action endpoint로 모델링**하는 혼합 스타일이다. REST 정통성보다 도메인 의도와 핸들러 분리의 명료성을 우선한 실용적 선택이다.

---

## 7. API 상세

각 API는 다음 항목으로 구성한다.

- 목적
- 권한
- 요청
- 응답
- 비즈니스 규칙
- 주요 실패 응답

---

### 7.1 강의 등록

**`POST /api/courses`**

크리에이터가 강의를 신규 등록한다. `status`는 항상 `DRAFT`로 생성된다.

**권한**: CREATOR

**요청**

```http
POST /api/courses
X-USER-ID: 1
Content-Type: application/json

{
  "title": "Java 마스터 클래스",
  "description": "Spring Boot 실전 강의",
  "price": 150000,
  "capacity": 30,
  "startDate": "2026-06-01",
  "endDate": "2026-08-31",
  "cancellationDeadlineDays": 7
}
```

**요청 본문 필드**

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| `title` | string | ✓ | 1~100자 |
| `description` | string | | 0~10,000자 (NULL 허용) |
| `price` | long | ✓ | `>= 0` |
| `capacity` | int | ✓ | `>= 1` |
| `startDate` | date | ✓ | `<= endDate` |
| `endDate` | date | ✓ | `>= startDate` |
| `cancellationDeadlineDays` | int | | `>= 0`, 기본 7 |

`status`, `occupiedCount`, `creatorId`, `createdAt` 등은 서버가 결정하며 요청에서 받지 않는다.

**응답** (201 Created)

```json
{
  "id": 1,
  "creatorId": 1,
  "title": "Java 마스터 클래스",
  "description": "Spring Boot 실전 강의",
  "price": 150000,
  "capacity": 30,
  "occupiedCount": 0,
  "remainingCount": 30,
  "startDate": "2026-06-01",
  "endDate": "2026-08-31",
  "status": "DRAFT",
  "cancellationDeadlineDays": 7,
  "createdAt": "2026-05-20T14:30:00+09:00",
  "updatedAt": "2026-05-20T14:30:00+09:00"
}
```

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 입력 검증 실패 |
| 401 | `UNAUTHENTICATED` | `X-USER-ID` 누락 또는 유효하지 않음 |
| 403 | `FORBIDDEN` | `STUDENT`가 호출 |

---

### 7.2 강의 모집 시작

**`POST /api/courses/{id}/open`**

강의 상태를 `DRAFT → OPEN`으로 전이한다. 이 시점부터 수강 신청 접수가 시작된다.

**권한**: CREATOR (본인 강의만)

**요청**

```http
POST /api/courses/1/open
X-USER-ID: 1
```

요청 본문 없음.

**응답** (200 OK)

```json
{
  "id": 1,
  "status": "OPEN",
  "updatedAt": "2026-05-20T15:00:00+09:00"
}
```

**비즈니스 규칙**

- 현재 `status == DRAFT` 여야 한다.
- 강의 본문 전체가 아닌 `id`, `status`, `updatedAt`만 반환한다 (상태 전이 응답을 가볍게 유지).

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `NOT_COURSE_OWNER` | 본인 강의 아님 |
| 404 | `COURSE_NOT_FOUND` | 강의 없음 |
| 409 | `INVALID_TRANSITION` | 현재 `DRAFT`가 아님 |

---

### 7.3 강의 모집 마감

**`POST /api/courses/{id}/close`**

강의 상태를 `OPEN → CLOSED`로 전이한다. 신규 신청과 대기열 등록이 차단되며, 이후 발생하는 취소에 대해 **대기자 자동 승격도 중단된다.**

**권한**: CREATOR (본인 강의만)

**요청**

```http
POST /api/courses/1/close
X-USER-ID: 1
```

요청 본문 없음.

**응답** (200 OK)

```json
{
  "id": 1,
  "status": "CLOSED",
  "updatedAt": "2026-05-20T20:00:00+09:00"
}
```

**비즈니스 규칙**

- 현재 `status == OPEN` 여야 한다.
- 기존 `PENDING`/`CONFIRMED` 신청은 영향 없음 (그대로 유지).
- 기존 `WAITING` 대기열도 영향 없음 (사용자가 직접 취소하거나 그대로 남음).
- **CLOSED는 신규 신청과 대기자 자동 승격만 차단한다.** 이미 생성된 `PENDING`의 결제 확정, 그리고 `PENDING`/`CONFIRMED`의 취소는 기존 상태 전이 규칙에 따라 그대로 허용한다.
- 이후 발생하는 수강 취소 시 `occupied_count` 감소는 그대로 수행되지만, 대기자 자동 승격은 수행되지 않는다 (ERD 6번 정책).

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `NOT_COURSE_OWNER` | 본인 강의 아님 |
| 404 | `COURSE_NOT_FOUND` | 강의 없음 |
| 409 | `INVALID_TRANSITION` | 현재 `OPEN`이 아님 |

---

### 7.4 강의 목록 조회

**`GET /api/courses`**

**권한**: ANY

> 본 과제에서는 단순화를 위해 모든 상태(`DRAFT`/`OPEN`/`CLOSED`)를 ANY가 조회할 수 있다. 실제 서비스에서는 `STUDENT`에게 `DRAFT`를 숨기고, `CREATOR`는 본인이 만든 `DRAFT`만 보이도록 권한별 필터링을 적용하는 것이 자연스럽다.

**요청 파라미터**

| 파라미터 | 타입 | 비고 |
|---|---|---|
| `status` | enum | `DRAFT` / `OPEN` / `CLOSED` (선택) |
| `page`, `size` | int | 페이지네이션 |

**응답** (200 OK)

```json
{
  "content": [
    {
      "id": 1,
      "creatorId": 1,
      "title": "Java 마스터 클래스",
      "price": 150000,
      "capacity": 30,
      "occupiedCount": 12,
      "remainingCount": 18,
      "startDate": "2026-06-01",
      "endDate": "2026-08-31",
      "status": "OPEN",
      "createdAt": "2026-05-20T14:30:00+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

정렬: `created_at DESC, id DESC`

> 목록 응답에는 `description`을 포함하지 않는다(상세에서만 제공).

---

### 7.5 강의 상세 조회

**`GET /api/courses/{id}`**

**권한**: ANY

**응답** (200 OK)

```json
{
  "id": 1,
  "creatorId": 1,
  "title": "Java 마스터 클래스",
  "description": "Spring Boot 실전 강의",
  "price": 150000,
  "capacity": 30,
  "occupiedCount": 12,
  "remainingCount": 18,
  "startDate": "2026-06-01",
  "endDate": "2026-08-31",
  "status": "OPEN",
  "cancellationDeadlineDays": 7,
  "createdAt": "2026-05-20T14:30:00+09:00",
  "updatedAt": "2026-05-20T15:00:00+09:00"
}
```

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 404 | `COURSE_NOT_FOUND` | 강의 없음 |

---

### 7.6 수강 신청

**`POST /api/courses/{id}/enrollments`**

학생이 강의에 신청한다. 정원이 남았으면 `Enrollment(PENDING)`을, 정원이 찼으면 `Waitlist(WAITING)`를 생성한다. **둘 다 동일 엔드포인트의 정상 결과**이며 응답의 `outcome` 필드로 구분한다.

**권한**: STUDENT

**요청**

```http
POST /api/courses/1/enrollments
X-USER-ID: 5
```

요청 본문 없음.

**응답 — 정원 내 신청 성공** (201 Created)

```json
{
  "outcome": "ENROLLED",
  "enrollment": {
    "id": 100,
    "courseId": 1,
    "userId": 5,
    "status": "PENDING",
    "promotedFromWaitlistId": null,
    "createdAt": "2026-05-20T16:00:00+09:00"
  },
  "waitlist": null
}
```

**응답 — 대기열 등록 성공** (201 Created)

```json
{
  "outcome": "WAITLISTED",
  "enrollment": null,
  "waitlist": {
    "id": 50,
    "courseId": 1,
    "userId": 5,
    "status": "WAITING",
    "position": 3,
    "createdAt": "2026-05-20T16:00:00+09:00"
  }
}
```

`position`은 현재 시점의 대기열 순번 (1부터).

**비즈니스 규칙**

1. `courses` 행을 `SELECT FOR UPDATE`로 잠근다.
2. `course.status == OPEN` 확인.
3. 동일 사용자/동일 강의의 활성 신청(`PENDING`/`CONFIRMED`) 또는 활성 대기(`WAITING`) 중복 차단.
4. `occupied_count < capacity` 분기:
   - **YES**: `Enrollment(PENDING)` 생성, `occupied_count += 1`, 응답 `ENROLLED`
   - **NO**: `Waitlist(WAITING)` 생성, `occupied_count` 변화 없음, 응답 `WAITLISTED`

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `FORBIDDEN` | `CREATOR`가 호출 |
| 404 | `COURSE_NOT_FOUND` | 강의 없음 |
| 409 | `COURSE_NOT_OPEN` | 강의 상태가 `OPEN` 아님 |
| 409 | `DUPLICATE_ENROLLMENT` | 동일 강의 활성 신청 이미 존재 |
| 409 | `DUPLICATE_WAITLIST` | 동일 강의 활성 대기 이미 존재 |

---

### 7.7 결제 확정

**`POST /api/enrollments/{id}/confirm`**

`PENDING → CONFIRMED`. 외부 결제 시스템은 연동하지 않고 단순 상태 변경. 본 도메인에 `Payment` 엔티티가 없어 경로명을 `confirm`으로 사용한다 (실제 결제 자원이 있을 때만 `/payment`로 표기하는 편이 정확).

**권한**: STUDENT (본인 신청만)

**요청**

```http
POST /api/enrollments/100/confirm
X-USER-ID: 5
```

**응답** (200 OK)

```json
{
  "id": 100,
  "courseId": 1,
  "userId": 5,
  "status": "CONFIRMED",
  "confirmedAt": "2026-05-20T17:00:00+09:00",
  "promotedFromWaitlistId": null,
  "createdAt": "2026-05-20T16:00:00+09:00"
}
```

**비즈니스 규칙**

- 본인 신청인지 확인.
- 현재 `status == PENDING` 확인.
- 동시 취소와의 race를 방어하기 위해 UPDATE에 가드 조건을 둔다. JPA Auditing이 native UPDATE에 적용되지 않으므로 `updated_at`도 명시:
  - `UPDATE enrollments SET status='CONFIRMED', confirmed_at=now(), updated_at=now() WHERE id=? AND status='PENDING'`
  - 영향 받은 row가 0이면 `409 INVALID_STATUS` 반환.

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `NOT_ENROLLMENT_OWNER` | 본인 신청 아님 |
| 404 | `ENROLLMENT_NOT_FOUND` | 신청 없음 |
| 409 | `INVALID_STATUS` | 현재 `PENDING`이 아님 (이미 취소/확정됨) |

---

### 7.8 수강 취소

**`POST /api/enrollments/{id}/cancel`**

`PENDING` 또는 `CONFIRMED` → `CANCELLED`. 슬롯이 비면 가장 오래된 `WAITING` 대기자를 자동 승격한다(강의 `OPEN`인 경우에 한함).

**권한**: STUDENT (본인 신청만)

**요청**

```http
POST /api/enrollments/100/cancel
X-USER-ID: 5
```

**응답** (200 OK) — 승격자가 있는 경우

```json
{
  "enrollment": {
    "id": 100,
    "status": "CANCELLED",
    "cancelledAt": "2026-05-20T18:00:00+09:00"
  },
  "promoted": {
    "enrollmentId": 101,
    "fromWaitlistId": 50,
    "userId": 7
  }
}
```

**응답** (200 OK) — 승격자가 없거나 강의가 `CLOSED`인 경우

```json
{
  "enrollment": {
    "id": 100,
    "status": "CANCELLED",
    "cancelledAt": "2026-05-20T18:00:00+09:00"
  },
  "promoted": null
}
```

**비즈니스 규칙**

1. 본인 신청 확인.
2. `courses` 행 `SELECT FOR UPDATE` (자동 승격 처리를 위해).
3. `enrollments` 행 `SELECT FOR UPDATE` (결제 확정과의 race를 직렬화). 락 획득 순서: **course → enrollment**.
4. 락 후 재조회로 현재 상태 확인:
   - `CANCELLED` → 409 `ALREADY_CANCELLED`
   - `PENDING` 또는 `CONFIRMED`가 아닌 그 외 → 409 `INVALID_STATUS`
5. `CONFIRMED`인 경우 `confirmed_at + cancellation_deadline_days` 이내 확인 (`now() <= confirmed_at + deadline`).
6. `Enrollment.status = CANCELLED`로 전이. 가드 조건 UPDATE에 `updated_at` 명시 (JPA Auditing이 native UPDATE에 적용되지 않으므로):
   - `UPDATE enrollments SET status='CANCELLED', cancelled_at=now(), updated_at=now() WHERE id=?`
7. `UPDATE courses SET occupied_count = occupied_count - 1, updated_at=now() WHERE id=?`.
8. `course.status == OPEN`이면 가장 오래된 `WAITING` 1명 자동 승격:
   - **대상 `Waitlist` 가드 조건 UPDATE**로 동시 대기 취소와의 race를 차단:
     - `UPDATE waitlists SET status='PROMOTED', promoted_at=now(), updated_at=now() WHERE id=? AND status='WAITING'`
     - 영향 0이면 이미 취소된 대기자이므로 **다음 후보**(`created_at` 다음 순서)를 시도. 더 이상 후보가 없으면 승격 없이 종료.
   - 새 `Enrollment` 생성 (`status=PENDING`, `promoted_from_waitlist_id` 채움).
   - `occupied_count += 1`.
9. `course.status == CLOSED`이면 8번 자동 승격은 건너뛴다. (단, 6·7번 취소 처리와 `occupied_count` 감소는 그대로 수행)

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `NOT_ENROLLMENT_OWNER` | 본인 신청 아님 |
| 404 | `ENROLLMENT_NOT_FOUND` | 신청 없음 |
| 409 | `ALREADY_CANCELLED` | 이미 취소됨 |
| 409 | `CANCEL_DEADLINE_EXCEEDED` | 취소 가능 기간 초과 |

---

### 7.9 내 수강 신청 목록

**`GET /api/me/enrollments`**

**권한**: STUDENT

**요청 파라미터**

| 파라미터 | 타입 | 비고 |
|---|---|---|
| `status` | enum | `PENDING` / `CONFIRMED` / `CANCELLED` (선택) |
| `page`, `size` | int | 페이지네이션 |

**응답** (200 OK)

```json
{
  "content": [
    {
      "id": 100,
      "courseId": 1,
      "courseTitle": "Java 마스터 클래스",
      "courseStatus": "OPEN",
      "status": "CONFIRMED",
      "promotedFromWaitlistId": null,
      "confirmedAt": "2026-05-20T17:00:00+09:00",
      "cancelledAt": null,
      "createdAt": "2026-05-20T16:00:00+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

정렬: `enrollments.created_at DESC, id DESC`

---

### 7.10 대기 취소

**`POST /api/waitlists/{id}/cancel`**

`WAITING → CANCELLED`.

**권한**: STUDENT (본인 대기만)

**요청**

```http
POST /api/waitlists/50/cancel
X-USER-ID: 5
```

**응답** (200 OK)

```json
{
  "id": 50,
  "status": "CANCELLED",
  "cancelledAt": "2026-05-20T18:30:00+09:00"
}
```

**비즈니스 규칙**

- 본인 대기 확인.
- 현재 `status == WAITING` 확인.
- 가드 조건 UPDATE로 동시 승격과의 race 방어. JPA Auditing이 native UPDATE에 적용되지 않으므로 `updated_at`도 명시:
  - `UPDATE waitlists SET status='CANCELLED', cancelled_at=now(), updated_at=now() WHERE id=? AND status='WAITING'`
  - 영향 0이면 `409 INVALID_STATUS`.

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `NOT_WAITLIST_OWNER` | 본인 대기 아님 |
| 404 | `WAITLIST_NOT_FOUND` | 대기 없음 |
| 409 | `INVALID_STATUS` | 이미 `PROMOTED` 또는 `CANCELLED` |

---

### 7.11 내 대기 목록

**`GET /api/me/waitlists`**

**권한**: STUDENT

**요청 파라미터**

| 파라미터 | 타입 | 비고 |
|---|---|---|
| `status` | enum | `WAITING` / `PROMOTED` / `CANCELLED` (선택) |
| `page`, `size` | int | 페이지네이션 |

**응답** (200 OK)

```json
{
  "content": [
    {
      "id": 50,
      "courseId": 1,
      "courseTitle": "Java 마스터 클래스",
      "status": "WAITING",
      "position": 3,
      "promotedAt": null,
      "cancelledAt": null,
      "createdAt": "2026-05-20T16:00:00+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

`position`은 `WAITING` 항목에서만 의미가 있다. `PROMOTED`/`CANCELLED`는 `null`.

정렬: `waitlists.created_at DESC, id DESC`

---

### 7.12 강의별 수강생 목록 (크리에이터 전용)

**`GET /api/courses/{id}/enrollments`**

본인 강의의 수강 신청 목록.

**권한**: CREATOR (본인 강의만)

**요청 파라미터**

| 파라미터 | 타입 | 비고 |
|---|---|---|
| `status` | enum | `PENDING` / `CONFIRMED` / `CANCELLED` (선택) |
| `page`, `size` | int | 페이지네이션 |

**응답** (200 OK)

```json
{
  "content": [
    {
      "id": 100,
      "userId": 5,
      "userName": "박학생",
      "status": "CONFIRMED",
      "promotedFromWaitlistId": null,
      "confirmedAt": "2026-05-20T17:00:00+09:00",
      "cancelledAt": null,
      "createdAt": "2026-05-20T16:00:00+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 12,
  "totalPages": 1,
  "hasNext": false
}
```

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `NOT_COURSE_OWNER` | 본인 강의 아님 |
| 404 | `COURSE_NOT_FOUND` | 강의 없음 |

---

### 7.13 강의별 대기자 목록 (크리에이터 전용)

**`GET /api/courses/{id}/waitlists`**

본인 강의의 대기열 목록.

**권한**: CREATOR (본인 강의만)

**요청 파라미터**

| 파라미터 | 타입 | 비고 |
|---|---|---|
| `status` | enum | `WAITING` / `PROMOTED` / `CANCELLED` (선택) |
| `page`, `size` | int | 페이지네이션 |

**응답** (200 OK)

```json
{
  "content": [
    {
      "id": 50,
      "userId": 7,
      "userName": "김대기",
      "status": "WAITING",
      "position": 1,
      "promotedAt": null,
      "cancelledAt": null,
      "createdAt": "2026-05-20T16:30:00+09:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

크리에이터의 대기자 목록은 운영 가시성용이며, 기본 정렬은 `WAITING`은 `created_at ASC, id ASC` (대기 순서). 그 외 상태는 `created_at DESC, id DESC`.

**주요 실패**

| HTTP | code | 상황 |
|---|---|---|
| 403 | `NOT_COURSE_OWNER` | 본인 강의 아님 |
| 404 | `COURSE_NOT_FOUND` | 강의 없음 |

---

## 8. 상태 코드 매트릭스

| 상황 | HTTP | code 예시 |
|---|---|---|
| 조회 성공 | 200 OK | — |
| 생성 성공 | 201 Created | — |
| 상태 변경 성공 | 200 OK | — |
| 입력 검증 실패 | 400 Bad Request | `INVALID_REQUEST` |
| 미인증 (헤더 누락 / 유효 X) | 401 Unauthorized | `UNAUTHENTICATED` |
| 권한 없음 (role 불일치) | 403 Forbidden | `FORBIDDEN` |
| 소유권 위반 | 403 Forbidden | `NOT_COURSE_OWNER`, `NOT_ENROLLMENT_OWNER`, `NOT_WAITLIST_OWNER` |
| 리소스 없음 | 404 Not Found | `COURSE_NOT_FOUND`, `ENROLLMENT_NOT_FOUND`, `WAITLIST_NOT_FOUND` |
| 상태 충돌 / 비즈니스 규칙 위반 | 409 Conflict | `COURSE_NOT_OPEN`, `INVALID_TRANSITION`, `DUPLICATE_ENROLLMENT`, `DUPLICATE_WAITLIST`, `INVALID_STATUS`, `ALREADY_CANCELLED`, `CANCEL_DEADLINE_EXCEEDED` |
| 서버 오류 | 500 Internal Server Error | `INTERNAL_ERROR` |

---

## 9. 다음 문서 연결

본 문서는 **API 표면(인터페이스)**과 그 설계 원칙만 정의한다. 상세 내부 정책은 다음 문서에서 다룬다.

- 상태 전이 상세 정책 — `docs/STATE_TRANSITIONS.md`
- 동시성 제어 흐름 — `docs/CONCURRENCY.md`
- 예외 코드 카탈로그 — `docs/ERROR_CODES.md`
- 테스트 시나리오 — `docs/TEST_SCENARIOS.md`
