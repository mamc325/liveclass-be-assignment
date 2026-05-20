# BE-A 수강 신청 시스템 상태 전이 정책

> 작성일: 2026-05-20
> 대상: 라이브클래스 BE-A 채용 과제

---

## 1. 문서 목적

본 문서는 `Course`, `Enrollment`, `Waitlist` 세 도메인의 상태 머신을 정밀하게 명세한다. 각 전이의 **선결 조건(precondition)**, **부수 효과(side effect)**, **시간 기록(audit timestamp)**, **권한**, **실패 매핑**을 한곳에 모아 구현/테스트의 직접적인 기준점으로 사용한다.

ERD와 API 문서는 표면적인 규칙을 다루고, 본 문서는 **상태 전이 단위로 코드 가드를 어떻게 작성할지를 결정하는 원천**이다.

상호 참조:
- 데이터 모델 → `docs/ERD.md`
- API 인터페이스 → `docs/API.md`
- 동시성 흐름 (별도 정리) → `docs/CONCURRENCY.md` (작성 예정)

---

## 2. 도메인별 상태 머신 개요

```
Course:    [없음] ──생성──→ DRAFT ──open──→ OPEN ──close──→ CLOSED

Enrollment: [없음] ──신청──→ PENDING ──confirm──→ CONFIRMED
                                │                    │
                                └──cancel──→ CANCELLED ←──cancel(기간 내)──┘

Waitlist:  [없음] ──등록──→ WAITING ──자동 승격──→ PROMOTED
                              │
                              └──사용자 취소──→ CANCELLED
```

세 도메인은 모두 **단방향 라이프사이클**이며, 종단 상태(`CLOSED`, `CANCELLED`, `PROMOTED`)에서 다른 상태로 되돌아가지 않는다.

---

## 3. Course 상태 전이

### 3.1 상태 머신

```
[없음] ─── creation ───→ DRAFT
                          │
                          └── open ──→ OPEN
                                        │
                                        └── close ──→ CLOSED
```

### 3.2 전이 매트릭스

| from \ to | DRAFT | OPEN | CLOSED |
|---|---|---|---|
| **[없음]** | ✅ creation | ✗ | ✗ |
| **DRAFT** | — | ✅ open | ✗ |
| **OPEN** | ✗ | — | ✅ close |
| **CLOSED** | ✗ | ✗ | — |

- 역방향 전이(예: `CLOSED → OPEN`)는 일절 허용하지 않는다.
- `DRAFT → CLOSED` 직접 마감도 금지(반드시 `OPEN`을 경유).

### 3.3 전이별 상세

#### Transition: [없음] → DRAFT (`creation`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/courses` |
| 선결 조건 | 호출자 `role == CREATOR` / 요청 본문 검증 통과 (`title 1~100자`, `price >= 0`, `capacity >= 1`, `startDate <= endDate`, `cancellationDeadlineDays >= 0`) |
| 부수 효과 | `INSERT INTO courses (...) VALUES (... status='DRAFT', occupied_count=0, creator_id=X-USER-ID, ...)` |
| 시간 기록 | `created_at = now()`, `updated_at = now()` |
| 권한 | CREATOR |
| 실패 매핑 | 400 `INVALID_REQUEST` / 401 `UNAUTHENTICATED` / 403 `FORBIDDEN` |

#### Transition: DRAFT → OPEN (`open`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/courses/{id}/open` |
| 선결 조건 | 호출자 = `course.creator_id` / 현재 `status == DRAFT` |
| 부수 효과 | `UPDATE courses SET status='OPEN', updated_at=now() WHERE id=? AND status='DRAFT'` |
| 시간 기록 | `updated_at = now()` (UPDATE 문에 직접 명시) |
| 권한 | CREATOR (본인 강의) |
| 실패 매핑 | 403 `NOT_COURSE_OWNER` / 404 `COURSE_NOT_FOUND` / 409 `INVALID_TRANSITION` (가드 조건 UPDATE 영향 0) |

