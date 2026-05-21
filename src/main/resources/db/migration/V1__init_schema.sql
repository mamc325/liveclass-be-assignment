-- =====================================================================
-- V1: 초기 스키마
-- 생성 순서: users → courses → waitlists → enrollments
-- (enrollments.promoted_from_waitlist_id → waitlists.id FK 때문)
-- =====================================================================

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    email       VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_users_role CHECK (role IN ('CREATOR', 'STUDENT'))
);

CREATE UNIQUE INDEX uq_users_email ON users (email);

-- ---------------------------------------------------------------------
-- courses
-- ---------------------------------------------------------------------
CREATE TABLE courses (
    id                          BIGSERIAL PRIMARY KEY,
    creator_id                  BIGINT       NOT NULL,
    title                       VARCHAR(100) NOT NULL,
    description                 TEXT,
    price                       BIGINT       NOT NULL,
    capacity                    INTEGER      NOT NULL,
    occupied_count              INTEGER      NOT NULL DEFAULT 0,
    start_date                  DATE         NOT NULL,
    end_date                    DATE         NOT NULL,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    cancellation_deadline_days  INTEGER      NOT NULL DEFAULT 7,
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_courses_creator        FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_courses_price         CHECK (price >= 0),
    CONSTRAINT chk_courses_capacity      CHECK (capacity > 0),
    CONSTRAINT chk_courses_occupied      CHECK (occupied_count >= 0),
    CONSTRAINT chk_courses_dates         CHECK (start_date <= end_date),
    CONSTRAINT chk_courses_status        CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    CONSTRAINT chk_courses_deadline_days CHECK (cancellation_deadline_days >= 0)
);

CREATE INDEX idx_courses_status_created
    ON courses (status, created_at DESC, id DESC);

CREATE INDEX idx_courses_creator_id
    ON courses (creator_id);

-- ---------------------------------------------------------------------
-- waitlists (enrollments보다 먼저 생성)
-- ---------------------------------------------------------------------
CREATE TABLE waitlists (
    id            BIGSERIAL PRIMARY KEY,
    course_id     BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    promoted_at   TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_waitlists_course  FOREIGN KEY (course_id) REFERENCES courses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlists_user    FOREIGN KEY (user_id)   REFERENCES users (id)   ON DELETE RESTRICT,
    CONSTRAINT chk_waitlists_status CHECK (status IN ('WAITING', 'PROMOTED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_waiting_waitlist_per_user_course
    ON waitlists (user_id, course_id)
    WHERE status = 'WAITING';

CREATE INDEX idx_waitlists_course_status_created
    ON waitlists (course_id, status, created_at ASC, id ASC);

-- ---------------------------------------------------------------------
-- enrollments
-- ---------------------------------------------------------------------
CREATE TABLE enrollments (
    id                         BIGSERIAL PRIMARY KEY,
    course_id                  BIGINT      NOT NULL,
    user_id                    BIGINT      NOT NULL,
    promoted_from_waitlist_id  BIGINT,
    status                     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    confirmed_at               TIMESTAMPTZ,
    cancelled_at               TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_enrollments_course        FOREIGN KEY (course_id) REFERENCES courses (id)  ON DELETE RESTRICT,
    CONSTRAINT fk_enrollments_user          FOREIGN KEY (user_id)   REFERENCES users (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_enrollments_promoted_from FOREIGN KEY (promoted_from_waitlist_id) REFERENCES waitlists (id) ON DELETE RESTRICT,
    CONSTRAINT chk_enrollments_status       CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_active_enrollment_per_user_course
    ON enrollments (user_id, course_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

CREATE UNIQUE INDEX uq_enrollments_promoted_from_waitlist
    ON enrollments (promoted_from_waitlist_id)
    WHERE promoted_from_waitlist_id IS NOT NULL;

CREATE INDEX idx_enrollments_user_created
    ON enrollments (user_id, created_at DESC, id DESC);

CREATE INDEX idx_enrollments_course_status_created
    ON enrollments (course_id, status, created_at DESC, id DESC);
