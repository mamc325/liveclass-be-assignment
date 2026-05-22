package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.DomainException;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CancelRaceConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired EnrollmentService enrollmentService;
    @Autowired CourseService courseService;
    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentRepository enrollmentRepository;
    @Autowired WaitlistRepository waitlistRepository;
    @Autowired JdbcTemplate jdbc;

    private final AuthUser creator = new AuthUser(1L, UserRole.CREATOR);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    /**
     * CC-5: 동일 enrollment에 동시 cancel 시도.
     *
     * 사용자가 빠르게 두 번 이상 cancel을 클릭한 시나리오.
     * 기대: 정확히 1건만 성공, 나머지는 ALREADY_CANCELLED.
     * occupiedCount는 정확히 한 번만 감소.
     */
    @Test
    void CC_5_동일_enrollment_동시_cancel_concurrency() throws Exception {
        // given
        Long courseId = openCourse(5);
        AuthUser student = new AuthUser(10L, UserRole.STUDENT);
        Long enrollmentId = enrollmentService.apply(student, courseId).enrollment().id();

        // when — 5개 스레드가 동일 enrollment에 동시 cancel
        int attempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger alreadyCancelledCount = new AtomicInteger();
        AtomicInteger otherFailureCount = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    enrollmentService.cancel(student, enrollmentId);
                    successCount.incrementAndGet();
                } catch (DomainException e) {
                    if (e.errorCode() == ErrorCode.ALREADY_CANCELLED) {
                        alreadyCancelledCount.incrementAndGet();
                    } else {
                        otherFailureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherFailureCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("30초 안에 완료").isTrue();
        pool.shutdown();

        // then
        assertThat(successCount.get()).as("정확히 1건만 성공").isEqualTo(1);
        assertThat(alreadyCancelledCount.get())
            .as("나머지는 ALREADY_CANCELLED")
            .isEqualTo(attempts - 1);
        assertThat(otherFailureCount.get()).as("예상 외 실패 없음").isZero();

        Enrollment e = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);

        Course refreshed = courseRepository.findById(courseId).orElseThrow();
        assertThat(refreshed.getOccupiedCount())
            .as("occupiedCount 한 번만 감소 (5 → 정원이 5 capacity면 0이 되면 안 됨, 1 enrollment만 cancelled 됐으니 0)")
            .isEqualTo(0);
    }

    /**
     * CC-6: CLOSED 강의에서 동시 cancel.
     *
     * capacity=10, 10명 enroll → close → 10명 동시 cancel.
     * 기대: 모두 성공, occupiedCount=0, 자동 승격 발동 없음(CLOSED라).
     */
    @Test
    void CC_6_CLOSED_강의_동시_cancel_자동_승격_없음_concurrency() throws Exception {
        // given
        Long courseId = openCourse(10);
        List<AuthUser> students = new ArrayList<>(10);
        List<Long> enrollmentIds = new ArrayList<>(10);
        for (long id = 10; id <= 19; id++) {
            AuthUser s = new AuthUser(id, UserRole.STUDENT);
            students.add(s);
            EnrollmentApplyResponse res = enrollmentService.apply(s, courseId);
            enrollmentIds.add(res.enrollment().id());
        }

        // 강의 close
        courseService.close(creator, courseId);

        // when — 10명 동시 cancel
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    enrollmentService.cancel(students.get(idx), enrollmentIds.get(idx));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("30초 안에 완료").isTrue();
        pool.shutdown();

        // then
        assertThat(successCount.get()).as("모두 성공").isEqualTo(10);
        assertThat(errorCount.get()).as("예상 외 실패 없음").isZero();

        Course refreshed = courseRepository.findById(courseId).orElseThrow();
        assertThat(refreshed.getOccupiedCount())
            .as("occupiedCount 0까지 감소")
            .isZero();

        // CLOSED라 자동 승격 발동 안 됨 → promoted_from_waitlist_id가 set된 enrollment 0개
        Long promotedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM enrollments WHERE promoted_from_waitlist_id IS NOT NULL",
            Long.class);
        assertThat(promotedCount).as("CLOSED 강의이므로 promoted enrollment 0").isZero();

        // 대기열도 없음 (애초에 등록 안 했으니)
        long waitingCount = waitlistRepository
            .findByCourseIdAndStatus(courseId, WaitlistStatus.WAITING, PageRequest.of(0, 100))
            .getTotalElements();
        assertThat(waitingCount).isZero();
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
