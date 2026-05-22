package com.example.enrollment_system.me;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.auth.CurrentUser;
import com.example.enrollment_system.common.web.PageResponse;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.enrollment.Enrollment;
import com.example.enrollment_system.enrollment.EnrollmentRepository;
import com.example.enrollment_system.enrollment.EnrollmentStatus;
import com.example.enrollment_system.me.dto.MyEnrollmentRow;
import com.example.enrollment_system.me.dto.MyWaitlistRow;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import com.example.enrollment_system.waitlist.WaitlistStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final EnrollmentRepository enrollmentRepository;
    private final WaitlistRepository waitlistRepository;
    private final CourseRepository courseRepository;

    public MeController(EnrollmentRepository enrollmentRepository,
                        WaitlistRepository waitlistRepository,
                        CourseRepository courseRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.waitlistRepository = waitlistRepository;
        this.courseRepository = courseRepository;
    }

    /** GET /api/me/enrollments — 내 수강 신청 목록 (STUDENT). */
    @Transactional(readOnly = true)
    @GetMapping("/enrollments")
    public PageResponse<MyEnrollmentRow> myEnrollments(
            @CurrentUser AuthUser caller,
            @RequestParam(required = false) EnrollmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        caller.requireStudent();
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Enrollment> result = (status != null)
            ? enrollmentRepository.findByUserIdAndStatus(caller.id(), status, pageable)
            : enrollmentRepository.findByUserId(caller.id(), pageable);
        return PageResponse.from(result, this::toRow);
    }

    /** GET /api/me/waitlists — 내 대기 목록 (STUDENT). */
    @Transactional(readOnly = true)
    @GetMapping("/waitlists")
    public PageResponse<MyWaitlistRow> myWaitlists(
            @CurrentUser AuthUser caller,
            @RequestParam(required = false) WaitlistStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        caller.requireStudent();
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Waitlist> result = (status != null)
            ? waitlistRepository.findByUserIdAndStatus(caller.id(), status, pageable)
            : waitlistRepository.findByUserId(caller.id(), pageable);
        return PageResponse.from(result, this::toRow);
    }

    // ---- Entity → DTO 매핑 (course 정보 조인) ----

    private MyEnrollmentRow toRow(Enrollment e) {
        var course = courseRepository.findById(e.getCourseId()).orElse(null);
        return new MyEnrollmentRow(
            e.getId(),
            e.getCourseId(),
            course != null ? course.getTitle() : "unknown",
            course != null ? course.getStatus().name() : null,
            e.getStatus(),
            e.getPromotedFromWaitlistId(),
            e.getConfirmedAt(),
            e.getCancelledAt(),
            e.getCreatedAt()
        );
    }

    private MyWaitlistRow toRow(Waitlist w) {
        var course = courseRepository.findById(w.getCourseId()).orElse(null);
        Integer position = null;
        if (w.getStatus() == WaitlistStatus.WAITING) {
            // 본인 대기열에서의 현재 순번 계산
            var waitings = waitlistRepository.findOldestWaitingByCourseId(
                w.getCourseId(), PageRequest.of(0, Integer.MAX_VALUE));
            for (int i = 0; i < waitings.size(); i++) {
                if (waitings.get(i).getId().equals(w.getId())) {
                    position = i + 1;
                    break;
                }
            }
        }
        return new MyWaitlistRow(
            w.getId(),
            w.getCourseId(),
            course != null ? course.getTitle() : "unknown",
            w.getStatus(),
            position,
            w.getPromotedAt(),
            w.getCancelledAt(),
            w.getCreatedAt()
        );
    }
}
