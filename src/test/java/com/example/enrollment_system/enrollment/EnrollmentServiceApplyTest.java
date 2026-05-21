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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class EnrollmentServiceApplyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired EnrollmentService enrollmentService;
    @Autowired CourseService courseService;
    @Autowired CourseRepository courseRepository;
    @Autowired JdbcTemplate jdbc;

    private final AuthUser creator = new AuthUser(1L, UserRole.CREATOR);
    private final AuthUser student10 = new AuthUser(10L, UserRole.STUDENT);
    private final AuthUser student11 = new AuthUser(11L, UserRole.STUDENT);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    // S-E-1: 정원 내 신청 → ENROLLED
    @Test
    void apply는_정원_내일_때_ENROLLED를_반환한다() {
        Course course = openCourseWithCapacity(2);

        EnrollmentApplyResponse res = enrollmentService.apply(student10, course.getId());

        assertThat(res.outcome()).isEqualTo(EnrollmentApplyResponse.Outcome.ENROLLED);
        assertThat(res.enrollment()).isNotNull();
        assertThat(res.enrollment().status()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(res.waitlist()).isNull();

        Course refreshed = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(refreshed.getOccupiedCount()).isEqualTo(1);
    }

    // S-E-2: 정원 초과 신청 → WAITLISTED
    @Test
    void apply는_정원_초과_시_WAITLISTED를_반환한다() {
        Course course = openCourseWithCapacity(1);
        enrollmentService.apply(student10, course.getId()); // 정원 채움

        EnrollmentApplyResponse res = enrollmentService.apply(student11, course.getId());

        assertThat(res.outcome()).isEqualTo(EnrollmentApplyResponse.Outcome.WAITLISTED);
        assertThat(res.enrollment()).isNull();
        assertThat(res.waitlist()).isNotNull();
        assertThat(res.waitlist().status()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(res.waitlist().position()).isEqualTo(1);

        Course refreshed = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(refreshed.getOccupiedCount()).isEqualTo(1); // 변화 없음
    }

    // S-E-3: DRAFT 강의에 신청 → COURSE_NOT_OPEN
    @Test
    void apply를_DRAFT_강의에_시도하면_COURSE_NOT_OPEN() {
        Course course = courseService.register(creator, sampleRequest(10));
        // open 호출 안 함 → 여전히 DRAFT

        DomainException e = catchThrowableOfType(
            () -> enrollmentService.apply(student10, course.getId()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.COURSE_NOT_OPEN);
    }

    // S-E-4: 활성 신청 중복 → DUPLICATE_ENROLLMENT
    @Test
    void apply를_동일_사용자가_활성_신청_상태에서_재시도하면_DUPLICATE_ENROLLMENT() {
        Course course = openCourseWithCapacity(10);
        enrollmentService.apply(student10, course.getId()); // 첫 번째 신청 성공

        DomainException e = catchThrowableOfType(
            () -> enrollmentService.apply(student10, course.getId()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.DUPLICATE_ENROLLMENT);
    }

    // S-E-5: 활성 대기 중복 → DUPLICATE_WAITLIST
    @Test
    void apply를_동일_사용자가_활성_대기_상태에서_재시도하면_DUPLICATE_WAITLIST() {
        Course course = openCourseWithCapacity(1);
        enrollmentService.apply(student10, course.getId()); // 정원 채움
        enrollmentService.apply(student11, course.getId()); // student11이 WAITING

        DomainException e = catchThrowableOfType(
            () -> enrollmentService.apply(student11, course.getId()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.DUPLICATE_WAITLIST);
    }

    // 보강: COURSE_NOT_FOUND
    @Test
    void apply를_존재하지_않는_courseId로_시도하면_COURSE_NOT_FOUND() {
        DomainException e = catchThrowableOfType(
            () -> enrollmentService.apply(student10, 99999L),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.COURSE_NOT_FOUND);
    }

    // 보강: CREATOR가 apply 시도 → FORBIDDEN
    @Test
    void apply를_CREATOR가_시도하면_FORBIDDEN() {
        Course course = openCourseWithCapacity(10);

        DomainException e = catchThrowableOfType(
            () -> enrollmentService.apply(creator, course.getId()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ----------------------------------------
    // Helpers
    // ----------------------------------------

    private Course openCourseWithCapacity(int capacity) {
        Course course = courseService.register(creator, sampleRequest(capacity));
        return courseService.open(creator, course.getId());
    }

    private CourseCreateRequest sampleRequest(int capacity) {
        return new CourseCreateRequest(
            "Java 마스터 클래스",
            "Spring Boot 실전",
            150_000L,
            capacity,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            7
        );
    }
}
