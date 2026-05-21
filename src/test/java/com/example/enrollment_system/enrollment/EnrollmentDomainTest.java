package com.example.enrollment_system.enrollment;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnrollmentDomainTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final int DEADLINE_DAYS = 7;

    // E-U-1: PENDING 상태에서 confirm() → CONFIRMED 전이
    @Test
    void confirm은_PENDING_상태에서_CONFIRMED로_전이된다() {
        Enrollment e = Enrollment.directApply(1L, 10L);
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 21, 10, 0, 0, 0, KST);

        e.confirm(now);

        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(e.getConfirmedAt()).isEqualTo(now);
    }

    // E-U-2: CONFIRMED/CANCELLED 상태에서 confirm() → IllegalStateException
    @Test
    void confirm은_CONFIRMED_상태에서_호출하면_IllegalStateException() {
        Enrollment e = Enrollment.directApply(1L, 10L);
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 21, 10, 0, 0, 0, KST);
        e.confirm(now);

        assertThatThrownBy(() -> e.confirm(now))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirm은_CANCELLED_상태에서_호출하면_IllegalStateException() {
        Enrollment e = Enrollment.directApply(1L, 10L);
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 21, 10, 0, 0, 0, KST);
        e.cancel(now, DEADLINE_DAYS);

        assertThatThrownBy(() -> e.confirm(now))
            .isInstanceOf(IllegalStateException.class);
    }

    // E-U-3: PENDING 상태에서 cancel() → CANCELLED 전이
    @Test
    void cancel은_PENDING_상태에서_CANCELLED로_전이된다() {
        Enrollment e = Enrollment.directApply(1L, 10L);
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 21, 10, 0, 0, 0, KST);

        e.cancel(now, DEADLINE_DAYS);

        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(e.getCancelledAt()).isEqualTo(now);
    }

    // E-U-4: CONFIRMED + 기간 내 → CANCELLED
    @Test
    void cancel은_CONFIRMED_상태에서_기간_내라면_CANCELLED로_전이된다() {
        Enrollment e = Enrollment.directApply(1L, 10L);
        OffsetDateTime confirmedAt = OffsetDateTime.of(2026, 5, 14, 10, 0, 0, 0, KST);
        e.confirm(confirmedAt);

        // 정확히 경계 시각: confirmedAt + 7일
        OffsetDateTime boundary = OffsetDateTime.of(2026, 5, 21, 10, 0, 0, 0, KST);
        e.cancel(boundary, DEADLINE_DAYS);

        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(e.getCancelledAt()).isEqualTo(boundary);
    }

    // E-U-5: CONFIRMED + 기간 초과 → IllegalStateException
    @Test
    void cancel은_CONFIRMED_기간_초과면_IllegalStateException() {
        Enrollment e = Enrollment.directApply(1L, 10L);
        OffsetDateTime confirmedAt = OffsetDateTime.of(2026, 5, 14, 10, 0, 0, 0, KST);
        e.confirm(confirmedAt);

        // 경계 + 1초
        OffsetDateTime overDeadline = OffsetDateTime.of(2026, 5, 21, 10, 0, 1, 0, KST);
        assertThatThrownBy(() -> e.cancel(overDeadline, DEADLINE_DAYS))
            .isInstanceOf(IllegalStateException.class);

        // 상태 불변 확인
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(e.getCancelledAt()).isNull();
    }

    // E-U-6: CANCELLED 상태에서 cancel() → IllegalStateException
    @Test
    void cancel은_CANCELLED_상태에서_호출하면_IllegalStateException() {
        Enrollment e = Enrollment.directApply(1L, 10L);
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 21, 10, 0, 0, 0, KST);
        e.cancel(now, DEADLINE_DAYS);

        assertThatThrownBy(() -> e.cancel(now, DEADLINE_DAYS))
            .isInstanceOf(IllegalStateException.class);
    }

    // E-U-7: promoted creation 시 promotedFromWaitlistId 설정됨
    @Test
    void promotedFrom은_promotedFromWaitlistId를_설정한다() {
        Enrollment e = Enrollment.promotedFrom(1L, 10L, 99L);

        assertThat(e.getPromotedFromWaitlistId()).isEqualTo(99L);
        assertThat(e.isPromoted()).isTrue();
        assertThat(e.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
    }

    @Test
    void directApply는_promotedFromWaitlistId가_null이다() {
        Enrollment e = Enrollment.directApply(1L, 10L);

        assertThat(e.getPromotedFromWaitlistId()).isNull();
        assertThat(e.isPromoted()).isFalse();
    }
}
