package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.course.CourseStatus;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
import com.example.enrollment_system.enrollment.dto.EnrollmentCancelResponse;
import com.example.enrollment_system.enrollment.dto.EnrollmentSummary;
import com.example.enrollment_system.enrollment.dto.PromotedInfo;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

    @PersistenceContext
    private EntityManager entityManager;

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
     * 수강 취소.
     *
     * 흐름 (docs/STATE_TRANSITIONS.md 4.3 cancel, CONCURRENCY.md 7.2):
     *   1. 사전 read: enrollment non-locking → courseId 확인 + 소유권 사전 검증
     *   2. courses row SELECT FOR UPDATE
     *   3. enrollments row SELECT FOR UPDATE (락 순서: course → enrollment)
     *   4. 락 후 권위 검증: ALREADY_CANCELLED / INVALID_STATUS / CANCEL_DEADLINE_EXCEEDED 분기
     *   5. Entity.cancel() → status=CANCELLED, cancelled_at 기록
     *   6. occupied_count -= 1
     *   7. course.status == OPEN이면 자동 승격 (fallback 포함) → 성공 시 occupied_count += 1
     */
    @Transactional
    public EnrollmentCancelResponse cancel(AuthUser caller, Long enrollmentId) {
        caller.requireStudent();

        // 1. 사전 read — courseId 확인 + 소유권 사전 검증
        Enrollment preview = enrollmentRepository.findById(enrollmentId)
            .orElseThrow(() -> ErrorCode.ENROLLMENT_NOT_FOUND.with(
                "신청을 찾을 수 없습니다. (id=" + enrollmentId + ")"));
        if (!preview.isOwnedBy(caller.id())) {
            throw ErrorCode.NOT_ENROLLMENT_OWNER.asException();
        }

        // 2. course 락
        Course course = courseRepository.findByIdForUpdate(preview.getCourseId())
            .orElseThrow(() -> ErrorCode.COURSE_NOT_FOUND.asException());

        // 3. enrollment 락 + 재조회
        // 사전 read의 1차 캐시가 stale일 수 있으므로 refresh로 DB의 최신 상태 강제 동기화.
        // (CONCURRENCY.md 6.5 — native UPDATE/PESSIMISTIC 후 stale entity 회피)
        Enrollment enrollment = enrollmentRepository.findByIdForUpdate(enrollmentId)
            .orElseThrow(() -> ErrorCode.ENROLLMENT_NOT_FOUND.asException());
        entityManager.refresh(enrollment);

        // 4. 권위 검증
        if (enrollment.getStatus() == EnrollmentStatus.CANCELLED) {
            throw ErrorCode.ALREADY_CANCELLED.asException();
        }
        if (enrollment.getStatus() != EnrollmentStatus.PENDING
                && enrollment.getStatus() != EnrollmentStatus.CONFIRMED) {
            throw ErrorCode.INVALID_STATUS.asException();
        }

        OffsetDateTime now = OffsetDateTime.now(clock);

        // 5. CONFIRMED 기간 검증 (Entity 호출 전 명시적 검증으로 ErrorCode 분기 명확화)
        if (enrollment.getStatus() == EnrollmentStatus.CONFIRMED) {
            OffsetDateTime deadline = enrollment.getConfirmedAt()
                .plusDays(course.getCancellationDeadlineDays());
            if (now.isAfter(deadline)) {
                throw ErrorCode.CANCEL_DEADLINE_EXCEEDED.asException();
            }
        }

        // 6. 상태 전이
        enrollment.cancel(now, course.getCancellationDeadlineDays());
        enrollmentRepository.save(enrollment);

        // 7. occupied_count 감소
        course.decrementOccupiedCount();

        // 8. course.status == OPEN이면 자동 승격
        PromotedInfo promoted = null;
        if (course.getStatus() == CourseStatus.OPEN) {
            promoted = tryAutoPromote(course, now);
            if (promoted != null) {
                course.incrementOccupiedCount();
            }
        }
        courseRepository.save(course);

        return new EnrollmentCancelResponse(
            EnrollmentSummary.from(enrollment),
            promoted
        );
    }

    /**
     * 자동 승격 시도. fallback 포함.
     *
     * 가장 오래된 WAITING부터 차례로 시도. 가드 조건 UPDATE 영향 0이면 (동시 대기 취소로 인한 race)
     * 다음 후보를 시도. 모두 실패하면 승격 없이 null 반환.
     * (docs/CONCURRENCY.md 6.1.1)
     */
    private PromotedInfo tryAutoPromote(Course course, OffsetDateTime now) {
        int batch = 50;
        List<Waitlist> candidates = waitlistRepository.findOldestWaitingByCourseId(
            course.getId(), PageRequest.of(0, batch));

        for (Waitlist candidate : candidates) {
            int promotedRows = waitlistRepository.promoteIfWaiting(candidate.getId(), now);
            if (promotedRows == 1) {
                Enrollment newEnrollment = enrollmentRepository.save(
                    Enrollment.promotedFrom(course.getId(), candidate.getUserId(), candidate.getId()));
                return new PromotedInfo(
                    newEnrollment.getId(),
                    candidate.getId(),
                    candidate.getUserId()
                );
            }
            // 영향 0 → race로 이미 CANCELLED. 다음 후보 시도.
        }
        return null;
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
