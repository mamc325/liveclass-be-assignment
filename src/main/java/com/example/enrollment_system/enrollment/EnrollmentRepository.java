package com.example.enrollment_system.enrollment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    // -----------------------------------------------------------------
    // 락 / 조회
    // -----------------------------------------------------------------

    /**
     * Enrollment row를 PESSIMISTIC_WRITE로 잠그고 조회.
     * 취소 흐름에서 course lock 다음에 호출 (락 순서: course → enrollment).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Enrollment e WHERE e.id = :id")
    Optional<Enrollment> findByIdForUpdate(@Param("id") Long id);

    /**
     * 강의별 활성 신청 수 (정원 점유 카운트).
     * occupied_count 단일 진실 원천(SSoT)은 Course.occupiedCount지만,
     * 검증/감사용으로 별도 집계 쿼리 제공.
     */
    @Query("""
        SELECT COUNT(e) FROM Enrollment e
        WHERE e.courseId = :courseId
          AND e.status IN (com.example.enrollment_system.enrollment.EnrollmentStatus.PENDING,
                           com.example.enrollment_system.enrollment.EnrollmentStatus.CONFIRMED)
        """)
    long countActiveByCourseId(@Param("courseId") Long courseId);

    /**
     * 동일 사용자 / 동일 강의 활성 신청 존재 여부 (중복 검증용).
     */
    @Query("""
        SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END
        FROM Enrollment e
        WHERE e.courseId = :courseId
          AND e.userId = :userId
          AND e.status IN (com.example.enrollment_system.enrollment.EnrollmentStatus.PENDING,
                           com.example.enrollment_system.enrollment.EnrollmentStatus.CONFIRMED)
        """)
    boolean existsActiveByCourseIdAndUserId(@Param("courseId") Long courseId,
                                            @Param("userId") Long userId);

    // -----------------------------------------------------------------
    // 가드 조건 UPDATE (race 차단)
    // -----------------------------------------------------------------

    /**
     * PENDING → CONFIRMED 가드 조건 UPDATE.
     * 영향 0이면 호출자가 INVALID_STATUS로 분기 (이미 취소되었거나 확정됨).
     * (docs/STATE_TRANSITIONS.md 4.3 confirm, docs/CONCURRENCY.md 7.2)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Enrollment e
           SET e.status = com.example.enrollment_system.enrollment.EnrollmentStatus.CONFIRMED,
               e.confirmedAt = :now,
               e.updatedAt = :now
         WHERE e.id = :id
           AND e.status = com.example.enrollment_system.enrollment.EnrollmentStatus.PENDING
        """)
    int confirmIfPending(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * PENDING/CONFIRMED → CANCELLED 가드 조건 UPDATE.
     * 영향 0이면 호출자가 재조회해 ALREADY_CANCELLED / INVALID_STATUS 분기.
     * (docs/STATE_TRANSITIONS.md 4.3 cancel)
     *
     * 참고: 취소 가능 기간 검증과 자동 승격은 서비스 레이어가 처리.
     * 본 쿼리는 상태 전이만 책임.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Enrollment e
           SET e.status = com.example.enrollment_system.enrollment.EnrollmentStatus.CANCELLED,
               e.cancelledAt = :now,
               e.updatedAt = :now
         WHERE e.id = :id
           AND e.status IN (com.example.enrollment_system.enrollment.EnrollmentStatus.PENDING,
                            com.example.enrollment_system.enrollment.EnrollmentStatus.CONFIRMED)
        """)
    int cancelIfActive(@Param("id") Long id, @Param("now") OffsetDateTime now);

    // -----------------------------------------------------------------
    // 목록 조회
    // -----------------------------------------------------------------

    /**
     * 내 수강 신청 목록 (사용자 기준).
     * 인덱스 idx_enrollments_user_created (user_id, created_at DESC, id DESC) 활용 의도.
     */
    Page<Enrollment> findByUserId(Long userId, Pageable pageable);

    Page<Enrollment> findByUserIdAndStatus(Long userId, EnrollmentStatus status, Pageable pageable);

    /**
     * 강의별 수강생 목록 (크리에이터 전용).
     * 인덱스 idx_enrollments_course_status_created 활용 의도.
     */
    Page<Enrollment> findByCourseId(Long courseId, Pageable pageable);

    Page<Enrollment> findByCourseIdAndStatus(Long courseId, EnrollmentStatus status, Pageable pageable);
}
