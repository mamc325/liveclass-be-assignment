package com.example.enrollment_system.waitlist.dto;

import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistStatus;

import java.time.OffsetDateTime;

public record WaitlistSummary(
    Long id,
    Long courseId,
    Long userId,
    WaitlistStatus status,
    Integer position,
    OffsetDateTime createdAt
) {
    public static WaitlistSummary from(Waitlist w, int position) {
        return new WaitlistSummary(
            w.getId(),
            w.getCourseId(),
            w.getUserId(),
            w.getStatus(),
            position,
            w.getCreatedAt()
        );
    }
}
