package com.example.enrollment_system.enrollment.dto;

public record EnrollmentCancelResponse(
    EnrollmentSummary enrollment,
    PromotedInfo promoted
) {}
