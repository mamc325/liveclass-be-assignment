package com.example.enrollment_system.course.dto;

import com.example.enrollment_system.enrollment.Enrollment;
import com.example.enrollment_system.enrollment.EnrollmentStatus;

import java.time.OffsetDateTime;

/**
 * 강의별 수강생 목록 응답 행 (크리에이터 전용). API.md 7.12.
 */
public record CourseEnrollmentRow(
    Long id,
    Long userId,
    String userName,
    EnrollmentStatus status,
    Long promotedFromWaitlistId,
    OffsetDateTime confirmedAt,
    OffsetDateTime cancelledAt,
    OffsetDateTime createdAt
) {
    public static CourseEnrollmentRow from(Enrollment e, String userName) {
        return new CourseEnrollmentRow(
            e.getId(),
            e.getUserId(),
            userName,
            e.getStatus(),
            e.getPromotedFromWaitlistId(),
            e.getConfirmedAt(),
            e.getCancelledAt(),
            e.getCreatedAt()
        );
    }
}
