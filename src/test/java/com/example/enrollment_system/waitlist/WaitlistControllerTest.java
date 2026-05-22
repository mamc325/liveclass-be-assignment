package com.example.enrollment_system.waitlist;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.EnrollmentService;
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
class WaitlistControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired MockMvc mockMvc;
    @Autowired CourseService courseService;
    @Autowired EnrollmentService enrollmentService;
    @Autowired WaitlistService waitlistService;
    @Autowired JdbcTemplate jdbc;

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

    @Test
    void POST_cancel_WAITING_상태에서_200_CANCELLED() throws Exception {
        Long courseId = openCourse(1);
        enrollmentService.apply(student10, courseId); // ENROLLED
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, courseId); // WAITLISTED

        mockMvc.perform(post("/api/waitlists/" + waitlisted.waitlist().id() + "/cancel")
                .header("X-USER-ID", STUDENT11))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelledAt").exists());
    }

    @Test
    void POST_cancel_다른_사용자가_시도하면_403_NOT_WAITLIST_OWNER() throws Exception {
        Long courseId = openCourse(1);
        enrollmentService.apply(student10, courseId);
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, courseId);

        mockMvc.perform(post("/api/waitlists/" + waitlisted.waitlist().id() + "/cancel")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_WAITLIST_OWNER"));
    }

    @Test
    void POST_cancel_이미_CANCELLED면_409_INVALID_STATUS() throws Exception {
        Long courseId = openCourse(1);
        enrollmentService.apply(student10, courseId);
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, courseId);
        waitlistService.cancel(student11, waitlisted.waitlist().id());

        mockMvc.perform(post("/api/waitlists/" + waitlisted.waitlist().id() + "/cancel")
                .header("X-USER-ID", STUDENT11))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void POST_cancel_존재하지_않는_waitlistId면_404_WAITLIST_NOT_FOUND() throws Exception {
        mockMvc.perform(post("/api/waitlists/99999/cancel")
                .header("X-USER-ID", STUDENT11))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("WAITLIST_NOT_FOUND"));
    }

    @Test
    void POST_cancel_X_USER_ID_없으면_401_UNAUTHENTICATED() throws Exception {
        mockMvc.perform(post("/api/waitlists/1/cancel"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
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
}