#### Transition: OPEN → CLOSED (`close`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/courses/{id}/close` |
| 선결 조건 | 호출자 = `course.creator_id` / 현재 `status == OPEN` |
| 부수 효과 | `UPDATE courses SET status='CLOSED', updated_at=now() WHERE id=? AND status='OPEN'` |
| 시간 기록 | `updated_at = now()` (UPDATE 문에 직접 명시) |
| 권한 | CREATOR (본인 강의) |
| 실패 매핑 | 403 `NOT_COURSE_OWNER` / 404 `COURSE_NOT_FOUND` / 409 `INVALID_TRANSITION` |
| 추가 효과 | 이후 발생하는 수강 취소에서 **대기자 자동 승격이 차단**된다 (6.1 절 참조) |

> **CLOSED 후에도 허용되는 전이**: 기존 `PENDING`의 결제 확정(`PENDING → CONFIRMED`), 기존 `PENDING`/`CONFIRMED`의 취소(`→ CANCELLED`)는 그대로 허용된다. CLOSED는 **신규 신청 및 대기자 자동 승격**만 차단한다.

---

## 4. Enrollment 상태 전이

### 4.1 상태 머신

```
[없음] ──신청 (정원 내)─→ PENDING ──confirm──→ CONFIRMED
                              │                    │
                              └─cancel─→ CANCELLED ←─cancel (기간 내)──┘
```

대기열 자동 승격으로 생성된 `Enrollment`도 동일하게 `PENDING`으로 시작한다. 일반 신청과 구분은 `promoted_from_waitlist_id` 컬럼으로만 한다.

### 4.2 전이 매트릭스

| from \ to | PENDING | CONFIRMED | CANCELLED |
|---|---|---|---|
| **[없음]** | ✅ direct creation / promoted creation | ✗ | ✗ |
| **PENDING** | — | ✅ confirm | ✅ cancel |
| **CONFIRMED** | ✗ | — | ✅ cancel (기간 내) |
| **CANCELLED** | ✗ | ✗ | — |

### 4.3 전이별 상세

#### Transition: [없음] → PENDING — direct creation (`enrollment 신청`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/courses/{id}/enrollments` (정원 내) |
| 선결 조건 | 호출자 `role == STUDENT` / 강의 존재 / `course.status == OPEN` / **동일 사용자의 활성(`PENDING`/`CONFIRMED`) 신청 없음** / **동일 사용자의 활성(`WAITING`) 대기 없음** / `course.occupied_count < course.capacity` |
| 부수 효과 | `INSERT INTO enrollments (status='PENDING', promoted_from_waitlist_id=NULL, ...)` / `UPDATE courses SET occupied_count = occupied_count + 1, updated_at=now() WHERE id=?` |
| 시간 기록 | `created_at = now()`, `updated_at = now()` |
| 권한 | STUDENT |
| 락 | `courses` row `SELECT FOR UPDATE` |
| 실패 매핑 | 403 `FORBIDDEN` / 404 `COURSE_NOT_FOUND` / 409 `COURSE_NOT_OPEN` / 409 `DUPLICATE_ENROLLMENT` / 409 `DUPLICATE_WAITLIST` |

#### Transition: [없음] → PENDING — promoted creation (대기열 자동 승격)

| 항목 | 내용 |
|---|---|
| Trigger | 다른 `Enrollment`의 `→ CANCELLED` 전이가 빈 슬롯을 만들고, 해당 강의가 `OPEN`이며, `WAITING` 대기자가 있을 때 자동 발생 (사용자 직접 호출 불가) |
| 선결 조건 | 부모 트랜잭션(취소 처리)이 `courses` row 락을 보유 / 가장 오래된 `WAITING` 후보에 대한 가드 조건 UPDATE 성공 |
| 부수 효과 | `INSERT INTO enrollments (status='PENDING', promoted_from_waitlist_id=?, user_id=대기자, ...)` / `occupied_count`는 동일 트랜잭션 안에서 `-1 + 1 = 0` 순변동(취소 + 승격) |
| 시간 기록 | `created_at = now()`, `updated_at = now()` |
| 권한 | 시스템 (자동) |
| 실패 매핑 | 적용 안 됨 — `WAITING` 후보가 없거나 race로 모두 실패하면 단순히 승격 없이 종료 |

