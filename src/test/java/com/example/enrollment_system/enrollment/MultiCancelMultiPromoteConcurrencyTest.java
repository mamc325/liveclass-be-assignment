package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
import com.example.enrollment_system.user.User;
import com.example.enrollment_system.user.UserRepository;
import com.example.enrollment_system.user.UserRole;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import com.example.enrollment_system.waitlist.WaitlistStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CC-7: 동시 다중 cancel + 다중 자동 승격.
 *
 * capacity=5, 5명 enroll, 10명 waitlist. 5명 동시 cancel.
 * 기대:
 *  - 5명 원래 enrollment CANCELLED
 *  - 가장 오래된 5명의 waitlist PROMOTED, 새 PENDING enrollment 5개 생성
 *  - 나머지 5명은 여전히 WAITING
 *  - occupiedCount=5 유지 (각 cancel당 -1 +1 순변동 0)
 *  - 이중 승격 없음 (uq_enrollments_promoted_from_waitlist 위반 없음)
 *
 * 같은 강의의 course row lock으로 5개 cancel이 순차 직렬화되어 정합성 유지.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MultiCancelMultiPromoteConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired EnrollmentService enrollmentService;
    @Autowired CourseService courseService;
    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentRepository enrollmentRepository;
    @Autowired WaitlistRepository waitlistRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private final AuthUser creator = new AuthUser(1L, UserRole.CREATOR);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    @Test
    void CC_7_동시_다중_cancel_다중_자동_승격_concurrency() throws Exception {
        // given
        int capacity = 5;
        int waitingCount = 10;
        Long courseId = openCourse(capacity);

        // 5명 enroll
        List<AuthUser> enrolledUsers = createParticipants(capacity);
        List<Long> enrollmentIds = new ArrayList<>(capacity);
        for (AuthUser u : enrolledUsers) {
            enrollmentIds.add(enrollmentService.apply(u, courseId).enrollment().id());
        }

        // 10명 waitlist (별도 사용자)
        List<AuthUser> waitingUsers = createParticipants(waitingCount);
        for (AuthUser u : waitingUsers) {
            EnrollmentApplyResponse r = enrollmentService.apply(u, courseId);
            assertThat(r.outcome()).isEqualTo(EnrollmentApplyResponse.Outcome.WAITLISTED);
        }

        // when — 5명 동시 cancel
        ExecutorService pool = Executors.newFixedThreadPool(capacity);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(capacity);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (int i = 0; i < capacity; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    enrollmentService.cancel(enrolledUsers.get(idx), enrollmentIds.get(idx));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("60초 안에 완료").isTrue();
        pool.shutdown();

        // then
        assertThat(successCount.get()).as("5명 cancel 모두 성공").isEqualTo(capacity);
        assertThat(errorCount.get()).as("예외 없음").isZero();

        Course refreshed = courseRepository.findById(courseId).orElseThrow();
        assertThat(refreshed.getOccupiedCount())
            .as("자동 승격으로 occupiedCount=5 유지")
            .isEqualTo(capacity);

        // 가장 오래된 5명이 PROMOTED, 나머지 5명은 여전히 WAITING
        long promotedWaitlists = waitlistRepository
            .findByCourseIdAndStatus(courseId, WaitlistStatus.PROMOTED, PageRequest.of(0, 100))
            .getTotalElements();
        long stillWaiting = waitlistRepository
            .findByCourseIdAndStatus(courseId, WaitlistStatus.WAITING, PageRequest.of(0, 100))
            .getTotalElements();
        assertThat(promotedWaitlists).as("PROMOTED 정확히 5개").isEqualTo(capacity);
        assertThat(stillWaiting).as("WAITING 5개 잔존").isEqualTo(waitingCount - capacity);

        // 새 PENDING enrollment (promoted_from_waitlist_id) 정확히 5개, 이중 승격 없음
        Long promotedEnrollments = jdbc.queryForObject(
            "SELECT COUNT(*) FROM enrollments WHERE promoted_from_waitlist_id IS NOT NULL",
            Long.class);
        assertThat(promotedEnrollments)
            .as("promoted_from_waitlist_id set된 enrollment 정확히 5개 (uq 위반 없음)")
            .isEqualTo((long) capacity);

        long activeEnrollments = enrollmentRepository.countActiveByCourseId(courseId);
        assertThat(activeEnrollments)
            .as("활성 enrollment(PENDING/CONFIRMED) 정확히 5 — occupiedCount와 일치")
            .isEqualTo(capacity);
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

    private List<AuthUser> createParticipants(int count) {
        List<AuthUser> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User newUser = userRepository.save(
                new User("student_" + suffix, "s_" + suffix + "@test.com", UserRole.STUDENT));
            users.add(new AuthUser(newUser.getId(), UserRole.STUDENT));
        }
        return users;
    }
}
