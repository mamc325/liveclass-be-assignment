package com.example.enrollment_system.course.dto;

import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistStatus;

import java.time.OffsetDateTime;

/**
 * 강의별 대기자 목록 응답 행 (크리에이터 전용). API.md 7.13.
 */
public record CourseWaitlistRow(
    Long id,
    Long userId,
    String userName,
    WaitlistStatus status,
    OffsetDateTime promotedAt,
    OffsetDateTime cancelledAt,
    OffsetDateTime createdAt
) {
    public static CourseWaitlistRow from(Waitlist w, String userName) {
        return new CourseWaitlistRow(
            w.getId(),
            w.getUserId(),
            userName,
            w.getStatus(),
            w.getPromotedAt(),
            w.getCancelledAt(),
            w.getCreatedAt()
        );
    }
}
