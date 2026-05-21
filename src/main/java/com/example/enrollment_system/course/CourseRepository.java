package com.example.enrollment_system.course;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
