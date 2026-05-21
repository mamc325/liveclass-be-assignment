package com.example.enrollment_system.waitlist.dto;

import com.example.enrollment_system.waitlist.Waitlist;

import java.time.OffsetDateTime;

public record WaitlistCancelResponse(
    Long id,
    String status,
    OffsetDateTime cancelledAt
) {
    public static WaitlistCancelResponse from(Waitlist w) {
        return new WaitlistCancelResponse(
            w.getId(),
            w.getStatus().name(),
            w.getCancelledAt()
        );
    }
}
