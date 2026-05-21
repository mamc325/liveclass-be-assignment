package com.example.enrollment_system.waitlist;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitlistDomainTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW =
        OffsetDateTime.of(2026, 5, 21, 10, 0, 0, 0, KST);

    // W-U-1: WAITING 상태에서 promote() → PROMOTED 전이
    @Test
    void promote는_WAITING_상태에서_PROMOTED로_전이된다() {
        Waitlist w = new Waitlist(1L, 10L);

        w.promote(NOW);

        assertThat(w.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(w.getPromotedAt()).isEqualTo(NOW);
    }

    // W-U-2: PROMOTED/CANCELLED 상태에서 promote() → IllegalStateException
    @Test
    void promote는_PROMOTED_상태에서_호출하면_IllegalStateException() {
        Waitlist w = new Waitlist(1L, 10L);
        w.promote(NOW);

        assertThatThrownBy(() -> w.promote(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promote는_CANCELLED_상태에서_호출하면_IllegalStateException() {
        Waitlist w = new Waitlist(1L, 10L);
        w.cancel(NOW);

        assertThatThrownBy(() -> w.promote(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    // W-U-3: WAITING 상태에서 cancel() → CANCELLED 전이
    @Test
    void cancel은_WAITING_상태에서_CANCELLED로_전이된다() {
        Waitlist w = new Waitlist(1L, 10L);

        w.cancel(NOW);

        assertThat(w.getStatus()).isEqualTo(WaitlistStatus.CANCELLED);
        assertThat(w.getCancelledAt()).isEqualTo(NOW);
    }

    // W-U-4: PROMOTED/CANCELLED 상태에서 cancel() → IllegalStateException
    @Test
    void cancel은_PROMOTED_상태에서_호출하면_IllegalStateException() {
        Waitlist w = new Waitlist(1L, 10L);
        w.promote(NOW);

        assertThatThrownBy(() -> w.cancel(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel은_CANCELLED_상태에서_호출하면_IllegalStateException() {
        Waitlist w = new Waitlist(1L, 10L);
        w.cancel(NOW);

        assertThatThrownBy(() -> w.cancel(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    // 초기 상태 검증
    @Test
    void 생성_시_초기_상태는_WAITING이고_타임스탬프는_null이다() {
        Waitlist w = new Waitlist(1L, 10L);

        assertThat(w.getStatus()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(w.getPromotedAt()).isNull();
        assertThat(w.getCancelledAt()).isNull();
        assertThat(w.isWaiting()).isTrue();
    }
}