#### Transition: PENDING → CONFIRMED (`confirm`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/enrollments/{id}/confirm` |
| 선결 조건 | 호출자 = `enrollment.user_id` / 현재 `status == PENDING` |
| 부수 효과 | `UPDATE enrollments SET status='CONFIRMED', confirmed_at=now(), updated_at=now() WHERE id=? AND status='PENDING'` (결제-취소 race 방어 가드 조건) |
| 시간 기록 | `confirmed_at = now()`, `updated_at = now()` (UPDATE 문에 직접 명시) |
| 권한 | STUDENT (본인 신청) |
| 락 | 별도 락 불필요. 가드 조건 UPDATE의 PostgreSQL row-level atomic 보장으로 cancel과의 race 차단 |
| 실패 매핑 | 403 `NOT_ENROLLMENT_OWNER` / 404 `ENROLLMENT_NOT_FOUND` / 409 `INVALID_STATUS` (가드 영향 0) |
| 비고 | `course.status`와 무관하게 `PENDING`이라면 결제 확정 가능 (CLOSED여도 허용) |

#### Transition: PENDING → CANCELLED (`cancel`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/enrollments/{id}/cancel` |
| 선결 조건 | 호출자 = `enrollment.user_id` / 락 획득 후 재조회 시 `status == PENDING` |
| 부수 효과 | `UPDATE enrollments SET status='CANCELLED', cancelled_at=now(), updated_at=now() WHERE id=?` / `UPDATE courses SET occupied_count = occupied_count - 1, updated_at=now() WHERE id=?` / (강의 `OPEN`이면) **대기열 자동 승격 트리거** |
| 시간 기록 | `cancelled_at = now()`, `updated_at = now()` (UPDATE 문에 직접 명시) |
| 권한 | STUDENT (본인 신청) |
| 락 | **`courses` row `SELECT FOR UPDATE` + `enrollments` row `SELECT FOR UPDATE`** (락 획득 순서: course → enrollment) |
| 실패 매핑 | 403 `NOT_ENROLLMENT_OWNER` / 404 `ENROLLMENT_NOT_FOUND` / 409 `ALREADY_CANCELLED` (락 후 재조회 시 `CANCELLED` 확인) / 409 `INVALID_STATUS` (그 외 비허용 상태) |
| 비고 | `PENDING` 상태는 결제 전이므로 취소 기간 제한 없음 |

#### Transition: CONFIRMED → CANCELLED (`cancel within deadline`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/enrollments/{id}/cancel` |
| 선결 조건 | 호출자 = `enrollment.user_id` / 락 획득 후 재조회 시 `status == CONFIRMED` / `now() <= confirmed_at + course.cancellation_deadline_days` |
| 부수 효과 | `PENDING → CANCELLED`와 동일 (`occupied_count -= 1`, 자동 승격 트리거) |
| 시간 기록 | `cancelled_at = now()`, `updated_at = now()` (UPDATE 문에 직접 명시) |
| 권한 | STUDENT (본인 신청) |
| 락 | **`courses` row `SELECT FOR UPDATE` + `enrollments` row `SELECT FOR UPDATE`** |
| 실패 매핑 | 403 `NOT_ENROLLMENT_OWNER` / 404 `ENROLLMENT_NOT_FOUND` / 409 `CANCEL_DEADLINE_EXCEEDED` (기간 초과) / 409 `ALREADY_CANCELLED` (락 후 재조회 시 `CANCELLED`) / 409 `INVALID_STATUS` (그 외 비허용 상태) |
| 비고 | `course.status == CLOSED`여도 본 전이는 허용된다 |

