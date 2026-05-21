package com.example.enrollment_system.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MockHttpServletRequest mock = new MockHttpServletRequest();
        mock.setRequestURI("/api/test");
        request = mock;
    }

    // -----------------------------------------------------------------
    // 도메인 예외
    // -----------------------------------------------------------------

    @Test
    void DomainException은_ErrorCode_속성으로_ProblemDetail을_만든다() {
        DomainException e = ErrorCode.COURSE_NOT_OPEN.asException();

        ResponseEntity<ProblemDetail> res = handler.handleDomain(e, request);

        ProblemDetail body = res.getBody();
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getTitle()).isEqualTo("Course Not Open");
        assertThat(body.getDetail()).isEqualTo("강의가 모집 중 상태가 아닙니다.");
        assertThat(body.getInstance().toString()).isEqualTo("/api/test");
        assertThat(body.getProperties()).containsEntry("code", "COURSE_NOT_OPEN");
    }

    @Test
    void DomainException은_customMessage를_detail에_반영한다() {
        DomainException e = ErrorCode.COURSE_NOT_FOUND.with("강의를 찾을 수 없습니다. (id=42)");

        ResponseEntity<ProblemDetail> res = handler.handleDomain(e, request);

        assertThat(res.getBody().getDetail()).isEqualTo("강의를 찾을 수 없습니다. (id=42)");
        assertThat(res.getBody().getProperties()).containsEntry("code", "COURSE_NOT_FOUND");
    }

    // -----------------------------------------------------------------
    // 헤더 누락
    // -----------------------------------------------------------------

    @Test
    void X_USER_ID_헤더_누락은_UNAUTHENTICATED_401() throws Exception {
        MissingRequestHeaderException e =
            new MissingRequestHeaderException("X-USER-ID", dummyMethodParameter());

        ResponseEntity<ProblemDetail> res = handler.handleMissingHeader(e, request);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().getProperties()).containsEntry("code", "UNAUTHENTICATED");
    }

    @Test
    void 그_외_헤더_누락은_INVALID_REQUEST_400() throws Exception {
        MissingRequestHeaderException e =
            new MissingRequestHeaderException("X-Other-Header", dummyMethodParameter());

        ResponseEntity<ProblemDetail> res = handler.handleMissingHeader(e, request);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(res.getBody().getDetail()).contains("X-Other-Header");
    }

    // -----------------------------------------------------------------
    // DB 제약 위반
    // -----------------------------------------------------------------

    @Test
    void uq_active_enrollment_위반은_DUPLICATE_ENROLLMENT() {
        DataIntegrityViolationException e = mockDbViolation(
            "duplicate key value violates unique constraint \"uq_active_enrollment_per_user_course\""
        );

        ResponseEntity<ProblemDetail> res = handler.handleDataIntegrity(e, request);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().getProperties()).containsEntry("code", "DUPLICATE_ENROLLMENT");
    }

    @Test
    void uq_waiting_waitlist_위반은_DUPLICATE_WAITLIST() {
        DataIntegrityViolationException e = mockDbViolation(
            "duplicate key value violates unique constraint \"uq_waiting_waitlist_per_user_course\""
        );

        ResponseEntity<ProblemDetail> res = handler.handleDataIntegrity(e, request);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().getProperties()).containsEntry("code", "DUPLICATE_WAITLIST");
    }

    @Test
    void uq_promoted_from_waitlist_위반은_INTERNAL_ERROR() {
        DataIntegrityViolationException e = mockDbViolation(
            "duplicate key value violates unique constraint \"uq_enrollments_promoted_from_waitlist\""
        );

        ResponseEntity<ProblemDetail> res = handler.handleDataIntegrity(e, request);

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void 알수없는_DB_위반은_INTERNAL_ERROR() {
        DataIntegrityViolationException e = mockDbViolation(
            "unexpected constraint violation"
        );

        ResponseEntity<ProblemDetail> res = handler.handleDataIntegrity(e, request);

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    // -----------------------------------------------------------------
    // Throwable fallback
    // -----------------------------------------------------------------

    @Test
    void 알수없는_예외는_INTERNAL_ERROR_500() {
        Throwable e = new RuntimeException("뭔지 모를 에러");

        ResponseEntity<ProblemDetail> res = handler.handleUnknown(e, request);

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        // 스택 트레이스는 응답에 노출되지 않음
        assertThat(res.getBody().getDetail()).doesNotContain("RuntimeException");
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private DataIntegrityViolationException mockDbViolation(String causeMessage) {
        return new DataIntegrityViolationException("wrapped", new RuntimeException(causeMessage));
    }

    /** MissingRequestHeaderException 생성용 더미 MethodParameter. */
    private MethodParameter dummyMethodParameter() throws NoSuchMethodException {
        Method m = DummyController.class.getMethod("dummy", String.class);
        return new MethodParameter(m, 0);
    }

    static class DummyController {
        public void dummy(@RequestHeader("X-USER-ID") String header) {}
    }
}
