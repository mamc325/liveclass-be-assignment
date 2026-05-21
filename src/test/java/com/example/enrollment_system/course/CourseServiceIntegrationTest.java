package com.example.enrollment_system.course;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.DomainException;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.user.UserRole;
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
class CourseServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired CourseService courseService;
    @Autowired CourseRepository courseRepository;
    @Autowired JdbcTemplate jdbc;

    // 시드 사용자
    private final AuthUser creator1 = new AuthUser(1L, UserRole.CREATOR);
    private final AuthUser creator2 = new AuthUser(2L, UserRole.CREATOR);
    private final AuthUser student = new AuthUser(10L, UserRole.STUDENT);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    // S-C-1: 강의 등록 → DRAFT 상태 생성
    @Test
    void register는_DRAFT_상태로_Course를_생성한다() {
        Course course = courseService.register(creator1, sampleRequest());

        assertThat(course.getId()).isNotNull();
        assertThat(course.getStatus()).isEqualTo(CourseStatus.DRAFT);
        assertThat(course.getCreatorId()).isEqualTo(1L);
        assertThat(course.getOccupiedCount()).isZero();
        assertThat(course.getCancellationDeadlineDays()).isEqualTo(7);
        assertThat(course.getCreatedAt()).isNotNull();
    }

    // S-C-2: DRAFT → open
    @Test
    void open은_DRAFT_상태에서_OPEN으로_전이한다() {
        Course course = courseService.register(creator1, sampleRequest());

        Course opened = courseService.open(creator1, course.getId());

        assertThat(opened.getStatus()).isEqualTo(CourseStatus.OPEN);
    }

    // S-C-3: OPEN → close
    @Test
    void close는_OPEN_상태에서_CLOSED로_전이한다() {
        Course course = courseService.register(creator1, sampleRequest());
        courseService.open(creator1, course.getId());

        Course closed = courseService.close(creator1, course.getId());

        assertThat(closed.getStatus()).isEqualTo(CourseStatus.CLOSED);
    }

    // S-C-4: DRAFT → close 시도 → INVALID_TRANSITION
    @Test
    void close를_DRAFT_상태에서_시도하면_INVALID_TRANSITION() {
        Course course = courseService.register(creator1, sampleRequest());

        DomainException e = catchThrowableOfType(
            () -> courseService.close(creator1, course.getId()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_TRANSITION);
    }

    // S-C-5: 본인 강의 아닌 사용자가 open 시도 → NOT_COURSE_OWNER
    @Test
    void open을_본인_강의_아닌_사용자가_시도하면_NOT_COURSE_OWNER() {
        Course course = courseService.register(creator1, sampleRequest());

        DomainException e = catchThrowableOfType(
            () -> courseService.open(creator2, course.getId()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_COURSE_OWNER);
    }

    // 보강: STUDENT가 register 시도 → FORBIDDEN
    @Test
    void register를_STUDENT가_시도하면_FORBIDDEN() {
        DomainException e = catchThrowableOfType(
            () -> courseService.register(student, sampleRequest()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    // 보강: 존재하지 않는 courseId open → COURSE_NOT_FOUND
    @Test
    void open을_존재하지_않는_courseId로_시도하면_COURSE_NOT_FOUND() {
        DomainException e = catchThrowableOfType(
            () -> courseService.open(creator1, 99999L),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.COURSE_NOT_FOUND);
    }

    // 보강: detail() 정상
    @Test
    void detail은_존재하는_courseId면_조회된다() {
        Course course = courseService.register(creator1, sampleRequest());

        Course found = courseService.detail(course.getId());

        assertThat(found.getId()).isEqualTo(course.getId());
        assertThat(found.getTitle()).isEqualTo("Java 마스터 클래스");
    }

    // 보강: detail() 없는 id → COURSE_NOT_FOUND
    @Test
    void detail은_존재하지_않는_courseId면_COURSE_NOT_FOUND() {
        DomainException e = catchThrowableOfType(
            () -> courseService.detail(99999L),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.COURSE_NOT_FOUND);
    }

    private CourseCreateRequest sampleRequest() {
        return new CourseCreateRequest(
            "Java 마스터 클래스",
            "Spring Boot 실전 강의",
            150_000L,
            30,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            7
        );
    }
}
