package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.user.UserRole;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import com.example.enrollment_system.waitlist.WaitlistService;
import com.example.enrollment_system.waitlist.WaitlistStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CC-3: 자동 승격 ↔ 대기 취소 race.
 *
 * Setup: capacity=1 강의, A=ENROLLED, B=WAITLIST(1번), C=WAITLIST(2번).
 * Concurrent: A.cancel(자동 승격 발동) ↔ B.waitlistCancel
 *
 * 두 케이스 어느 쪽이든 정합성 유지 검증:
 *  - Case 1 (A의 PROMOTED UPDATE가 먼저): B=PROMOTED, C=WAITING
 *  - Case 2 (B의 CANCELLED UPDATE가 먼저): B=CANCELLED, C=PROMOTED (fallback)
 *
 * 불변식:
 *  - occupiedCount == 1
 *  - A enrollment == CANCELLED
 *  - 새 PENDING enrollment with promoted_from_waitlist_id 정확히 1개
 *  - 어떤 partial unique index 위반도 없음
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PromotionRaceConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired EnrollmentService enrollmentService;
    @Autowired WaitlistService waitlistService;
    @Autowired CourseService courseService;
    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentRepository enrollmentRepository;
    @Autowired WaitlistRepository waitlistRepository;
    @Autowired JdbcTemplate jdbc;

    private final AuthUser creator = new AuthUser(1L, UserRole.CREATOR);
    private final AuthUser studentA = new AuthUser(10L, UserRole.STUDENT);
    private final AuthUser studentB = new AuthUser(11L, UserRole.STUDENT);
    private final AuthUser studentC = new AuthUser(12L, UserRole.STUDENT);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    @Test
    void CC_3_자동_승격_대기_취소_race_concurrency() throws Exception {
        // given
        Long courseId = openCourse(1);
        Long aEnrollmentId = enrollmentService.apply(studentA, courseId).enrollment().id();
        Long bWaitlistId = enrollmentService.apply(studentB, courseId).waitlist().id();
        Long cWaitlistId = enrollmentService.apply(studentC, courseId).waitlist().id();

        // when — A cancel ↔ B waitlist cancel 동시 발사
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            try {
                start.await();
                enrollmentService.cancel(studentA, aEnrollmentId);
            } catch (Exception ignored) {
                // A의 cancel은 어떤 race든 성공해야 정상
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                waitlistService.cancel(studentB, bWaitlistId);
            } catch (Exception ignored) {
                // B의 cancel은 A가 먼저 PROMOTED 했으면 INVALID_STATUS로 실패 가능 — 정상
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("30초 안에 완료").isTrue();
        pool.shutdown();

        // then — 불변식 검증
        Course refreshed = courseRepository.findById(courseId).orElseThrow();
        assertThat(refreshed.getOccupiedCount())
            .as("자동 승격으로 occupiedCount 1 유지")
            .isEqualTo(1);

        Enrollment aE = enrollmentRepository.findById(aEnrollmentId).orElseThrow();
        assertThat(aE.getStatus())
            .as("A enrollment은 CANCELLED")
            .isEqualTo(EnrollmentStatus.CANCELLED);

        Long promotedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM enrollments WHERE promoted_from_waitlist_id IS NOT NULL",
            Long.class);
        assertThat(promotedCount)
            .as("새 PENDING enrollment with promoted_from_waitlist_id 정확히 1개")
            .isEqualTo(1L);

        Waitlist bW = waitlistRepository.findById(bWaitlistId).orElseThrow();
        Waitlist cW = waitlistRepository.findById(cWaitlistId).orElseThrow();

        boolean case1 = bW.getStatus() == WaitlistStatus.PROMOTED
                     && cW.getStatus() == WaitlistStatus.WAITING;
        boolean case2 = bW.getStatus() == WaitlistStatus.CANCELLED
                     && cW.getStatus() == WaitlistStatus.PROMOTED;

        assertThat(case1 || case2)
            .as("Case 1: B PROMOTED + C WAITING / Case 2: B CANCELLED + C PROMOTED (fallback)")
            .isTrue();
    }

    private Long openCourse(int capacity) {
        Course course = courseService.register(creator, new CourseCreateRequest(
            "Java 마스터 클래스",
            "Spring Boot 실전",
            150_000L,
            capacity,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            7
        ));
        return courseService.open(creator, course.getId()).getId();
    }
}