> **취소 가드 실패 코드 구분 정책**
>
> 사전 조회 결과로 분기:
> - 조회 결과 이미 `CANCELLED` → `ALREADY_CANCELLED`
> - 조회 결과 `PENDING`/`CONFIRMED`가 아닌 다른 비허용 상태 → `INVALID_STATUS` (현재 도메인에서는 발생 안 하지만 방어적)
> - 락 후 재조회에서 상태가 변동되었다면 동일 기준으로 분기
>
> 단순화 옵션: 락 후 재조회 결과 `PENDING`/`CONFIRMED`가 아니면 일괄 `INVALID_STATUS`로 처리하고, `CANCELLED`인 경우만 `ALREADY_CANCELLED`로 세분화한다.

---

## 5. Waitlist 상태 전이

### 5.1 상태 머신

```
[없음] ──등록 (정원 초과)─→ WAITING ──자동 승격──→ PROMOTED
                                │
                                └──사용자 취소──→ CANCELLED
```

### 5.2 전이 매트릭스

| from \ to | WAITING | PROMOTED | CANCELLED |
|---|---|---|---|
| **[없음]** | ✅ creation | ✗ | ✗ |
| **WAITING** | — | ✅ 자동 승격 | ✅ 사용자 취소 |
| **PROMOTED** | ✗ | — | ✗ |
| **CANCELLED** | ✗ | ✗ | — |

### 5.3 전이별 상세

#### Transition: [없음] → WAITING (`waitlist 등록`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/courses/{id}/enrollments` (정원 초과 시) |
| 선결 조건 | 호출자 `role == STUDENT` / 강의 존재 / `course.status == OPEN` / 동일 사용자의 활성(`PENDING`/`CONFIRMED`) 신청 없음 / 동일 사용자의 활성(`WAITING`) 대기 없음 / `course.occupied_count >= course.capacity` |
| 부수 효과 | `INSERT INTO waitlists (status='WAITING', ...)` |
| 시간 기록 | `created_at = now()`, `updated_at = now()` |
| 권한 | STUDENT |
| 락 | `courses` row `SELECT FOR UPDATE` (수강 신청과 동일 트랜잭션 분기) |
| 실패 매핑 | 403 `FORBIDDEN` / 404 `COURSE_NOT_FOUND` / 409 `COURSE_NOT_OPEN` / 409 `DUPLICATE_ENROLLMENT` / 409 `DUPLICATE_WAITLIST` |

#### Transition: WAITING → PROMOTED (`자동 승격`)

| 항목 | 내용 |
|---|---|
| Trigger | 다른 `Enrollment`의 `→ CANCELLED` 전이로 빈 슬롯 발생, 해당 강의가 `OPEN`일 때 (사용자 직접 호출 불가) |
| 선결 조건 | 부모 트랜잭션이 `courses` row 락 보유 / 가장 오래된 `WAITING` 후보(`ORDER BY created_at ASC, id ASC`)에 대한 가드 조건 UPDATE 성공 |
| 부수 효과 | `UPDATE waitlists SET status='PROMOTED', promoted_at=now(), updated_at=now() WHERE id=? AND status='WAITING'` / 동일 트랜잭션에서 신규 `Enrollment(PENDING)` 생성, `promoted_from_waitlist_id`에 본 row id 기록 |
| 시간 기록 | `promoted_at = now()`, `updated_at = now()` (UPDATE 문에 직접 명시) |
| 권한 | 시스템 (자동) |
| 실패 매핑 | 가드 영향 0이면 race 발생 — 다음 후보를 시도. 모든 후보가 실패하면 승격 없이 종료. |
| 비고 | `course.status == CLOSED`이면 본 전이는 발동되지 않는다 |

#### Transition: WAITING → CANCELLED (`사용자 대기 취소`)

| 항목 | 내용 |
|---|---|
| Trigger | `POST /api/waitlists/{id}/cancel` |
| 선결 조건 | 호출자 = `waitlist.user_id` / 현재 `status == WAITING` |
| 부수 효과 | `UPDATE waitlists SET status='CANCELLED', cancelled_at=now(), updated_at=now() WHERE id=? AND status='WAITING'` (자동 승격과의 race 방어 가드 조건) / `occupied_count` 변화 없음 (대기열은 슬롯을 점유하지 않음) |
| 시간 기록 | `cancelled_at = now()`, `updated_at = now()` (UPDATE 문에 직접 명시) |
| 권한 | STUDENT (본인 대기) |
| 락 | 별도 락 불필요 (가드 조건 UPDATE만으로 race 차단) |
| 실패 매핑 | 403 `NOT_WAITLIST_OWNER` / 404 `WAITLIST_NOT_FOUND` / 409 `INVALID_STATUS` (가드 영향 0) |

