package com.example.enrollment_system.course.dto;

import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CourseResponse(
    Long id,
    Long creatorId,
    String title,
    String description,
    Long price,
    Integer capacity,
    Integer occupiedCount,
    Integer remainingCount,
    LocalDate startDate,
    LocalDate endDate,
    CourseStatus status,
    Integer cancellationDeadlineDays,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static CourseResponse from(Course course) {
        return new CourseResponse(
            course.getId(),
            course.getCreatorId(),
            course.getTitle(),
            course.getDescription(),
            course.getPrice(),
            course.getCapacity(),
            course.getOccupiedCount(),
            course.getRemainingCount(),
            course.getStartDate(),
            course.getEndDate(),
            course.getStatus(),
            course.getCancellationDeadlineDays(),
            course.getCreatedAt(),
            course.getUpdatedAt()
        );
    }
}
