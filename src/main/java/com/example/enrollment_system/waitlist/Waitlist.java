package com.example.enrollment_system.waitlist;

import com.example.enrollment_system.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "waitlists")
public class Waitlist extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WaitlistStatus status;

    @Column(name = "promoted_at")
    private OffsetDateTime promotedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    protected Waitlist() {
        // JPA
    }

    public Waitlist(Long courseId, Long userId) {
        this.courseId = courseId;
        this.userId = userId;
        this.status = WaitlistStatus.WAITING;
    }

    // ---------------------------------------------------------------
    // 상태 전이
    // ---------------------------------------------------------------

    public void promote(OffsetDateTime now) {
        if (status != WaitlistStatus.WAITING) {
            throw new IllegalStateException(
                "WAITING 상태에서만 승격 가능합니다. 현재 상태=" + status);
        }
        this.status = WaitlistStatus.PROMOTED;
        this.promotedAt = now;
    }

    public void cancel(OffsetDateTime now) {
        if (status != WaitlistStatus.WAITING) {
            throw new IllegalStateException(
                "WAITING 상태에서만 취소 가능합니다. 현재 상태=" + status);
        }
        this.status = WaitlistStatus.CANCELLED;
        this.cancelledAt = now;
    }

    // ---------------------------------------------------------------
    // 조회 편의
    // ---------------------------------------------------------------

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isWaiting() {
        return status == WaitlistStatus.WAITING;
    }

    // ---------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------

    public Long getId() { return id; }
    public Long getCourseId() { return courseId; }
    public Long getUserId() { return userId; }
    public WaitlistStatus getStatus() { return status; }
    public OffsetDateTime getPromotedAt() { return promotedAt; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
}
