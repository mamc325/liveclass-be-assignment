package com.example.enrollment_system.enrollment;

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
@Table(name = "enrollments")
public class Enrollment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "promoted_from_waitlist_id")
    private Long promotedFromWaitlistId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EnrollmentStatus status;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    protected Enrollment() {
        // JPA
    }

    private Enrollment(Long courseId, Long userId, Long promotedFromWaitlistId) {
        this.courseId = courseId;
        this.userId = userId;
        this.promotedFromWaitlistId = promotedFromWaitlistId;
        this.status = EnrollmentStatus.PENDING;
    }

    /** 일반 사용자가 직접 신청한 enrollment. */
    public static Enrollment directApply(Long courseId, Long userId) {
        return new Enrollment(courseId, userId, null);
    }

    /** 대기열에서 자동 승격되어 생성된 enrollment. */
    public static Enrollment promotedFrom(Long courseId, Long userId, Long waitlistId) {
        return new Enrollment(courseId, userId, waitlistId);
    }

    // ---------------------------------------------------------------
    // 상태 전이
    // ---------------------------------------------------------------

    public void confirm(OffsetDateTime now) {
        if (status != EnrollmentStatus.PENDING) {
            throw new IllegalStateException(
                "PENDING 상태에서만 결제 확정 가능합니다. 현재 상태=" + status);
        }
        this.status = EnrollmentStatus.CONFIRMED;
        this.confirmedAt = now;
    }

    public void cancel(OffsetDateTime now, int cancellationDeadlineDays) {
        if (status == EnrollmentStatus.CANCELLED) {
            throw new IllegalStateException("이미 취소된 신청입니다.");
        }
        if (status != EnrollmentStatus.PENDING && status != EnrollmentStatus.CONFIRMED) {
            throw new IllegalStateException("취소 가능한 상태가 아닙니다. 현재 상태=" + status);
        }
        if (status == EnrollmentStatus.CONFIRMED) {
            OffsetDateTime deadline = confirmedAt.plusDays(cancellationDeadlineDays);
            if (now.isAfter(deadline)) {
                throw new IllegalStateException(
                    "취소 가능 기간을 초과했습니다. deadline=" + deadline + ", now=" + now);
            }
        }
        this.status = EnrollmentStatus.CANCELLED;
        this.cancelledAt = now;
    }

    // ---------------------------------------------------------------
    // 조회 편의
    // ---------------------------------------------------------------

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isActive() {
        return status == EnrollmentStatus.PENDING || status == EnrollmentStatus.CONFIRMED;
    }

    public boolean isPromoted() {
        return promotedFromWaitlistId != null;
    }

    // ---------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------

    public Long getId() { return id; }
    public Long getCourseId() { return courseId; }
    public Long getUserId() { return userId; }
    public Long getPromotedFromWaitlistId() { return promotedFromWaitlistId; }
    public EnrollmentStatus getStatus() { return status; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
}
