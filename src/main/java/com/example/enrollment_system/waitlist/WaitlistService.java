package com.example.enrollment_system.waitlist;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.waitlist.dto.WaitlistCancelResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class WaitlistService {

    private final WaitlistRepository waitlistRepository;
    private final Clock clock;

    public WaitlistService(WaitlistRepository waitlistRepository, Clock clock) {
        this.waitlistRepository = waitlistRepository;
        this.clock = clock;
    }

    /**
     * 대기 취소. WAITING → CANCELLED.
     * 동시 승격과의 race는 가드 조건 UPDATE로 차단. 별도 락 불필요.
     * (docs/STATE_TRANSITIONS.md 5.3, CONCURRENCY.md 7.3)
     */
    @Transactional
    public WaitlistCancelResponse cancel(AuthUser caller, Long waitlistId) {
        caller.requireStudent();

        Waitlist waitlist = waitlistRepository.findById(waitlistId)
            .orElseThrow(() -> ErrorCode.WAITLIST_NOT_FOUND.with(
                "대기를 찾을 수 없습니다. (id=" + waitlistId + ")"));

        if (!waitlist.isOwnedBy(caller.id())) {
            throw ErrorCode.NOT_WAITLIST_OWNER.asException();
        }

        int affected = waitlistRepository.cancelIfWaiting(
            waitlistId, OffsetDateTime.now(clock));

        if (affected == 0) {
            // race로 인해 이미 PROMOTED 또는 CANCELLED
            throw ErrorCode.INVALID_STATUS.with(
                "현재 상태에서는 대기 취소할 수 없습니다.");
        }

        Waitlist refreshed = waitlistRepository.findById(waitlistId).orElseThrow();
        return WaitlistCancelResponse.from(refreshed);
    }
}
