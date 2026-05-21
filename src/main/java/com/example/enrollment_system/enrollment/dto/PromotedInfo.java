package com.example.enrollment_system.enrollment.dto;

/**
 * 자동 승격 결과. 수강 취소 응답에 함께 담긴다.
 */
public record PromotedInfo(
    Long enrollmentId,
    Long fromWaitlistId,
    Long userId
) {}
