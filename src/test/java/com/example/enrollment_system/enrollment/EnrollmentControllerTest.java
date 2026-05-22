package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
import com.example.enrollment_system.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class EnrollmentControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired MockMvc mockMvc;
    @Autowired CourseService courseService;
    @Autowired EnrollmentService enrollmentService;
    @Autowired JdbcTemplate jdbc;

    private static final String CREATOR1 = "1";
    private static final String STUDENT10 = "10";
    private static final String STUDENT11 = "11";

    private final AuthUser creator = new AuthUser(1L, UserRole.CREATOR);
    private final AuthUser student10 = new AuthUser(10L, UserRole.STUDENT);
    private final AuthUser student11 = new AuthUser(11L, UserRole.STUDENT);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    // ------------------- apply -------------------

    @Test
    void POST_apply_정원_내면_201_ENROLLED() throws Exception {
        Long courseId = openCourse(2);

        mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("ENROLLED"))
            .andExpect(jsonPath("$.enrollment.status").value("PENDING"))
            .andExpect(jsonPath("$.enrollment.userId").value(10))
            .andExpect(jsonPath("$.waitlist").doesNotExist());
    }

    @Test
    void POST_apply_정원_초과면_201_WAITLISTED() throws Exception {
        Long courseId = openCourse(1);
        enrollmentService.apply(student10, courseId); // 정원 채움

        mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", STUDENT11))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("WAITLISTED"))
            .andExpect(jsonPath("$.waitlist.status").value("WAITING"))
            .andExpect(jsonPath("$.waitlist.position").value(1))
            .andExpect(jsonPath("$.enrollment").doesNotExist());
    }

    @Test
    void POST_apply_CREATOR가_시도하면_403_FORBIDDEN() throws Exception {
        Long courseId = openCourse(2);

        mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", CREATOR1))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void POST_apply_DRAFT_강의면_409_COURSE_NOT_OPEN() throws Exception {
        Long courseId = createDraftCourse();

        mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("COURSE_NOT_OPEN"));
    }

    @Test
    void POST_apply_중복_신청이면_409_DUPLICATE_ENROLLMENT() throws Exception {
        Long courseId = openCourse(10);
        enrollmentService.apply(student10, courseId); // 첫 신청

        mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_ENROLLMENT"));
    }

    // ------------------- confirm -------------------

    @Test
    void POST_confirm_성공_시_200_CONFIRMED_confirmedAt_채워짐() throws Exception {
        Long courseId = openCourse(2);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, courseId);

        mockMvc.perform(post("/api/enrollments/" + applied.enrollment().id() + "/confirm")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.confirmedAt").exists());
    }

    @Test
    void POST_confirm_다른_사용자가_시도하면_403_NOT_ENROLLMENT_OWNER() throws Exception {
        Long courseId = openCourse(2);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, courseId);

        mockMvc.perform(post("/api/enrollments/" + applied.enrollment().id() + "/confirm")
                .header("X-USER-ID", STUDENT11))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_ENROLLMENT_OWNER"));
    }

    @Test
    void POST_confirm_이미_CONFIRMED면_409_INVALID_STATUS() throws Exception {
        Long courseId = openCourse(2);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, courseId);
        enrollmentService.confirm(student10, applied.enrollment().id());

        mockMvc.perform(post("/api/enrollments/" + applied.enrollment().id() + "/confirm")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    // ------------------- cancel -------------------

    @Test
    void POST_cancel_PENDING_대기자_있으면_200_promoted_채워짐() throws Exception {
        Long courseId = openCourse(1);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, courseId);
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, courseId);

        mockMvc.perform(post("/api/enrollments/" + applied.enrollment().id() + "/cancel")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enrollment.status").value("CANCELLED"))
            .andExpect(jsonPath("$.promoted").exists())
            .andExpect(jsonPath("$.promoted.userId").value(11))
            .andExpect(jsonPath("$.promoted.fromWaitlistId").value(waitlisted.waitlist().id()));
    }

    @Test
    void POST_cancel_다른_사용자가_시도하면_403_NOT_ENROLLMENT_OWNER() throws Exception {
        Long courseId = openCourse(2);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, courseId);

        mockMvc.perform(post("/api/enrollments/" + applied.enrollment().id() + "/cancel")
                .header("X-USER-ID", STUDENT11))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_ENROLLMENT_OWNER"));
    }

    @Test
    void POST_cancel_이미_CANCELLED면_409_ALREADY_CANCELLED() throws Exception {
        Long courseId = openCourse(2);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, courseId);
        enrollmentService.cancel(student10, applied.enrollment().id());

        mockMvc.perform(post("/api/enrollments/" + applied.enrollment().id() + "/cancel")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
    }

    // ------------------- ProblemDetail 형식 -------------------

    @Test
    void apply_COURSE_NOT_FOUND_시_ProblemDetail_형식() throws Exception {
        mockMvc.perform(post("/api/courses/99999/enrollments")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value("Course Not Found"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"))
            .andExpect(jsonPath("$.instance").value("/api/courses/99999/enrollments"));
    }

    // ------------------- Helpers -------------------

    private Long openCourse(int capacity) {
        Course course = courseService.register(creator, sampleRequest(capacity));
        return courseService.open(creator, course.getId()).getId();
    }

    private Long createDraftCourse() {
        return courseService.register(creator, sampleRequest(10)).getId();
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
