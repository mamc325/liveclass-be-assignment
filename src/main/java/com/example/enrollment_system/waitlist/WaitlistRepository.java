package com.example.enrollment_system.waitlist;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface WaitlistRepository extends JpaRepository<Waitlist, Long> {

    // -----------------------------------------------------------------
    // 조회 / 중복 검증
    // -----------------------------------------------------------------

    /**
     * 동일 사용자 / 동일 강의 활성 대기 존재 여부 (중복 검증용).
     */
    @Query("""
        SELECT CASE WHEN COUNT(w) > 0 THEN true ELSE false END
        FROM Waitlist w
        WHERE w.courseId = :courseId
          AND w.userId = :userId
          AND w.status = com.example.enrollment_system.waitlist.WaitlistStatus.WAITING
        """)
    boolean existsWaitingByCourseIdAndUserId(@Param("courseId") Long courseId,
                                             @Param("userId") Long userId);

    /**
     * 자동 승격 후보 조회 — 가장 오래된 WAITING N건.
     *
     * 자동 승격 시 가드 UPDATE가 실패(다른 트랜잭션이 cancel)하면 fallback으로
     * 다음 후보를 시도해야 하므로 LIMIT 1 단건이 아니라 여러 건을 받아두는 편이 안전.
     * 호출자가 Pageable로 LIMIT 제어.
     *
     * 인덱스 idx_waitlists_course_status_created (course_id, status, created_at ASC, id ASC) 활용.
     */
    @Query("""
        SELECT w FROM Waitlist w
        WHERE w.courseId = :courseId
          AND w.status = com.example.enrollment_system.waitlist.WaitlistStatus.WAITING
        ORDER BY w.createdAt ASC, w.id ASC
        """)
    List<Waitlist> findOldestWaitingByCourseId(@Param("courseId") Long courseId,
                                               Pageable pageable);

    // -----------------------------------------------------------------
    // 가드 조건 UPDATE (race 차단)
    // -----------------------------------------------------------------

    /**
     * WAITING → PROMOTED 가드 조건 UPDATE.
     * 영향 0이면 호출자(자동 승격 로직)가 다음 후보로 fallback.
     * (docs/STATE_TRANSITIONS.md 5.3 promote, docs/CONCURRENCY.md 6.1.1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Waitlist w
           SET w.status = com.example.enrollment_system.waitlist.WaitlistStatus.PROMOTED,
               w.promotedAt = :now,
               w.updatedAt = :now
         WHERE w.id = :id
           AND w.status = com.example.enrollment_system.waitlist.WaitlistStatus.WAITING
        """)
    int promoteIfWaiting(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * WAITING → CANCELLED 가드 조건 UPDATE.
     * 영향 0이면 호출자가 INVALID_STATUS로 분기 (이미 PROMOTED/CANCELLED).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Waitlist w
           SET w.status = com.example.enrollment_system.waitlist.WaitlistStatus.CANCELLED,
               w.cancelledAt = :now,
               w.updatedAt = :now
         WHERE w.id = :id
           AND w.status = com.example.enrollment_system.waitlist.WaitlistStatus.WAITING
        """)
    int cancelIfWaiting(@Param("id") Long id, @Param("now") OffsetDateTime now);

    // -----------------------------------------------------------------
    // 목록 조회
    // -----------------------------------------------------------------

    /**
     * 내 대기 목록 (사용자 기준). 정렬은 호출자가 Pageable로.
     */
    Page<Waitlist> findByUserId(Long userId, Pageable pageable);

    Page<Waitlist> findByUserIdAndStatus(Long userId, WaitlistStatus status, Pageable pageable);

    /**
     * 강의별 대기자 목록 (크리에이터 전용).
     */
    Page<Waitlist> findByCourseId(Long courseId, Pageable pageable);

    Page<Waitlist> findByCourseIdAndStatus(Long courseId, WaitlistStatus status, Pageable pageable);
}
