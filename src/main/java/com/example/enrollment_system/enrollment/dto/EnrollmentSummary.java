package com.example.enrollment_system.enrollment.dto;

import com.example.enrollment_system.enrollment.Enrollment;
import com.example.enrollment_system.enrollment.EnrollmentStatus;

import java.time.OffsetDateTime;

public record EnrollmentSummary(
    Long id,
    Long courseId,
    Long userId,
    EnrollmentStatus status,
    Long promotedFromWaitlistId,
    OffsetDateTime confirmedAt,
    OffsetDateTime cancelledAt,
    OffsetDateTime createdAt
) {
    public static EnrollmentSummary from(Enrollment e) {
        return new EnrollmentSummary(
            e.getId(),
            e.getCourseId(),
            e.getUserId(),
            e.getStatus(),
            e.getPromotedFromWaitlistId(),
            e.getConfirmedAt(),
            e.getCancelledAt(),
            e.getCreatedAt()
        );
    }
}
