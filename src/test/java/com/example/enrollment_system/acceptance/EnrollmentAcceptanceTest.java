package com.example.enrollment_system.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 인수 테스트 — 여러 API를 연결한 사용자 흐름 검증.
 * (TEST_SCENARIOS 8.5)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class EnrollmentAcceptanceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private static final String CREATOR1 = "1";
    private static final String STUDENT10 = "10";
    private static final String STUDENT11 = "11";

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    /**
     * AT-1: 강의 등록 → open → 신청 → confirm → 내 신청 목록 조회.
     * 최종 enrollment 상태 CONFIRMED, 본인 목록에 포함됨을 확인.
     */
    @Test
    void AT_1_등록부터_결제_확정까지의_전체_흐름() throws Exception {
        // 1. 강의 등록 (CREATOR)
        Long courseId = createCourse(CREATOR1, 10);

        // 2. open
        mockMvc.perform(post("/api/courses/" + courseId + "/open")
                .header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN"));

        // 3. STUDENT가 수강 신청
        MvcResult applyResult = mockMvc.perform(
                post("/api/courses/" + courseId + "/enrollments")
                    .header("X-USER-ID", STUDENT10))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("ENROLLED"))
            .andExpect(jsonPath("$.enrollment.status").value("PENDING"))
            .andReturn();
        Long enrollmentId = jsonOf(applyResult).path("enrollment").path("id").asLong();

        // 4. 결제 확정
        mockMvc.perform(post("/api/enrollments/" + enrollmentId + "/confirm")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.confirmedAt").exists());

        // 5. 내 신청 목록 조회 → 1건, CONFIRMED 상태
        mockMvc.perform(get("/api/me/enrollments").header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(enrollmentId))
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.content[0].courseTitle").value("Java 마스터 클래스"));
    }

    /**
     * AT-2: 정원 1명 강의 → A 신청(ENROLLED) → B 신청(WAITLISTED) → A 취소.
     * B의 waitlist는 PROMOTED, B의 새 enrollment는 PENDING, occupiedCount는 1 유지.
     */
    @Test
    void AT_2_정원_1명_자동_승격_흐름() throws Exception {
        // 1. capacity=1 강의 등록 + open
        Long courseId = createCourse(CREATOR1, 1);
        mockMvc.perform(post("/api/courses/" + courseId + "/open").header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk());

        // 2. A(student10) 신청 → ENROLLED
        MvcResult aApply = mockMvc.perform(
                post("/api/courses/" + courseId + "/enrollments")
                    .header("X-USER-ID", STUDENT10))
            .andExpect(jsonPath("$.outcome").value("ENROLLED"))
            .andReturn();
        Long aEnrollmentId = jsonOf(aApply).path("enrollment").path("id").asLong();

        // 3. B(student11) 신청 → WAITLISTED
        MvcResult bApply = mockMvc.perform(
                post("/api/courses/" + courseId + "/enrollments")
                    .header("X-USER-ID", STUDENT11))
            .andExpect(jsonPath("$.outcome").value("WAITLISTED"))
            .andExpect(jsonPath("$.waitlist.position").value(1))
            .andReturn();
        Long bWaitlistId = jsonOf(bApply).path("waitlist").path("id").asLong();

        // 4. A 취소 → 자동 승격 발동
        MvcResult cancelResult = mockMvc.perform(
                post("/api/enrollments/" + aEnrollmentId + "/cancel")
                    .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enrollment.status").value("CANCELLED"))
            .andExpect(jsonPath("$.promoted").exists())
            .andExpect(jsonPath("$.promoted.fromWaitlistId").value(bWaitlistId))
            .andExpect(jsonPath("$.promoted.userId").value(11))
            .andReturn();
        Long bPromotedEnrollmentId = jsonOf(cancelResult).path("promoted").path("enrollmentId").asLong();

        // 5. B의 내 신청 목록 — 새로 생성된 PENDING enrollment 확인
        mockMvc.perform(get("/api/me/enrollments").header("X-USER-ID", STUDENT11))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(bPromotedEnrollmentId))
            .andExpect(jsonPath("$.content[0].status").value("PENDING"))
            .andExpect(jsonPath("$.content[0].promotedFromWaitlistId").value(bWaitlistId));

        // 6. 강의 상세 → occupiedCount=1 유지
        mockMvc.perform(get("/api/courses/" + courseId).header("X-USER-ID", STUDENT11))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.occupiedCount").value(1))
            .andExpect(jsonPath("$.remainingCount").value(0));

        // 7. B의 대기 목록 — PROMOTED 상태로 보임
        mockMvc.perform(get("/api/me/waitlists").header("X-USER-ID", STUDENT11))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].status").value("PROMOTED"))
            .andExpect(jsonPath("$.content[0].promotedAt").exists());
    }

    /**
     * AT-3: OPEN 강의에서 close 후 기존 PENDING 취소.
     * occupied_count는 감소하지만 대기자 자동 승격은 발동하지 않는다.
     */
    @Test
    void AT_3_CLOSED_강의에서_취소시_자동_승격_없음() throws Exception {
        // 1. capacity=1 강의 등록 + open
        Long courseId = createCourse(CREATOR1, 1, 7);
        mockMvc.perform(post("/api/courses/" + courseId + "/open").header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk());

        // 2. A 신청(ENROLLED), B 신청(WAITLISTED)
        MvcResult aApply = mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", STUDENT10))
            .andExpect(jsonPath("$.outcome").value("ENROLLED"))
            .andReturn();
        Long aEnrollmentId = jsonOf(aApply).path("enrollment").path("id").asLong();

        mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", STUDENT11))
            .andExpect(jsonPath("$.outcome").value("WAITLISTED"));

        // 3. 강의 close
        mockMvc.perform(post("/api/courses/" + courseId + "/close").header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"));

        // 4. A 취소 → 자동 승격 발동 안 됨
        mockMvc.perform(post("/api/enrollments/" + aEnrollmentId + "/cancel")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enrollment.status").value("CANCELLED"))
            .andExpect(jsonPath("$.promoted").doesNotExist()); // null이라 non_null 정책으로 필드 제거됨

        // 5. 강의 상세 → occupiedCount=0, B는 여전히 WAITING
        mockMvc.perform(get("/api/courses/" + courseId).header("X-USER-ID", STUDENT11))
            .andExpect(jsonPath("$.occupiedCount").value(0))
            .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/me/waitlists").header("X-USER-ID", STUDENT11))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].status").value("WAITING"));
    }

    /**
     * AT-4: 결제 확정 후 기간 초과 취소 시도 → CANCEL_DEADLINE_EXCEEDED.
     * cancellationDeadlineDays=0으로 즉시 만료 유도 (Clock mock 없이도 시간 진행으로 만료됨).
     */
    @Test
    void AT_4_결제_확정_후_기간_초과시_취소_불가() throws Exception {
        // 1. cancellationDeadlineDays=0인 강의 등록 + open
        Long courseId = createCourse(CREATOR1, 2, 0);
        mockMvc.perform(post("/api/courses/" + courseId + "/open").header("X-USER-ID", CREATOR1))
            .andExpect(status().isOk());

        // 2. STUDENT 신청 + 결제 확정
        MvcResult apply = mockMvc.perform(post("/api/courses/" + courseId + "/enrollments")
                .header("X-USER-ID", STUDENT10))
            .andExpect(jsonPath("$.outcome").value("ENROLLED"))
            .andReturn();
        Long enrollmentId = jsonOf(apply).path("enrollment").path("id").asLong();

        mockMvc.perform(post("/api/enrollments/" + enrollmentId + "/confirm")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 3. 취소 시도 — deadline=0이므로 confirm 직후 already expired
        mockMvc.perform(post("/api/enrollments/" + enrollmentId + "/cancel")
                .header("X-USER-ID", STUDENT10))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CANCEL_DEADLINE_EXCEEDED"));

        // 4. 상태 불변 확인 — 여전히 CONFIRMED
        mockMvc.perform(get("/api/me/enrollments").header("X-USER-ID", STUDENT10))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));

        // 5. 강의 상세 — occupiedCount 변화 없음
        mockMvc.perform(get("/api/courses/" + courseId).header("X-USER-ID", STUDENT10))
            .andExpect(jsonPath("$.occupiedCount").value(1));
    }

    // ----- Helpers -----

    private Long createCourse(String creatorHeader, int capacity) throws Exception {
        return createCourse(creatorHeader, capacity, 7);
    }

    private Long createCourse(String creatorHeader, int capacity, int cancellationDeadlineDays) throws Exception {
        String body = """
            {
              "title": "Java 마스터 클래스",
              "description": "Spring Boot 실전",
              "price": 150000,
              "capacity": %d,
              "startDate": "2026-06-01",
              "endDate": "2026-08-31",
              "cancellationDeadlineDays": %d
            }
            """.formatted(capacity, cancellationDeadlineDays);
        MvcResult result = mockMvc.perform(post("/api/courses")
                .header("X-USER-ID", creatorHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return jsonOf(result).path("id").asLong();
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