> **가드 조건 UPDATE + `updated_at` 일관성 주의**
>
> 본 문서의 모든 가드 조건 UPDATE는 JPA dirty checking이 아니라 **native UPDATE 쿼리**로 상태를 변경한다. 따라서 JPA Auditing(`@LastModifiedDate`)이 자동 적용되지 않으므로 **`updated_at = now()`를 UPDATE 문에 명시**한다.

---

## 6. 도메인 간 상호작용

### 6.1 Enrollment 취소 → Waitlist 자동 승격

`Enrollment`의 `→ CANCELLED` 전이는 다음 조건에서 `Waitlist → PROMOTED` 전이와 신규 `Enrollment` 생성을 트리거한다.

```
[trigger]    Enrollment X: PENDING/CONFIRMED → CANCELLED
[조건]       course.status == OPEN
             course에 WAITING 대기자가 1명 이상

[동작]       1. occupied_count -= 1
             2. 가장 오래된 WAITING 후보 선택 (created_at ASC, id ASC)
             3. 후보에 대해 가드 조건 UPDATE → status='PROMOTED'
                - 성공: 4단계 진행
                - 실패(race): 다음 후보 시도. 모두 실패면 승격 없이 종료
             4. 새 Enrollment 생성 (status='PENDING', promoted_from_waitlist_id=후보.id)
             5. occupied_count += 1
```

#### 6.1.1 자동 승격 시 race가 발생하는 이유

동일 강의의 신청·취소·승격 흐름은 `courses` row lock으로 직렬화된다. 다만 **사용자 대기 취소(`Waitlist WAITING → CANCELLED`)는 `courses` row lock 없이 `waitlists` row에 대한 가드 조건 UPDATE만으로 처리**된다(대기열은 occupied_count에 영향을 주지 않으므로 course-level 직렬화가 불필요).

따라서 다음 race가 가능하다.

```
T1 (Enrollment 취소 → 자동 승격): courses 락 보유 → WAITING 후보 W를 선정 → W에 PROMOTED UPDATE 시도
T2 (W의 소유자가 대기 취소):       courses 락 없이 W에 CANCELLED UPDATE 시도
```

T2가 먼저 commit하면 T1의 `WHERE status='WAITING'` 가드가 0 행을 반환한다. 이 때 T1은 **다음 `WAITING` 후보를 재조회**해 승격을 재시도한다. 후보가 더 이상 없으면 승격 없이 종료한다.

이 fallback은 두 가지를 보장한다:
1. 동시 대기 취소가 있어도 자동 승격이 정확히 한 명에게 적용된다.
2. 빈 슬롯이 있음에도 승격이 누락되는 데드 상태가 발생하지 않는다.

#### 6.1.2 `occupied_count` 순변동 케이스

| 시나리오 | `occupied_count` 변동 |
|---|---|
| 취소 + 승격 가능한 대기자 존재 + 승격 성공 | `-1 + 1 = 0` (순변동 없음) |
| 취소 + 대기자 없음 | `-1` |
| 취소 + 대기자 있지만 모두 race로 실패 (이미 취소됨) | `-1` |
| 취소 + 강의 `CLOSED` (자동 승격 차단) | `-1` |

### 6.2 Course CLOSE → Enrollment cancel 동작 변경

`Course → CLOSED` 전이는 자체적으로 다른 도메인의 데이터를 변경하지 않는다. 다만 이후의 모든 `Enrollment` 취소 흐름에서 **6.1의 자동 승격 단계를 스킵**한다.

```
[CLOSED 이후 발생한 취소]
  - occupied_count -= 1   (수행)
  - Enrollment status 변경 (수행)
  - 자동 승격             (스킵)
```

### 6.3 Course 상태와 Enrollment 전이의 관계

