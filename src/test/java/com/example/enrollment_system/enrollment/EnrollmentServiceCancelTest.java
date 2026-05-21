package com.example.enrollment_system.enrollment;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.error.DomainException;
import com.example.enrollment_system.common.error.ErrorCode;
import com.example.enrollment_system.course.Course;
import com.example.enrollment_system.course.CourseRepository;
import com.example.enrollment_system.course.CourseService;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.enrollment.dto.EnrollmentApplyResponse;
import com.example.enrollment_system.enrollment.dto.EnrollmentCancelResponse;
import com.example.enrollment_system.enrollment.dto.EnrollmentSummary;
import com.example.enrollment_system.user.UserRole;
import com.example.enrollment_system.waitlist.Waitlist;
import com.example.enrollment_system.waitlist.WaitlistRepository;
import com.example.enrollment_system.waitlist.WaitlistStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * EnrollmentService confirm + cancel + 자동 승격 + fallback 통합 테스트.
 *
 * 시나리오 매핑 (TEST_SCENARIOS 7.2):
 *  - S-E-6: confirm 정상
 *  - S-E-7: cancel PENDING + 대기자 없음
 *  - S-E-8: cancel PENDING + 대기자 있음(OPEN) → 자동 승격
 *  - S-E-9: cancel CONFIRMED 기간 내 + 대기자 있음
 *  - S-E-10: cancel CONFIRMED 기간 내 + 대기자 없음
 *  - S-E-11: cancel CONFIRMED 기간 초과
 *  - S-E-12: CLOSED 강의 cancel
 *  - S-E-13: CLOSED 강의 confirm
 *  - S-P-1: 모든 WAITING이 CANCELLED → 승격 없음
 *  - S-P-2: 일부 CANCELLED → 다음 후보로 fallback 승격
 *
 * Clock mock 없이 cancellationDeadlineDays=0으로 기간 초과 시뮬레이션 (TEST_SCENARIOS 12 정책).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class EnrollmentServiceCancelTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired EnrollmentService enrollmentService;
    @Autowired CourseService courseService;
    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentRepository enrollmentRepository;
    @Autowired WaitlistRepository waitlistRepository;
    @Autowired JdbcTemplate jdbc;

    private final AuthUser creator = new AuthUser(1L, UserRole.CREATOR);
    private final AuthUser student10 = new AuthUser(10L, UserRole.STUDENT);
    private final AuthUser student11 = new AuthUser(11L, UserRole.STUDENT);
    private final AuthUser student12 = new AuthUser(12L, UserRole.STUDENT);

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE TABLE enrollments, waitlists, courses CASCADE");
        jdbc.update("DELETE FROM users WHERE id > 100");
    }

    // S-E-6: confirm 정상
    @Test
    void confirm은_PENDING_상태에서_CONFIRMED로_전이된다() {
        Course course = openCourse(2, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        Long enrollmentId = applied.enrollment().id();

        EnrollmentSummary confirmed = enrollmentService.confirm(student10, enrollmentId);

        assertThat(confirmed.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
        Enrollment refreshed = enrollmentRepository.findById(enrollmentId).orElseThrow();
        assertThat(refreshed.getConfirmedAt()).isNotNull();
        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isEqualTo(1); // confirm은 count 불변
    }

    // S-E-7: PENDING cancel, 대기자 없음
    @Test
    void cancel은_PENDING_상태에서_대기자가_없으면_occupiedCount만_감소한다() {
        Course course = openCourse(2, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());

        EnrollmentCancelResponse res = enrollmentService.cancel(student10, applied.enrollment().id());

        assertThat(res.enrollment().status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(res.promoted()).isNull();
        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isZero();
    }

    // S-E-8: PENDING cancel + 대기자 있음 OPEN → 자동 승격
    @Test
    void cancel은_OPEN_강의에서_대기자가_있으면_자동_승격한다() {
        Course course = openCourse(1, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId()); // ENROLLED
        EnrollmentApplyResponse waitlisted = enrollmentService.apply(student11, course.getId()); // WAITLISTED

        EnrollmentCancelResponse res = enrollmentService.cancel(student10, applied.enrollment().id());

        assertThat(res.enrollment().status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(res.promoted()).isNotNull();
        assertThat(res.promoted().fromWaitlistId()).isEqualTo(waitlisted.waitlist().id());
        assertThat(res.promoted().userId()).isEqualTo(11L);

        Enrollment promoted = enrollmentRepository.findById(res.promoted().enrollmentId()).orElseThrow();
        assertThat(promoted.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        assertThat(promoted.getPromotedFromWaitlistId()).isEqualTo(waitlisted.waitlist().id());

        Waitlist w = waitlistRepository.findById(waitlisted.waitlist().id()).orElseThrow();
        assertThat(w.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);

        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isEqualTo(1); // 순변동 0
    }

    // S-E-9: CONFIRMED 기간 내 cancel + 대기자 있음
    @Test
    void cancel은_CONFIRMED_기간_내_상태에서_대기자가_있으면_자동_승격한다() {
        Course course = openCourse(1, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        enrollmentService.confirm(student10, applied.enrollment().id());
        enrollmentService.apply(student11, course.getId()); // WAITLISTED

        EnrollmentCancelResponse res = enrollmentService.cancel(student10, applied.enrollment().id());

        assertThat(res.enrollment().status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(res.promoted()).isNotNull();
        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isEqualTo(1);
    }

    // S-E-10: CONFIRMED 기간 내 cancel + 대기자 없음
    @Test
    void cancel은_CONFIRMED_기간_내_상태에서_대기자가_없으면_occupiedCount만_감소한다() {
        Course course = openCourse(2, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        enrollmentService.confirm(student10, applied.enrollment().id());

        EnrollmentCancelResponse res = enrollmentService.cancel(student10, applied.enrollment().id());

        assertThat(res.enrollment().status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(res.promoted()).isNull();
        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isZero();
    }

    // S-E-11: CONFIRMED 기간 초과 → CANCEL_DEADLINE_EXCEEDED + 상태/count 불변
    @Test
    void cancel은_CONFIRMED_기간_초과면_CANCEL_DEADLINE_EXCEEDED_상태_불변() {
        // cancellationDeadlineDays=0 → 즉시 만료
        Course course = openCourse(2, 0);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        enrollmentService.confirm(student10, applied.enrollment().id());

        DomainException e = catchThrowableOfType(
            () -> enrollmentService.cancel(student10, applied.enrollment().id()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.CANCEL_DEADLINE_EXCEEDED);

        Enrollment refreshed = enrollmentRepository.findById(applied.enrollment().id()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isEqualTo(1);
    }

    // S-E-12: CLOSED 강의에서 cancel → 자동 승격 발동 안 됨
    @Test
    void cancel은_CLOSED_강의에서_occupiedCount는_감소하되_자동_승격은_없다() {
        Course course = openCourse(1, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        enrollmentService.apply(student11, course.getId()); // WAITLISTED
        courseService.close(creator, course.getId()); // CLOSED

        EnrollmentCancelResponse res = enrollmentService.cancel(student10, applied.enrollment().id());

        assertThat(res.enrollment().status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(res.promoted()).isNull();
        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isZero();
    }

    // S-E-13: CLOSED 강의에서 confirm → 정상 수행
    @Test
    void confirm은_CLOSED_강의에서도_PENDING이면_정상_수행한다() {
        Course course = openCourse(2, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        courseService.close(creator, course.getId());

        EnrollmentSummary confirmed = enrollmentService.confirm(student10, applied.enrollment().id());

        assertThat(confirmed.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    // S-P-1: 모든 WAITING이 CANCELLED → 승격 없음
    @Test
    void cancel은_모든_대기자가_취소된_상태면_승격_없이_종료한다() {
        Course course = openCourse(1, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        EnrollmentApplyResponse waitlist11 = enrollmentService.apply(student11, course.getId());

        // student11이 대기 취소 (WaitlistService는 다음 커밋이라 SQL 직접 수행)
        cancelWaitlistDirect(waitlist11.waitlist().id());

        EnrollmentCancelResponse res = enrollmentService.cancel(student10, applied.enrollment().id());

        assertThat(res.enrollment().status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(res.promoted()).isNull();
        Course courseAfter = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(courseAfter.getOccupiedCount()).isZero();
    }

    // S-P-2: 첫 대기자가 CANCELLED → 다음 후보로 승격
    @Test
    void cancel은_첫_대기자가_취소되었으면_다음_후보가_승격된다() {
        Course course = openCourse(1, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        EnrollmentApplyResponse w11 = enrollmentService.apply(student11, course.getId()); // 1번 대기
        EnrollmentApplyResponse w12 = enrollmentService.apply(student12, course.getId()); // 2번 대기

        // student11(첫 대기자) 취소
        cancelWaitlistDirect(w11.waitlist().id());

        EnrollmentCancelResponse res = enrollmentService.cancel(student10, applied.enrollment().id());

        assertThat(res.promoted()).isNotNull();
        assertThat(res.promoted().userId()).isEqualTo(12L);
        assertThat(res.promoted().fromWaitlistId()).isEqualTo(w12.waitlist().id());
    }

    // 보강: 이미 CANCELLED인 enrollment에 cancel → ALREADY_CANCELLED
    @Test
    void cancel을_이미_CANCELLED인_enrollment에_시도하면_ALREADY_CANCELLED() {
        Course course = openCourse(2, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        enrollmentService.cancel(student10, applied.enrollment().id());

        DomainException e = catchThrowableOfType(
            () -> enrollmentService.cancel(student10, applied.enrollment().id()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.ALREADY_CANCELLED);
    }

    // 보강: confirm 중복 → INVALID_STATUS
    @Test
    void confirm을_이미_CONFIRMED인_enrollment에_시도하면_INVALID_STATUS() {
        Course course = openCourse(2, 7);
        EnrollmentApplyResponse applied = enrollmentService.apply(student10, course.getId());
        enrollmentService.confirm(student10, applied.enrollment().id());

        DomainException e = catchThrowableOfType(
            () -> enrollmentService.confirm(student10, applied.enrollment().id()),
            DomainException.class
        );

        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATUS);
    }

    // ----------------------------------------
    // Helpers
    // ----------------------------------------

    /**
     * 테스트 setup용 waitlist 직접 취소.
     * 정상 흐름에서는 WaitlistService를 사용하지만 본 테스트 시점에는 아직 구현 전이라 SQL 직접 수행.
     */
    private void cancelWaitlistDirect(Long waitlistId) {
        jdbc.update(
            "UPDATE waitlists SET status = 'CANCELLED', cancelled_at = now(), updated_at = now() WHERE id = ?",
            waitlistId);
    }

    private Course openCourse(int capacity, int cancellationDeadlineDays) {
        Course course = courseService.register(creator, new CourseCreateRequest(
            "Java 마스터 클래스",
            "Spring Boot 실전",
            150_000L,
            capacity,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            cancellationDeadlineDays
        ));
        return courseService.open(creator, course.getId());
    }
}
