package com.example.enrollment_system.course.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CourseCreateRequest(
    @NotBlank
    @Size(min = 1, max = 100)
    String title,

    @Size(max = 10_000)
    String description,

    @NotNull
    @Min(0)
    Long price,

    @NotNull
    @Min(1)
    Integer capacity,

    @NotNull
    LocalDate startDate,

    @NotNull
    LocalDate endDate,

    @Min(0)
    Integer cancellationDeadlineDays
) {
    /**
     * 필드 간 검증: startDate <= endDate.
     * Bean Validation의 @AssertTrue로 처리해 errors[] 응답 필드에 포함.
     */
    @AssertTrue(message = "startDate는 endDate 이전이거나 같아야 합니다.")
    public boolean isDateRangeValid() {
        if (startDate == null || endDate == null) return true;
        return !startDate.isAfter(endDate);
    }
}
