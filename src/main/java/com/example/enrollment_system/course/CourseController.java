package com.example.enrollment_system.course;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.auth.CurrentUser;
import com.example.enrollment_system.common.web.PageResponse;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.course.dto.CourseEnrollmentRow;
import com.example.enrollment_system.course.dto.CourseResponse;
import com.example.enrollment_system.course.dto.CourseStatusResponse;
import com.example.enrollment_system.course.dto.CourseSummaryResponse;
import com.example.enrollment_system.course.dto.CourseWaitlistRow;
import com.example.enrollment_system.enrollment.Enrollment;
import com.example.enrollment_system.enrollment.EnrollmentStatus;
import com.example.enrollment_system.user.UserRepository;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;
    private final UserRepository userRepository;

    public CourseController(CourseService courseService, UserRepository userRepository) {
        this.courseService = courseService;
        this.userRepository = userRepository;
    }

    /** POST /api/courses — 강의 등록 (CREATOR). */
    @PostMapping
    public ResponseEntity<CourseResponse> register(
            @CurrentUser AuthUser caller,
            @Valid @RequestBody CourseCreateRequest request) {
        Course course = courseService.register(caller, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CourseResponse.from(course));
    }

    /** POST /api/courses/{id}/open — 강의 모집 시작 (DRAFT → OPEN). */
    @PostMapping("/{id}/open")
    public CourseStatusResponse open(
            @CurrentUser AuthUser caller,
            @PathVariable Long id) {
        Course opened = courseService.open(caller, id);
        return CourseStatusResponse.from(opened);
    }

    /** POST /api/courses/{id}/close — 강의 모집 마감 (OPEN → CLOSED). */
    @PostMapping("/{id}/close")
    public CourseStatusResponse close(
            @CurrentUser AuthUser caller,
            @PathVariable Long id) {
        Course closed = courseService.close(caller, id);
        return CourseStatusResponse.from(closed);
    }

    /** GET /api/courses?status=&page=&size= — 강의 목록 (ANY). */
    @GetMapping
    public PageResponse<CourseSummaryResponse> list(
            @CurrentUser AuthUser caller,
            @RequestParam(required = false) CourseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Course> courses = courseService.list(status, pageable);
        return PageResponse.from(courses, CourseSummaryResponse::from);
    }

    /** GET /api/courses/{id} — 강의 상세 (ANY). */
    @GetMapping("/{id}")
    public CourseResponse detail(@CurrentUser AuthUser caller, @PathVariable Long id) {
        Course course = courseService.detail(id);
        return CourseResponse.from(course);
    }

    /** GET /api/courses/{id}/enrollments — 강의별 수강생 (CREATOR, 본인 강의). */
    @GetMapping("/{id}/enrollments")
    public PageResponse<CourseEnrollmentRow> listEnrollments(
            @CurrentUser AuthUser caller,
            @PathVariable Long id,
            @RequestParam(required = false) EnrollmentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Enrollment> enrollments = courseService.listEnrollmentsByCourse(caller, id, status, pageable);
        return PageResponse.from(enrollments,
            e -> CourseEnrollmentRow.from(e, userName(e.getUserId())));
    }

    /**
     * GET /api/courses/{id}/waitlists — 강의별 대기자 (CREATOR, 본인 강의).
     * WAITING은 created_at ASC (FIFO), 그 외는 DESC.
     */
    @GetMapping("/{id}/waitlists")
    public PageResponse<CourseWaitlistRow> listWaitlists(
            @CurrentUser AuthUser caller,
            @PathVariable Long id,
            @RequestParam(required = false) WaitlistStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Sort sort = status == WaitlistStatus.WAITING
            ? Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))
            : Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), sort);
        Page<Waitlist> waitlists = courseService.listWaitlistsByCourse(caller, id, status, pageable);
        return PageResponse.from(waitlists,
            w -> CourseWaitlistRow.from(w, userName(w.getUserId())));
    }

    private String userName(Long userId) {
        return userRepository.findById(userId)
            .map(u -> u.getName())
            .orElse("unknown");
    }
}
