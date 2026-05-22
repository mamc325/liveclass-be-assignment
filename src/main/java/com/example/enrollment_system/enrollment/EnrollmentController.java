package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.auth.CurrentUser;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
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
}
