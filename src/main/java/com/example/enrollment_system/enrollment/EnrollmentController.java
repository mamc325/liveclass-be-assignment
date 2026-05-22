package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.auth.CurrentUser;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
import com.example.enrollment_system.enrollment.dto.EnrollmentCancelResponse;
import com.example.enrollment_system.enrollment.dto.EnrollmentSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /**
     * POST /api/courses/{courseId}/enrollments — 수강 신청 (STUDENT).
     * 정원이 남으면 ENROLLED, 정원이 찼으면 WAITLISTED.
     */
    @PostMapping("/api/courses/{courseId}/enrollments")
    public ResponseEntity<EnrollmentApplyResponse> apply(
            @CurrentUser AuthUser caller,
            @PathVariable Long courseId) {
        EnrollmentApplyResponse response = enrollmentService.apply(caller, courseId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/enrollments/{id}/confirm — 결제 확정 (STUDENT, 본인 신청만).
     * PENDING → CONFIRMED. 외부 결제 시스템 없음 — 단순 상태 변경.
     */
    @PostMapping("/api/enrollments/{id}/confirm")
    public EnrollmentSummary confirm(
            @CurrentUser AuthUser caller,
            @PathVariable Long id) {
        return enrollmentService.confirm(caller, id);
    }

    /**
     * POST /api/enrollments/{id}/cancel — 수강 취소 (STUDENT, 본인 신청만).
     * PENDING/CONFIRMED → CANCELLED. 슬롯이 비면 자동 승격 (강의 OPEN일 때).
     * 응답의 promoted는 승격자 있을 시 채워지고, 없으면 null.
     */
    @PostMapping("/api/enrollments/{id}/cancel")
    public EnrollmentCancelResponse cancel(
            @CurrentUser AuthUser caller,
            @PathVariable Long id) {
        return enrollmentService.cancel(caller, id);
    }
}