> **정상 API 흐름에서는** `DRAFT` 상태 강의에 `PENDING`/`CONFIRMED` 신청이나 `WAITING` 대기가 존재할 수 없다(`OPEN`이 된 이후에만 신청·등록 가능). 시드 데이터나 테스트에서 직접 INSERT한 경우는 별개.

`Enrollment.PENDING → CONFIRMED`는 `course.status`와 무관하다. 즉 CLOSED 후에도 기존 `PENDING`은 결제 확정 가능. 이는 "이미 자리를 잡은 사용자가 결제를 못 하는 손해를 막기" 위함.

| Course status | PENDING → CONFIRMED | PENDING → CANCELLED | CONFIRMED → CANCELLED | 자동 승격 발동 |
|---|---|---|---|---|
| `DRAFT` | (정상 흐름에서 PENDING 미존재) | (동일) | (동일) | — |
| `OPEN` | ✅ | ✅ | ✅ (기간 내) | ✅ |
| `CLOSED` | ✅ | ✅ | ✅ (기간 내) | ✗ |

---

## 7. 시간 기반 정책

### 7.1 KST 기준 정책

본 과제는 한국 서비스 환경을 가정하므로 다음 시간대 정책을 사용한다.

- DB 저장: PostgreSQL `TIMESTAMPTZ` (내부적으로 UTC 정규화)
- DB 세션 타임존: `Asia/Seoul`
- Java 도메인 타입: `OffsetDateTime`
- 비즈니스 기준 시간대: `Asia/Seoul`
- API 응답 포맷: ISO 8601 KST, 예: `2026-05-20T23:30:00+09:00`

### 7.2 `now()`의 기준

본 문서에서 사용하는 `now()`는 **서버 애플리케이션이 주입받은 `Clock` 기준 현재 시각**이다. 테스트 시 `Clock` mock으로 임의 시각을 주입할 수 있다. 응답 포맷 변환은 KST(`Asia/Seoul`, `+09:00`) 기준으로 수행한다.

### 7.3 취소 가능 기간 계산

```
취소 가능 = (now() <= enrollment.confirmed_at + course.cancellation_deadline_days)
```

#### 계산 기준

- **결제 확정 시각 기준 N일 후 같은 시각까지** 허용 (날짜 단위 자르기가 아니라 인스턴트 비교)
- `OffsetDateTime` 또는 `Instant`의 동일 인스턴트 비교 (`<=`)
- `cancellation_deadline_days`는 `Course` 단위 컬럼이며, 본 전이 시점의 `course.cancellation_deadline_days`를 참조 (이후 강의 정보 수정 기능이 추가되어도 시점값 사용)

#### 예시

- `confirmed_at = 2026-05-20T10:00:00+09:00`
- `cancellation_deadline_days = 7`
- 취소 가능 마감 = `2026-05-27T10:00:00+09:00`
- `now() = 2026-05-27T10:00:00+09:00` → **허용** (`<=` 비교)
- `now() = 2026-05-27T10:00:01+09:00` → **거부** (`CANCEL_DEADLINE_EXCEEDED`)

### 7.4 시청 이력 기반 취소 제한은 범위 밖

본 과제에서는 수강 콘텐츠 시청 이력 도메인을 구현하지 않는다. 따라서 `CONFIRMED` 취소 가능 여부는 `confirmed_at + cancellation_deadline_days` 기준으로만 판단한다.

실제 서비스에서는 시청 시작 여부, 콘텐츠 소비율 등을 추가 조건으로 둘 수 있지만, 이는 별도의 학습 진도 도메인이 필요하므로 이번 범위에서는 명시적으로 제외한다.

---

## 8. 동시성과 상태 전이

각 전이가 어떤 동시성 보호 수단을 사용하는지 정리.

