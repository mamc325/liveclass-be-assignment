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

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LastSeatConcurrencyTest {

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

    /**
     * CC-1: 마지막 자리 50명 동시 경합.
     *
     * capacity=10인 강의에 학생 50명이 동시에 apply 호출.
     * 기대:
     *   - ENROLLED 정확히 10명
     *   - WAITLISTED 정확히 40명
     *   - course.occupiedCount == 10 (capacity 초과 X)
     *   - 활성 enrollment 수 == 10 (DB 검증)
     *   - WAITING waitlist 수 == 40 (DB 검증)
     *
     * BE-A 평가 핵심 시나리오 (TEST_SCENARIOS 9.1 CC-1, CONCURRENCY.md 7.1).
     */
    @Test
    void 동시_50명_capacity_10_경합_concurrency() throws Exception {
        // given
        Long courseId = openCourse(10);
        List<AuthUser> participants = createParticipants(50);

        // when
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(50);
        AtomicInteger enrolledCount = new AtomicInteger();
        AtomicInteger waitlistedCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (AuthUser participant : participants) {
            pool.submit(() -> {
                try {
                    start.await();
                    EnrollmentApplyResponse res = enrollmentService.apply(participant, courseId);
                    if (res.outcome() == EnrollmentApplyResponse.Outcome.ENROLLED) {
                        enrolledCount.incrementAndGet();
                    } else {
                        waitlistedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown(); // 50개 스레드 동시 발사
        boolean allDone = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        // then
        assertThat(allDone).as("60초 안에 모든 신청 처리 완료").isTrue();
        assertThat(errorCount.get()).as("예상치 못한 예외 없음").isZero();
        assertThat(enrolledCount.get()).as("ENROLLED 정확히 10명").isEqualTo(10);
        assertThat(waitlistedCount.get()).as("WAITLISTED 정확히 40명").isEqualTo(40);

        Course refreshed = courseRepository.findById(courseId).orElseThrow();
        assertThat(refreshed.getOccupiedCount())
            .as("occupiedCount는 정원 10을 초과하지 않음")
            .isEqualTo(10);

        long activeEnrollments = enrollmentRepository.countActiveByCourseId(courseId);
        assertThat(activeEnrollments)
            .as("DB 활성 enrollment 수가 occupiedCount와 일치")
            .isEqualTo(10);

        long waitingWaitlists = waitlistRepository
            .findByCourseIdAndStatus(courseId, WaitlistStatus.WAITING, PageRequest.of(0, 100))
            .getTotalElements();
        assertThat(waitingWaitlists)
            .as("DB WAITING 대기열 수가 40과 일치")
            .isEqualTo(40);
    }

    // ----- Helpers -----

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

    /**
     * 시드 사용자(id 10~19) 10명 + 동적 생성(id >= 101) (count - 10)명을 구성한다.
     * 동적 생성 사용자는 cleanup에서 자동 삭제 (DELETE WHERE id > 100).
     */
    private List<AuthUser> createParticipants(int count) {
        List<AuthUser> users = new ArrayList<>(count);
        for (long id = 10; id <= 19 && users.size() < count; id++) {
            users.add(new AuthUser(id, UserRole.STUDENT));
        }
        while (users.size() < count) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User newUser = userRepository.save(
                new User("student_" + suffix, "s_" + suffix + "@test.com", UserRole.STUDENT));
            users.add(new AuthUser(newUser.getId(), UserRole.STUDENT));
        }
        return users;
    }
}
