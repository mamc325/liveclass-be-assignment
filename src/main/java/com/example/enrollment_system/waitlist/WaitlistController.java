package com.example.enrollment_system.waitlist;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.auth.CurrentUser;
import com.example.enrollment_system.waitlist.dto.WaitlistCancelResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    /**
     * POST /api/waitlists/{id}/cancel — 대기 취소 (STUDENT, 본인 대기만).
     * WAITING → CANCELLED.
     */
    @PostMapping("/api/waitlists/{id}/cancel")
    public WaitlistCancelResponse cancel(
            @CurrentUser AuthUser caller,
            @PathVariable Long id) {
        return waitlistService.cancel(caller, id);
    }
}