| 전이 | `courses` row 락 | `enrollments` row 락 | `waitlists` row 락 | 가드 조건 UPDATE | 비고 |
|---|---|---|---|---|---|
| Course `DRAFT → OPEN` | ✗ | ✗ | ✗ | ✅ `WHERE status='DRAFT'` | 단일 row, 락 불필요 |
| Course `OPEN → CLOSED` | ✗ | ✗ | ✗ | ✅ `WHERE status='OPEN'` | 단일 row, 락 불필요 |
| Enrollment direct creation (PENDING) | ✅ | — (INSERT) | ✗ | — | 정원 검증 + `occupied_count` 증감 |
| Enrollment promoted creation (PENDING) | ✅ (부모 트랜잭션) | — (INSERT) | ✗ | — | 동일 트랜잭션 |
| Enrollment `PENDING → CONFIRMED` | ✗ | ✗ | ✗ | ✅ `WHERE status='PENDING'` | atomic UPDATE로 cancel과 race 차단 |
| Enrollment `→ CANCELLED` | **✅** | **✅** | ✗ | ✗ | **course 락 → enrollment 락** 순서로 획득. 자동 승격을 위해 course 락 필요, confirm과의 race 차단을 위해 enrollment 락 필요 |
| Waitlist `[없음] → WAITING` | ✅ | ✗ | — (INSERT) | — | 정원 분기 판단 |
| Waitlist `WAITING → PROMOTED` | ✅ (부모 트랜잭션) | ✗ | ✗ | ✅ `WHERE status='WAITING'` | race 발생 시 다음 후보 fallback |
| Waitlist `WAITING → CANCELLED` | ✗ | ✗ | ✗ | ✅ `WHERE status='WAITING'` | 자동 승격과의 race 방어 |

### 8.1 락 획득 순서 (deadlock 회피)

여러 락을 잡는 전이는 다음 순서를 반드시 따른다.

```
courses row → enrollments row → (필요 시) waitlists row
```

이 순서를 모든 트랜잭션이 동일하게 따르면 deadlock은 발생하지 않는다. 두 트랜잭션이 서로 다른 단계의 락을 기다리는 cycle이 형성되지 않기 때문.

### 8.2 가드 조건 UPDATE 패턴의 일관성

본 시스템은 다음 패턴을 일관되게 사용한다.

- **단일 row 상태 변경 + 무락**: 가드 조건 UPDATE (`SET ... WHERE id=? AND status='<expected>'`). 영향 row가 0이면 race 발생으로 판단해 409.
- **다중 row 변경 + 비즈니스 검증**: `SELECT FOR UPDATE`로 락 획득 → 재조회로 상태 평가 → 단순 UPDATE. cancel 전이가 이 패턴(course 락 + enrollment 락).
- **자동 승격(WAITING → PROMOTED)**: 부모 트랜잭션의 course 락 안에서 waitlist에 대해 가드 조건 UPDATE. 영향 0이면 다음 후보로 fallback.

상세 흐름과 `SKIP LOCKED` 등 PostgreSQL 옵션 검토는 `docs/CONCURRENCY.md`에서 다룬다.

---

## 9. 시간 기록(audit timestamp) 일람

각 도메인이 어떤 시간 컬럼을 갖고, 어느 전이에서 기록되는가.

### Course

| 컬럼 | 기록 시점 |
|---|---|
| `created_at` | `[없음] → DRAFT` |
| `updated_at` | 모든 전이(`creation`, `open`, `close`) — UPDATE 문에 직접 명시 |

### Enrollment

| 컬럼 | 기록 시점 |
|---|---|
| `created_at` | `[없음] → PENDING` (direct or promoted) |
| `confirmed_at` | `PENDING → CONFIRMED` |
| `cancelled_at` | `→ CANCELLED` |
| `updated_at` | 모든 상태 변경 — INSERT/UPDATE 문에 직접 명시 |
| `promoted_from_waitlist_id` | promoted creation 시점 (이후 불변) |

### Waitlist

| 컬럼 | 기록 시점 |
|---|---|
| `created_at` | `[없음] → WAITING` |
| `promoted_at` | `WAITING → PROMOTED` |
| `cancelled_at` | `WAITING → CANCELLED` |
| `updated_at` | 모든 상태 변경 — INSERT/UPDATE 문에 직접 명시 |

---

## 10. 에러 매핑 일람

