package com.example.enrollment_system.course.dto;

import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 강의 목록용 요약 응답. description은 제외 (API.md 7.4 정책).
 */
public record CourseSummaryResponse(
    Long id,
    Long creatorId,
    String title,
    Long price,
    Integer capacity,
    Integer occupiedCount,
    Integer remainingCount,
    LocalDate startDate,
    LocalDate endDate,
    CourseStatus status,
    OffsetDateTime createdAt
) {
    public static CourseSummaryResponse from(Course course) {
        return new CourseSummaryResponse(
            course.getId(),
            course.getCreatorId(),
            course.getTitle(),
            course.getPrice(),
            course.getCapacity(),
            course.getOccupiedCount(),
            course.getRemainingCount(),
            course.getStartDate(),
            course.getEndDate(),
            course.getStatus(),
            course.getCreatedAt()
        );
    }
}
