package com.example.enrollment_system.course.dto;

import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseStatus;

import java.time.OffsetDateTime;

/**
 * 강의 상태 전이 응답 (open/close용 경량 DTO).
 * API.md 7.2/7.3 응답 형태 — {id, status, updatedAt}.
 */
public record CourseStatusResponse(
    Long id,
    CourseStatus status,
    OffsetDateTime updatedAt
) {
    public static CourseStatusResponse from(Course course) {
        return new CourseStatusResponse(
            course.getId(),
            course.getStatus(),
            course.getUpdatedAt()
        );
    }
}
