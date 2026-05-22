package com.example.enrollment_system.course;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CourseControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseService courseService;
    @Autowired JdbcTemplate jdbc;

    private static final String CREATOR1 = "1";
    private static final String CREATOR2 = "2";
    private static final String STUDENT10 = "10";

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    // ------------------- Happy paths -------------------

    @Test
    void POST_courses_은_201과_CourseResponse를_반환한다() throws Exception {
        CourseCreateRequest req = sampleRequest();

        mockMvc.perform(post("/api/courses")
                .header("X-USER-ID", CREATOR1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.creatorId").value(1))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.occupiedCount").value(0))
            .andExpect(jsonPath("$.remainingCount").value(30))
            .andExpect(jsonPath("$.title").value("Java 마스터 클래스"));
    }

    @Test
    void POST_open_은_DRAFT를_OPEN으로_전이한다() throws Exception {
        Long courseId = createDraftCourse();

        mockMvc.perform(post("/api/courses/" + courseId + "/open")
                .header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(courseId))
            .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void POST_close_는_OPEN을_CLOSED로_전이한다() throws Exception {
        Long courseId = createDraftCourse();
        courseService.open(new AuthUser(1L, UserRole.CREATOR), courseId);

        mockMvc.perform(post("/api/courses/" + courseId + "/close")
                .header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void GET_courses_는_목록을_반환한다() throws Exception {
        createDraftCourse();
        createDraftCourse();

        mockMvc.perform(get("/api/courses")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void GET_courses_id_는_상세를_반환한다() throws Exception {
        Long courseId = createDraftCourse();

        mockMvc.perform(get("/api/courses/" + courseId)
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(courseId))
            .andExpect(jsonPath("$.title").value("Java 마스터 클래스"))
            .andExpect(jsonPath("$.description").value("Spring Boot 실전"));
    }

    @Test
    void GET_courses_id_enrollments_는_본인_강의_수강생을_반환한다() throws Exception {
        Long courseId = createDraftCourse();

        mockMvc.perform(get("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void GET_courses_id_waitlists_는_본인_강의_대기자를_반환한다() throws Exception {
        Long courseId = createDraftCourse();

        mockMvc.perform(get("/api/courses/" + courseId + "/waitlists")
                .header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }

    // ------------------- 권한 -------------------

    @Test
    void POST_courses_을_STUDENT가_시도하면_403_FORBIDDEN() throws Exception {
        mockMvc.perform(post("/api/courses")
                .header("X-USER-ID", STUDENT10)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void POST_open_을_본인_강의_아닌_creator가_시도하면_403_NOT_COURSE_OWNER() throws Exception {
        Long courseId = createDraftCourse(); // creator1 소유

        mockMvc.perform(post("/api/courses/" + courseId + "/open")
                .header("X-USER-ID", CREATOR2))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_COURSE_OWNER"));
    }

    // ------------------- 인증 -------------------

    @Test
    void POST_courses_을_X_USER_ID_없이_시도하면_401_UNAUTHENTICATED() throws Exception {
        mockMvc.perform(post("/api/courses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ------------------- 검증 -------------------

    @Test
    void POST_courses_을_title_누락으로_시도하면_400_INVALID_REQUEST_errors_포함() throws Exception {
        String json = """
            {
              "description": "설명",
              "price": 150000,
              "capacity": 30,
              "startDate": "2026-06-01",
              "endDate": "2026-08-31",
              "cancellationDeadlineDays": 7
            }
            """;

        mockMvc.perform(post("/api/courses")
                .header("X-USER-ID", CREATOR1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void POST_courses_을_capacity_0으로_시도하면_400_INVALID_REQUEST() throws Exception {
        String json = """
            {
              "title": "테스트",
              "price": 150000,
              "capacity": 0,
              "startDate": "2026-06-01",
              "endDate": "2026-08-31"
            }
            """;

        mockMvc.perform(post("/api/courses")
                .header("X-USER-ID", CREATOR1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void POST_courses_을_startDate가_endDate보다_늦으면_400() throws Exception {
        String json = """
            {
              "title": "테스트",
              "price": 150000,
              "capacity": 10,
              "startDate": "2026-09-01",
              "endDate": "2026-08-31"
            }
            """;

        mockMvc.perform(post("/api/courses")
                .header("X-USER-ID", CREATOR1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ------------------- ProblemDetail 형식 -------------------

    @Test
    void POST_open이_DRAFT_아니면_INVALID_TRANSITION_ProblemDetail_형식() throws Exception {
        Long courseId = createDraftCourse();
        courseService.open(new AuthUser(1L, UserRole.CREATOR), courseId); // 이미 OPEN

        mockMvc.perform(post("/api/courses/" + courseId + "/open")
                .header("X-USER-ID", CREATOR1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value("Invalid Transition"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
            .andExpect(jsonPath("$.instance").value("/api/courses/" + courseId + "/open"));
    }

    // ------------------- Helpers -------------------

    private Long createDraftCourse() {
        return courseService.register(
            new AuthUser(1L, UserRole.CREATOR), sampleRequest()).getId();
    }

    private CourseCreateRequest sampleRequest() {
        return new CourseCreateRequest(
            "Java 마스터 클래스",
            "Spring Boot 실전",
            150_000L,
            30,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            7
        );
    }
}
