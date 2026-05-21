package com.example.enrollment_system.course;

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

public interface CourseRepository extends JpaRepository<Course, Long> {

    /**
     * Course row를 PESSIMISTIC_WRITE로 잠그고 조회.
     * 수강 신청 / 취소 / 자동 승격 흐름에서 사용한다.
     * (docs/CONCURRENCY.md 6.1 참조)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Course c WHERE c.id = :id")
    Optional<Course> findByIdForUpdate(@Param("id") Long id);

    /**
     * 강의 목록 — 정렬은 호출자가 Pageable로 전달.
     * 인덱스 idx_courses_status_created (status, created_at DESC, id DESC) 활용을 의도.
     */
    Page<Course> findByStatus(CourseStatus status, Pageable pageable);

    /**
     * 강의 목록 (status 필터 없음).
     */
    Page<Course> findAllBy(Pageable pageable);

    // -----------------------------------------------------------------
    // 상태 전이 가드 조건 UPDATE
    // -----------------------------------------------------------------

    /**
     * DRAFT → OPEN 가드 조건 UPDATE.
     * 영향 0이면 호출자가 INVALID_TRANSITION으로 분기.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Course c
           SET c.status = com.example.enrollment_system.course.CourseStatus.OPEN,
               c.updatedAt = :now
         WHERE c.id = :id
           AND c.status = com.example.enrollment_system.course.CourseStatus.DRAFT
        """)
    int openIfDraft(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * OPEN → CLOSED 가드 조건 UPDATE.
     * 영향 0이면 호출자가 INVALID_TRANSITION으로 분기.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Course c
           SET c.status = com.example.enrollment_system.course.CourseStatus.CLOSED,
               c.updatedAt = :now
         WHERE c.id = :id
           AND c.status = com.example.enrollment_system.course.CourseStatus.OPEN
        """)
    int closeIfOpen(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
