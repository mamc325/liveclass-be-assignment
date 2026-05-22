package com.example.enrollment_system.me;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.EnrollmentService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MeControllerTest {

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

    @Test
    void GET_me_enrollments_본인_신청만_반환된다() throws Exception {
        Long courseId = openCourse(10);
        enrollmentService.apply(student10, courseId);
        enrollmentService.apply(student11, courseId); // 다른 사용자

        mockMvc.perform(get("/api/me/enrollments").header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].userId").doesNotExist()) // userId는 MyEnrollmentRow에 없음
            .andExpect(jsonPath("$.content[0].courseId").value(courseId))
            .andExpect(jsonPath("$.content[0].courseTitle").value("Java 마스터 클래스"))
            .andExpect(jsonPath("$.content[0].courseStatus").value("OPEN"))
            .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    void GET_me_enrollments_status_필터_적용된다() throws Exception {
        Long courseId = openCourse(10);
        var applied = enrollmentService.apply(student10, courseId);
        enrollmentService.confirm(student10, applied.enrollment().id());

        mockMvc.perform(get("/api/me/enrollments")
                .header("X-USER-ID", STUDENT10)
                .param("status", "CONFIRMED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.content[0].confirmedAt").exists());
    }

    @Test
    void GET_me_waitlists_본인_대기만_반환_position_채워짐() throws Exception {
        Long courseId = openCourse(1);
        enrollmentService.apply(student10, courseId); // ENROLLED
        enrollmentService.apply(student11, courseId); // WAITLISTED

        mockMvc.perform(get("/api/me/waitlists").header("X-USER-ID", STUDENT11))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].status").value("WAITING"))
            .andExpect(jsonPath("$.content[0].position").value(1))
            .andExpect(jsonPath("$.content[0].courseTitle").value("Java 마스터 클래스"));
    }

    @Test
    void GET_me_waitlists_status_필터_적용된다() throws Exception {
        Long courseId = openCourse(1);
        enrollmentService.apply(student10, courseId);
        enrollmentService.apply(student11, courseId);

        mockMvc.perform(get("/api/me/waitlists")
                .header("X-USER-ID", STUDENT11)
                .param("status", "WAITING"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void GET_me_enrollments_CREATOR가_호출하면_403_FORBIDDEN() throws Exception {
        mockMvc.perform(get("/api/me/enrollments").header("X-USER-ID", CREATOR1))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void GET_me_enrollments_X_USER_ID_없으면_401_UNAUTHENTICATED() throws Exception {
        mockMvc.perform(get("/api/me/enrollments"))
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
