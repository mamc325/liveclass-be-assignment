-- =====================================================================
-- V2: 시드 사용자
-- 평가/테스트 편의용 고정 ID 사용자.
--   - id 1, 2:    CREATOR
--   - id 10~19:   STUDENT
--
-- 본 시드는 테스트 cleanup에서 삭제하지 않는다.
-- 동적 생성 사용자(예: 동시성 테스트 50명)는 id >= 101부터 받게 시퀀스를 bump.
-- (TEST_SCENARIOS.md 11.1, 13.3 참조)
-- =====================================================================

INSERT INTO users (id, name, email, role, created_at, updated_at) VALUES
    (1,  'creator1',  'creator1@test.com',  'CREATOR', now(), now()),
    (2,  'creator2',  'creator2@test.com',  'CREATOR', now(), now()),
    (10, 'student01', 'student01@test.com', 'STUDENT', now(), now()),
    (11, 'student02', 'student02@test.com', 'STUDENT', now(), now()),
    (12, 'student03', 'student03@test.com', 'STUDENT', now(), now()),
    (13, 'student04', 'student04@test.com', 'STUDENT', now(), now()),
    (14, 'student05', 'student05@test.com', 'STUDENT', now(), now()),
    (15, 'student06', 'student06@test.com', 'STUDENT', now(), now()),
    (16, 'student07', 'student07@test.com', 'STUDENT', now(), now()),
    (17, 'student08', 'student08@test.com', 'STUDENT', now(), now()),
    (18, 'student09', 'student09@test.com', 'STUDENT', now(), now()),
    (19, 'student10', 'student10@test.com', 'STUDENT', now(), now());

-- 시퀀스를 100으로 설정 → 다음 nextval()부터 id = 101을 부여
SELECT setval(pg_get_serial_sequence('users', 'id'), 100);
