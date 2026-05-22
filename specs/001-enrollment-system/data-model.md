# 데이터 모델: 수강 신청 시스템

이 문서는 Spec Kit 진입점이다. 상세한 단일 진실 원천은 `docs/ERD.md`이며, 실제 실행 스키마는 `src/main/resources/db/migration/V1__init_schema.sql`이다.

## 엔티티

### User

- `id`
- `name`
- `email`
- `role`: `CREATOR` 또는 `STUDENT`
- `createdAt`
- `updatedAt`

목적: 과제용 시드 기반 호출자 식별과 역할 조회.

### Course

- `id`
- `creatorId`
- `title`
- `description`
- `price`
- `capacity`
- `occupiedCount`
- `startDate`
- `endDate`
- `status`: `DRAFT`, `OPEN`, `CLOSED`
- `cancellationDeadlineDays`
- `createdAt`
- `updatedAt`

규칙:

- `occupiedCount`는 항상 `0` 이상 `capacity` 이하를 유지해야 한다.
- `PENDING`과 `CONFIRMED` 신청은 정원을 점유한다.
- 본인 소유 강의만 모집 시작/마감하거나 수강생/대기자 목록을 조회할 수 있다.

### Enrollment

- `id`
- `courseId`
- `userId`
- `promotedFromWaitlistId`
- `status`: `PENDING`, `CONFIRMED`, `CANCELLED`
- `confirmedAt`
- `cancelledAt`
- `createdAt`
- `updatedAt`

규칙:

- 한 사용자는 한 강의에 하나의 활성 신청만 가질 수 있다.
- `PENDING`은 `CONFIRMED` 또는 `CANCELLED`가 될 수 있다.
- `CONFIRMED`는 강의 취소 가능 기간 안에서만 `CANCELLED`가 될 수 있다.
- `CANCELLED`는 종단 상태다.

### Waitlist

- `id`
- `courseId`
- `userId`
- `status`: `WAITING`, `PROMOTED`, `CANCELLED`
- `promotedAt`
- `cancelledAt`
- `createdAt`
- `updatedAt`

규칙:

- 한 사용자는 한 강의에 하나의 `WAITING` 대기만 가질 수 있다.
- `WAITING`은 `PROMOTED` 또는 `CANCELLED`가 될 수 있다.
- 승격 순서는 `createdAt ASC, id ASC`다.

## 데이터베이스 제약

- `uq_active_enrollment_per_user_course`: 사용자/강의별 활성 신청 1개만 허용.
- `uq_waiting_waitlist_per_user_course`: 사용자/강의별 대기 중 row 1개만 허용.
- `uq_enrollments_promoted_from_waitlist`: 대기 row 하나는 최대 하나의 신청만 생성 가능.
- check constraint로 상태 값과 음수가 될 수 없는 숫자 필드를 검증.