전이 위반 시 사용되는 도메인 에러 코드.

| code | 발생 위치 | HTTP |
|---|---|---|
| `INVALID_REQUEST` | 입력 검증 실패 (전이와 무관) | 400 |
| `UNAUTHENTICATED` | `X-USER-ID` 누락 / 존재하지 않는 사용자 | 401 |
| `FORBIDDEN` | role 불일치 | 403 |
| `NOT_COURSE_OWNER` | Course 전이 시 소유권 위반 | 403 |
| `NOT_ENROLLMENT_OWNER` | Enrollment 전이 시 소유권 위반 | 403 |
| `NOT_WAITLIST_OWNER` | Waitlist 전이 시 소유권 위반 | 403 |
| `COURSE_NOT_FOUND` | 대상 Course 없음 | 404 |
| `ENROLLMENT_NOT_FOUND` | 대상 Enrollment 없음 | 404 |
| `WAITLIST_NOT_FOUND` | 대상 Waitlist 없음 | 404 |
| `INVALID_TRANSITION` | Course 상태 전이 가드 영향 0 (현재 상태 불일치) | 409 |
| `COURSE_NOT_OPEN` | 수강 신청/대기 등록 시 `course.status != OPEN` | 409 |
| `DUPLICATE_ENROLLMENT` | 동일 사용자/강의 활성 신청 중복 | 409 |
| `DUPLICATE_WAITLIST` | 동일 사용자/강의 활성 대기 중복 | 409 |
| `INVALID_STATUS` | 결제 확정/대기 취소 가드 영향 0, 또는 취소 시 락 후 재조회 결과 비허용 상태 | 409 |
| `ALREADY_CANCELLED` | 수강 취소 시 락 후 재조회 결과 이미 `CANCELLED` | 409 |
| `CANCEL_DEADLINE_EXCEEDED` | `CONFIRMED` 취소 시 기간 초과 | 409 |

상세 카탈로그(메시지 템플릿, 발생 시나리오 등)는 `docs/ERROR_CODES.md`에서 정리한다.

---

## 11. 구현 체크리스트

본 문서를 코드에 옮길 때 다음 항목을 확인한다.

- [ ] 각 도메인 엔티티에 `protected setStatus()` 캡슐화. public 상태 변경 메서드는 `open()`, `close()`, `confirm()`, `cancel()`, `promote()` 등 의도가 명확한 형태로만 노출.
- [ ] 상태 변경 메서드 안에서 선결 조건을 검증 (e.g., `open()`은 `status == DRAFT`만 허용, 아니면 도메인 예외).
- [ ] 서비스 레이어에서 가드 조건 UPDATE를 사용해 race를 차단 — 영향 받은 row가 0이면 도메인 예외 → `409` 매핑.
- [ ] `Enrollment` 취소 흐름은 **`courses` row 락 → `enrollments` row 락 → occupied_count 감소 → (OPEN이면) 자동 승격**을 한 트랜잭션 안에 묶기.
- [ ] 자동 승격 시 가드 영향 0이면 다음 후보를 ORDER BY로 다시 조회. 후보가 더 없으면 승격 없이 종료.
- [ ] `cancellation_deadline_days` 비교는 `Instant`/`OffsetDateTime`의 동일 인스턴트 비교 (`<=`).
- [ ] `Course.status == CLOSED`라도 결제 확정·취소는 허용, **자동 승격만 차단**.
- [ ] **직접 UPDATE 쿼리를 사용하는 전이는 `updated_at = now()`를 쿼리에 포함**한다 (JPA Auditing이 자동 적용되지 않음).
- [ ] **가드 조건 UPDATE 영향 row가 0인 경우, 필요하면 현재 상태를 재조회해 `ALREADY_CANCELLED`와 `INVALID_STATUS`를 구분**한다.
- [ ] 락 획득 순서는 항상 `courses → enrollments → waitlists`. 모든 트랜잭션이 동일 순서를 따라 deadlock 회피.
- [ ] `now()`는 서비스 빈에 주입된 `Clock`을 사용. 테스트에서 시각 mock 가능.
