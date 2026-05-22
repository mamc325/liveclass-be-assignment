package com.example.enrollment_system.me.dto;

import com.example.enrollment_system.waitlist.WaitlistStatus;

import java.time.OffsetDateTime;

public record MyWaitlistRow(
    Long id,
    Long courseId,
    String courseTitle,
    WaitlistStatus status,
    Integer position,
    OffsetDateTime promotedAt,
    OffsetDateTime cancelledAt,
    OffsetDateTime createdAt
) {}
