package com.example.enrollment_system.enrollment.dto;

import com.example.enrollment_system.enrollment.Enrollment;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.dto.WaitlistSummary;

public record EnrollmentApplyResponse(
    Outcome outcome,
    EnrollmentSummary enrollment,
    WaitlistSummary waitlist
) {
    public enum Outcome { ENROLLED, WAITLISTED }

    public static EnrollmentApplyResponse enrolled(Enrollment e) {
        return new EnrollmentApplyResponse(
            Outcome.ENROLLED,
            EnrollmentSummary.from(e),
            null
        );
    }

    public static EnrollmentApplyResponse waitlisted(Waitlist w, int position) {
        return new EnrollmentApplyResponse(
            Outcome.WAITLISTED,
            null,
            WaitlistSummary.from(w, position)
        );
    }
}
