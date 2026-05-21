package com.example.enrollment_system.waitlist;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.DomainException;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.EnrollmentService;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
import com.example.enrollment_system.user.UserRole;
import com.example.enrollment_system.waitlist.dto.WaitlistCancelResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class WaitlistServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired WaitlistService waitlistService;
    @Autowired EnrollmentService enrollmentService;
    @Autowired CourseService courseService;
    @Autowired WaitlistRepository waitlistRepository;
    @Autowired JdbcTemplate jdbc;

    private final AuthUser creator = new AuthUser(1L, UserRole.CREATOR);
    private final AuthUser student10 = new AuthUser(10L, UserRole.STUDENT);
    private final AuthUser student11 = new AuthUser(11L, UserRole.STUDENT);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    // S-W-1: WAITING 상태에서 cancel → CANCELLED
    @Test
    void cancel은_WAITING_상태에서_CANCELLED로_전이된다() {
        Course course = openCourse(1);
        enrollmentService.apply(student10, course.getId()); // ENROLLED
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, course.getId()); // WAITLISTED

        WaitlistCancelResponse res = waitlistService.cancel(student11, waitlisted.waitlist().id());

        assertThat(res.status()).isEqualTo("CANCELLED");
        assertThat(res.cancelledAt()).isNotNull();
        Waitlist refreshed = waitlistRepository.findById(waitlisted.waitlist().id()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(WaitlistStatus.CANCELLED);
    }

    // S-W-2: 이미 CANCELLED인 waitlist에 cancel → INVALID_STATUS
    @Test
    void cancel을_이미_CANCELLED인_waitlist에_시도하면_INVALID_STATUS() {
        Course course = openCourse(1);
        enrollmentService.apply(student10, course.getId());
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, course.getId());
        waitlistService.cancel(student11, waitlisted.waitlist().id()); // 첫 cancel

        DomainException e = catchThrowableOfType(
            () -> waitlistService.cancel(student11, waitlisted.waitlist().id()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATUS);
    }

    // 보강: 본인 대기 아닌 사용자가 취소 → NOT_WAITLIST_OWNER
    @Test
    void cancel을_본인_대기_아닌_사용자가_시도하면_NOT_WAITLIST_OWNER() {
        Course course = openCourse(1);
        enrollmentService.apply(student10, course.getId());
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, course.getId());

        DomainException e = catchThrowableOfType(
            () -> waitlistService.cancel(student10, waitlisted.waitlist().id()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_WAITLIST_OWNER);
    }

    // 보강: 존재하지 않는 waitlistId → WAITLIST_NOT_FOUND
    @Test
    void cancel을_존재하지_않는_waitlistId로_시도하면_WAITLIST_NOT_FOUND() {
        DomainException e = catchThrowableOfType(
            () -> waitlistService.cancel(student11, 99999L),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.WAITLIST_NOT_FOUND);
    }

    private Course openCourse(int capacity) {
        Course course = courseService.register(creator, new CourseCreateRequest(
            "Java 마스터 클래스",
            "Spring Boot 실전",
            150_000L,
            capacity,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            7
        ));
        return courseService.open(creator, course.getId());
    }
}
