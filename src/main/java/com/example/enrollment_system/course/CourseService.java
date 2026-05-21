package com.example.enrollment_system.course;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.Enrollment;
import com.example.enrollment_system.enrollment.EnrollmentRepository;
import com.example.enrollment_system.enrollment.EnrollmentStatus;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import com.example.enrollment_system.waitlist.WaitlistStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

@Service
public class CourseService {

    private static final int DEFAULT_CANCELLATION_DEADLINE_DAYS = 7;

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final WaitlistRepository waitlistRepository;
    private final Clock clock;

    public CourseService(CourseRepository courseRepository,
                         EnrollmentRepository enrollmentRepository,
                         WaitlistRepository waitlistRepository,
                         Clock clock) {
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.waitlistRepository = waitlistRepository;
        this.clock = clock;
    }

    /**
     * 강의 등록. 호출자는 CREATOR 권한이어야 한다.
     * status는 항상 DRAFT, occupiedCount는 0으로 초기화.
     */
    @Transactional
    public Course register(AuthUser caller, CourseCreateRequest req) {
        caller.requireCreator();

        int deadline = Objects.requireNonNullElse(
            req.cancellationDeadlineDays(), DEFAULT_CANCELLATION_DEADLINE_DAYS);

        Course course = new Course(
            caller.id(),
            req.title(),
            req.description(),
            req.price(),
            req.capacity(),
            req.startDate(),
            req.endDate(),
            deadline
        );
        return courseRepository.save(course);
    }

    /**
     * 강의 모집 시작 (DRAFT → OPEN).
     */
    @Transactional
    public Course open(AuthUser caller, Long courseId) {
        caller.requireCreator();
        Course course = courseRepository.findById(courseId)
            .orElseThrow(() -> ErrorCode.COURSE_NOT_FOUND.with(
                "강의를 찾을 수 없습니다. (id=" + courseId + ")"));
        if (!course.isOwnedBy(caller.id())) {
            throw ErrorCode.NOT_COURSE_OWNER.asException();
        }
        int affected = courseRepository.openIfDraft(courseId, OffsetDateTime.now(clock));
        if (affected == 0) {
            throw ErrorCode.INVALID_TRANSITION.with(
                "DRAFT 상태에서만 모집을 시작할 수 있습니다.");
        }
        return courseRepository.findById(courseId).orElseThrow();
    }

    /**
     * 강의 모집 마감 (OPEN → CLOSED).
     */
    @Transactional
    public Course close(AuthUser caller, Long courseId) {
        caller.requireCreator();
        Course course = courseRepository.findById(courseId)
            .orElseThrow(() -> ErrorCode.COURSE_NOT_FOUND.with(
                "강의를 찾을 수 없습니다. (id=" + courseId + ")"));
        if (!course.isOwnedBy(caller.id())) {
            throw ErrorCode.NOT_COURSE_OWNER.asException();
        }
        int affected = courseRepository.closeIfOpen(courseId, OffsetDateTime.now(clock));
        if (affected == 0) {
            throw ErrorCode.INVALID_TRANSITION.with(
                "OPEN 상태에서만 모집을 마감할 수 있습니다.");
        }
        return courseRepository.findById(courseId).orElseThrow();
    }

    /**
     * 강의 목록 조회 (status 필터 선택).
     * 정렬은 호출자(컨트롤러)가 Pageable로 전달.
     */
    @Transactional(readOnly = true)
    public Page<Course> list(CourseStatus statusFilter, Pageable pageable) {
        if (statusFilter != null) {
            return courseRepository.findByStatus(statusFilter, pageable);
        }
        return courseRepository.findAllBy(pageable);
    }

    /**
     * 강의 상세 조회.
     */
    @Transactional(readOnly = true)
    public Course detail(Long courseId) {
        return courseRepository.findById(courseId)
            .orElseThrow(() -> ErrorCode.COURSE_NOT_FOUND.with(
                "강의를 찾을 수 없습니다. (id=" + courseId + ")"));
    }

    /**
     * 강의별 수강 신청 목록 (크리에이터 전용, 본인 강의만).
     */
    @Transactional(readOnly = true)
    public Page<Enrollment> listEnrollmentsByCourse(
            AuthUser caller, Long courseId,
            EnrollmentStatus statusFilter, Pageable pageable) {
        caller.requireCreator();
        Course course = courseRepository.findById(courseId)
            .orElseThrow(() -> ErrorCode.COURSE_NOT_FOUND.with(
                "강의를 찾을 수 없습니다. (id=" + courseId + ")"));
        if (!course.isOwnedBy(caller.id())) {
            throw ErrorCode.NOT_COURSE_OWNER.asException();
        }
        if (statusFilter != null) {
            return enrollmentRepository.findByCourseIdAndStatus(courseId, statusFilter, pageable);
        }
        return enrollmentRepository.findByCourseId(courseId, pageable);
    }

    /**
     * 강의별 대기자 목록 (크리에이터 전용, 본인 강의만).
     */
    @Transactional(readOnly = true)
    public Page<Waitlist> listWaitlistsByCourse(
            AuthUser caller, Long courseId,
            WaitlistStatus statusFilter, Pageable pageable) {
        caller.requireCreator();
        Course course = courseRepository.findById(courseId)
            .orElseThrow(() -> ErrorCode.COURSE_NOT_FOUND.with(
                "강의를 찾을 수 없습니다. (id=" + courseId + ")"));
        if (!course.isOwnedBy(caller.id())) {
            throw ErrorCode.NOT_COURSE_OWNER.asException();
        }
        if (statusFilter != null) {
            return waitlistRepository.findByCourseIdAndStatus(courseId, statusFilter, pageable);
        }
        return waitlistRepository.findByCourseId(courseId, pageable);
    }
}
