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
    OffsetDateTime createdAt
) {
    public static EnrollmentSummary from(Enrollment e) {
        return new EnrollmentSummary(
            e.getId(),
            e.getCourseId(),
            e.getUserId(),
            e.getStatus(),
            e.getPromotedFromWaitlistId(),
            e.getCreatedAt()
        );
    }
}
