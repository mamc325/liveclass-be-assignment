package com.example.enrollment_system.repository;

import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.enrollment.Enrollment;
import com.example.enrollment_system.enrollment.EnrollmentRepository;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PartialUniqueIndexAndSortingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentRepository enrollmentRepository;
    @Autowired WaitlistRepository waitlistRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        // TRUNCATE CASCADE로 3 테이블을 원자적으로 비움 — JdbcTemplate ↔ JPA 트랜잭션 가시성 우회.
        // users는 시드 보존 (id <= 19) — 동적 생성 사용자만 삭제.
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    // -----------------------------------------------------------------
    // Partial Unique Index
    // -----------------------------------------------------------------

    // R-U-1: 동일 (user, course) PENDING 2건 → uq_active_enrollment_per_user_course
    @Test
    void 동일_사용자_강의에_활성_PENDING_중복_INSERT면_violation() {
        Course course = courseRepository.save(newCourse(1L));
        Enrollment first = Enrollment.directApply(course.getId(), 10L);
        enrollmentRepository.saveAndFlush(first);

        Enrollment second = Enrollment.directApply(course.getId(), 10L);

        assertThatThrownBy(() -> enrollmentRepository.saveAndFlush(second))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // R-U-2: PENDING + CANCELLED 동시 존재 → 허용 (partial unique)
    @Test
    void 동일_사용자_강의에_CANCELLED와_PENDING_공존_허용() {
        Course course = courseRepository.save(newCourse(1L));

        Enrollment cancelled = Enrollment.directApply(course.getId(), 10L);
        cancelled.cancel(now(), 7);
        enrollmentRepository.saveAndFlush(cancelled);

        Enrollment newPending = Enrollment.directApply(course.getId(), 10L);
        enrollmentRepository.saveAndFlush(newPending);

        assertThat(enrollmentRepository.count()).isEqualTo(2);
    }

    // R-U-3: 동일 (user, course) WAITING 2건 → uq_waiting_waitlist_per_user_course
    @Test
    void 동일_사용자_강의에_WAITING_중복_INSERT면_violation() {
        Course course = courseRepository.save(newCourse(1L));
        waitlistRepository.saveAndFlush(new Waitlist(course.getId(), 10L));

        Waitlist second = new Waitlist(course.getId(), 10L);

        assertThatThrownBy(() -> waitlistRepository.saveAndFlush(second))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // R-U-4: 동일 promoted_from_waitlist_id 2건 → uq_enrollments_promoted_from_waitlist
    @Test
    void 동일_promoted_from_waitlist_id_중복_INSERT면_violation() {
        Course course = courseRepository.save(newCourse(1L));
        Waitlist waitlist = waitlistRepository.saveAndFlush(new Waitlist(course.getId(), 10L));

        enrollmentRepository.saveAndFlush(
            Enrollment.promotedFrom(course.getId(), 10L, waitlist.getId()));

        Enrollment dup = Enrollment.promotedFrom(course.getId(), 11L, waitlist.getId());

        assertThatThrownBy(() -> enrollmentRepository.saveAndFlush(dup))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // R-U-5: promoted_from_waitlist_id = NULL 다수 → 허용 (partial unique)
    @Test
    void promoted_from_waitlist_id가_NULL이면_다수_허용() {
        Course course = courseRepository.save(newCourse(1L));

        enrollmentRepository.saveAndFlush(Enrollment.directApply(course.getId(), 10L));
        enrollmentRepository.saveAndFlush(Enrollment.directApply(course.getId(), 11L));
        enrollmentRepository.saveAndFlush(Enrollment.directApply(course.getId(), 12L));

        assertThat(enrollmentRepository.count()).isEqualTo(3);
    }

    // -----------------------------------------------------------------
    // 정렬 정책
    // -----------------------------------------------------------------

    // R-S-1: enrollments created_at DESC, id DESC 페이지네이션 안정성
    @Test
    void enrollment_목록은_created_at_DESC_id_DESC로_페이지네이션된다() {
        Course course = courseRepository.save(newCourse(1L));

        // 동일 사용자 중복 차단되므로 사용자 ID를 다르게
        Long e1 = enrollmentRepository.saveAndFlush(Enrollment.directApply(course.getId(), 10L)).getId();
        Long e2 = enrollmentRepository.saveAndFlush(Enrollment.directApply(course.getId(), 11L)).getId();
        Long e3 = enrollmentRepository.saveAndFlush(Enrollment.directApply(course.getId(), 12L)).getId();

        var page = enrollmentRepository.findByCourseId(
            course.getId(),
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        );

        // 가장 최근 생성된 e3가 가장 앞에 와야 함
        assertThat(page.getContent()).extracting(Enrollment::getId)
            .containsExactly(e3, e2, e1);
    }

    // R-S-2: waitlists created_at ASC, id ASC FIFO
    @Test
    void waitlist_목록은_created_at_ASC_id_ASC로_FIFO_정렬된다() {
        Course course = courseRepository.save(newCourse(1L));

        Long w1 = waitlistRepository.saveAndFlush(new Waitlist(course.getId(), 10L)).getId();
        Long w2 = waitlistRepository.saveAndFlush(new Waitlist(course.getId(), 11L)).getId();
        Long w3 = waitlistRepository.saveAndFlush(new Waitlist(course.getId(), 12L)).getId();

        var page = waitlistRepository.findByCourseId(
            course.getId(),
            PageRequest.of(0, 10, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
        );

        // 먼저 등록된 w1이 가장 앞 (FIFO)
        assertThat(page.getContent()).extracting(Waitlist::getId)
            .containsExactly(w1, w2, w3);
    }

    // 자동 승격 후보 조회 (Pageable 활용 확인)
    @Test
    void findOldestWaitingByCourseId는_가장_오래된_WAITING을_먼저_반환한다() {
        Course course = courseRepository.save(newCourse(1L));

        Long w1 = waitlistRepository.saveAndFlush(new Waitlist(course.getId(), 10L)).getId();
        Long w2 = waitlistRepository.saveAndFlush(new Waitlist(course.getId(), 11L)).getId();

        List<Waitlist> candidates = waitlistRepository.findOldestWaitingByCourseId(
            course.getId(), PageRequest.of(0, 5));

        assertThat(candidates).extracting(Waitlist::getId).containsExactly(w1, w2);
    }

    // -----------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------

    private Course newCourse(Long creatorId) {
        return new Course(
            creatorId,
            "Java 마스터 클래스",
            "Spring Boot 실전",
            150_000L,
            10,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            7
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
