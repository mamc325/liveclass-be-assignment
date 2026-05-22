-- =====================================================================
-- V3: unique partial index 컬럼 순서 재정렬
-- (user_id, course_id) → (course_id, user_id)
--
-- 동기:
--   동시 신청 흐름은 항상 courses row를 먼저 PESSIMISTIC_WRITE로 잠근 뒤
--   같은 course_id에 대한 EXISTS 검증을 수행한다. course_id를 인덱스의
--   선두 컬럼으로 두면 같은 강의 데이터가 인덱스 페이지에 인접 배치되어
--   buffer locality가 좋아지고, EXISTS 양쪽 = 조건 쿼리는 그대로 동작한다.
--   (CC-1: capacity=10 강의에 50명 동시 신청 같은 시연 시나리오 의도와 일치)
-- =====================================================================

DROP INDEX IF EXISTS uq_active_enrollment_per_user_course;
DROP INDEX IF EXISTS uq_waiting_waitlist_per_user_course;

CREATE UNIQUE INDEX uq_active_enrollment_per_user_course
    ON enrollments (course_id, user_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

CREATE UNIQUE INDEX uq_waiting_waitlist_per_user_course
    ON waitlists (course_id, user_id)
    WHERE status = 'WAITING';
