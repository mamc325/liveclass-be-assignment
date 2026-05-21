package com.example.enrollment_system.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // 400 Bad Request
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid Request",
        "요청이 유효하지 않습니다."),

    // 401 Unauthorized
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated",
        "인증이 필요합니다."),

    // 403 Forbidden
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden",
        "권한이 없습니다."),
    NOT_COURSE_OWNER(HttpStatus.FORBIDDEN, "Not Course Owner",
        "본인 강의가 아닙니다."),
    NOT_ENROLLMENT_OWNER(HttpStatus.FORBIDDEN, "Not Enrollment Owner",
        "본인 신청이 아닙니다."),
    NOT_WAITLIST_OWNER(HttpStatus.FORBIDDEN, "Not Waitlist Owner",
        "본인 대기가 아닙니다."),

    // 404 Not Found
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Course Not Found",
        "강의를 찾을 수 없습니다."),
    ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Enrollment Not Found",
        "신청을 찾을 수 없습니다."),
    WAITLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "Waitlist Not Found",
        "대기를 찾을 수 없습니다."),

    // 409 Conflict
    INVALID_TRANSITION(HttpStatus.CONFLICT, "Invalid Transition",
        "허용되지 않는 상태 전이입니다."),
    COURSE_NOT_OPEN(HttpStatus.CONFLICT, "Course Not Open",
        "강의가 모집 중 상태가 아닙니다."),
    DUPLICATE_ENROLLMENT(HttpStatus.CONFLICT, "Duplicate Enrollment",
        "이미 활성 신청이 존재합니다."),
    DUPLICATE_WAITLIST(HttpStatus.CONFLICT, "Duplicate Waitlist",
        "이미 활성 대기가 존재합니다."),
    INVALID_STATUS(HttpStatus.CONFLICT, "Invalid Status",
        "현재 상태에서 허용되지 않는 작업입니다."),
    ALREADY_CANCELLED(HttpStatus.CONFLICT, "Already Cancelled",
        "이미 취소된 항목입니다."),
    CANCEL_DEADLINE_EXCEEDED(HttpStatus.CONFLICT, "Cancel Deadline Exceeded",
        "취소 가능 기간을 초과했습니다."),

    // 500 Internal Server Error
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error",
        "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String title;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String title, String defaultMessage) {
        this.status = status;
        this.title = title;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public DomainException asException() {
        return new DomainException(this);
    }

    public DomainException with(String customMessage) {
        return new DomainException(this, customMessage);
    }
}
