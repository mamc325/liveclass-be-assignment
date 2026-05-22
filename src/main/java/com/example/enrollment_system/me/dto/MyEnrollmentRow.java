package com.example.enrollment_system.me.dto;

import com.example.enrollment_system.enrollment.EnrollmentStatus;

import java.time.OffsetDateTime;

public record MyEnrollmentRow(
    Long id,
    Long courseId,
    String courseTitle,
    String courseStatus,
    EnrollmentStatus status,
    Long promotedFromWaitlistId,
    OffsetDateTime confirmedAt,
    OffsetDateTime cancelledAt,
    OffsetDateTime createdAt
) {}
