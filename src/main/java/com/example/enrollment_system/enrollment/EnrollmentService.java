package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.course.CourseStatus;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
import com.example.enrollment_system.enrollment.dto.EnrollmentSummary;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class EnrollmentService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final WaitlistRepository waitlistRepository;
    private final Clock clock;

    public EnrollmentService(CourseRepository courseRepository,
                             EnrollmentRepository enrollmentRepository,
                             WaitlistRepository waitlistRepository,
                             Clock clock) {
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.waitlistRepository = waitlistRepository;
        this.clock = clock;
    }

    /**
     * 수강 신청. 정원이 남으면 Enrollment(PENDING), 정원이 찼으면 Waitlist(WAITING) 생성.
     *
     * 동시성 흐름 (docs/STATE_TRANSITIONS.md 4.3 direct creation, CONCURRENCY.md 7.1):
     *   1. courses row SELECT FOR UPDATE
     *   2. course.status == OPEN 확인
     *   3. 활성 신청 / 활성 대기 중복 검증
     *   4. occupied_count < capacity 분기:
     *      - YES: Enrollment(PENDING) + occupied_count++  → ENROLLED
     *      - NO:  Waitlist(WAITING)                       → WAITLISTED
     */
    @Transactional
    public EnrollmentApplyResponse apply(AuthUser caller, Long courseId) {
        caller.requireStudent();

        Course course = courseRepository.findByIdForUpdate(courseId)
            .orElseThrow(() -> ErrorCode.COURSE_NOT_FOUND.with(
                "강의를 찾을 수 없습니다. (id=" + courseId + ")"));

        if (course.getStatus() != CourseStatus.OPEN) {
            throw ErrorCode.COURSE_NOT_OPEN.asException();
        }

        if (enrollmentRepository.existsActiveByCourseIdAndUserId(courseId, caller.id())) {
            throw ErrorCode.DUPLICATE_ENROLLMENT.asException();
        }
        if (waitlistRepository.existsWaitingByCourseIdAndUserId(courseId, caller.id())) {
            throw ErrorCode.DUPLICATE_WAITLIST.asException();
        }

        if (course.hasCapacity()) {
            Enrollment enrollment = enrollmentRepository.save(
                Enrollment.directApply(courseId, caller.id()));
            course.incrementOccupiedCount();
            courseRepository.save(course);
            return EnrollmentApplyResponse.enrolled(enrollment);
        } else {
            Waitlist waitlist = waitlistRepository.save(new Waitlist(courseId, caller.id()));
            int position = currentWaitlistPosition(waitlist);
            return EnrollmentApplyResponse.waitlisted(waitlist, position);
        }
    }

    /**
     * 결제 확정. PENDING → CONFIRMED.
     * 동시 취소와의 race는 가드 조건 UPDATE로 차단. 별도 락 불필요.
     * (docs/CONCURRENCY.md 7.2)
     */
    @Transactional
    public EnrollmentSummary confirm(AuthUser caller, Long enrollmentId) {
        caller.requireStudent();

        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
            .orElseThrow(() -> ErrorCode.ENROLLMENT_NOT_FOUND.with(
                "신청을 찾을 수 없습니다. (id=" + enrollmentId + ")"));

        if (!enrollment.isOwnedBy(caller.id())) {
            throw ErrorCode.NOT_ENROLLMENT_OWNER.asException();
        }

        int affected = enrollmentRepository.confirmIfPending(
            enrollmentId, OffsetDateTime.now(clock));

        if (affected == 0) {
            // race로 인해 이미 다른 상태로 전이됨 (CANCELLED or CONFIRMED)
            throw ErrorCode.INVALID_STATUS.with(
                "현재 상태에서는 결제 확정할 수 없습니다.");
        }

        Enrollment refreshed = enrollmentRepository.findById(enrollmentId).orElseThrow();
        return EnrollmentSummary.from(refreshed);
    }

    /**
     * 신규 생성된 waitlist의 현재 순번 (1부터).
     * 본인을 포함한 가장 오래된 WAITING 인덱스 + 1.
     */
    private int currentWaitlistPosition(Waitlist newlyCreated) {
        List<Waitlist> waiting = waitlistRepository.findOldestWaitingByCourseId(
            newlyCreated.getCourseId(),
            PageRequest.of(0, Integer.MAX_VALUE));
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().equals(newlyCreated.getId())) {
                return i + 1;
            }
        }
        return waiting.size();
    }
}
