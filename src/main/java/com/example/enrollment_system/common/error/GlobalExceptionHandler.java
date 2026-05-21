package com.example.enrollment_system.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 도메인 예외 → ProblemDetail. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException e, HttpServletRequest req) {
        ErrorCode code = e.errorCode();
        return build(code, e.getMessage(), req);
    }

    /** request body Bean Validation 실패. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
            .map(this::toFieldError)
            .toList();
        ProblemDetail problem = problem(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), req);
        problem.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status()).body(problem);
    }

    /** path/query parameter 타입 변환 실패 (잘못된 enum 값 등). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        String detail = "파라미터 '%s'의 값이 유효하지 않습니다.".formatted(e.getName());
        return build(ErrorCode.INVALID_REQUEST, detail, req);
    }

    /** 서비스 레이어 @Validated 검증 실패. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException e, HttpServletRequest req) {
        return build(ErrorCode.INVALID_REQUEST, e.getMessage(), req);
    }

    /** form/query parameter binding 실패. */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleBind(BindException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
            .map(this::toFieldError)
            .toList();
        ProblemDetail problem = problem(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(), req);
        problem.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status()).body(problem);
    }

    /** JSON 파싱 실패. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(
            HttpMessageNotReadableException e, HttpServletRequest req) {
        return build(ErrorCode.INVALID_REQUEST, "요청 본문을 해석할 수 없습니다.", req);
    }

    /** X-USER-ID 헤더 누락 → UNAUTHENTICATED. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException e, HttpServletRequest req) {
        if ("X-USER-ID".equalsIgnoreCase(e.getHeaderName())) {
            return build(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.defaultMessage(), req);
        }
        return build(ErrorCode.INVALID_REQUEST,
            "필수 헤더 '%s'가 누락되었습니다.".formatted(e.getHeaderName()), req);
    }

    /** DB 제약 위반 — partial unique violation 분기. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(
            DataIntegrityViolationException e, HttpServletRequest req) {
        ErrorCode code = resolveByConstraintName(e);
        log.warn("DB 제약 위반 → {}: {}", code, e.getMostSpecificCause().getMessage());
        return build(code, code.defaultMessage(), req);
    }

    /** 그 외 모든 예외 → INTERNAL_ERROR. */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> handleUnknown(Throwable e, HttpServletRequest req) {
        log.error("Unhandled exception", e);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), req);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, HttpServletRequest req) {
        return ResponseEntity.status(code.status()).body(problem(code, detail, req));
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setInstance(URI.create(req.getRequestURI()));
        problem.setProperty("code", code.name());
        return problem;
    }

    private Map<String, String> toFieldError(FieldError fe) {
        return Map.of(
            "field", fe.getField(),
            "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "유효하지 않은 값입니다."
        );
    }

    private ErrorCode resolveByConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String msg = cause != null ? cause.getMessage() : "";
        if (msg == null) {
            return ErrorCode.INTERNAL_ERROR;
        }
        if (msg.contains("uq_active_enrollment_per_user_course")) {
            return ErrorCode.DUPLICATE_ENROLLMENT;
        }
        if (msg.contains("uq_waiting_waitlist_per_user_course")) {
            return ErrorCode.DUPLICATE_WAITLIST;
        }
        if (msg.contains("uq_enrollments_promoted_from_waitlist")) {
            // 자동 승격 로직의 중복 실행 — 코드 버그 시그널
            return ErrorCode.INTERNAL_ERROR;
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
