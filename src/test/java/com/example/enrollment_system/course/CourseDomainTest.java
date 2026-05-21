package com.example.enrollment_system.course;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseDomainTest {

    // C-U-1: DRAFT 상태에서 open() 호출 → OPEN 전이
    @Test
    void open은_DRAFT_상태에서만_OPEN으로_전이된다() {
        Course course = newCourse();

        course.open();

        assertThat(course.getStatus()).isEqualTo(CourseStatus.OPEN);
    }

    // C-U-2: OPEN/CLOSED 상태에서 open() 호출 → IllegalStateException
    @Test
    void open은_OPEN_상태에서_호출하면_IllegalStateException() {
        Course course = newCourse();
        course.open(); // DRAFT -> OPEN

        assertThatThrownBy(course::open)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void open은_CLOSED_상태에서_호출하면_IllegalStateException() {
        Course course = newCourse();
        course.open();
        course.close(); // OPEN -> CLOSED

        assertThatThrownBy(course::open)
            .isInstanceOf(IllegalStateException.class);
    }

    // C-U-3: OPEN 상태에서 close() 호출 → CLOSED 전이
    @Test
    void close는_OPEN_상태에서만_CLOSED로_전이된다() {
        Course course = newCourse();
        course.open();

        course.close();

        assertThat(course.getStatus()).isEqualTo(CourseStatus.CLOSED);
    }

    // C-U-4: DRAFT/CLOSED 상태에서 close() 호출 → IllegalStateException
    @Test
    void close는_DRAFT_상태에서_호출하면_IllegalStateException() {
        Course course = newCourse();

        assertThatThrownBy(course::close)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void close는_CLOSED_상태에서_호출하면_IllegalStateException() {
        Course course = newCourse();
        course.open();
        course.close();

        assertThatThrownBy(course::close)
            .isInstanceOf(IllegalStateException.class);
    }

    // C-U-5: incrementOccupiedCount() → occupiedCount += 1
    @Test
    void incrementOccupiedCount는_occupiedCount를_1_증가시킨다() {
        Course course = newCourse(); // capacity=10, occupiedCount=0

        course.incrementOccupiedCount();

        assertThat(course.getOccupiedCount()).isEqualTo(1);
    }

    // C-U-6: occupiedCount == 0에서 decrement → IllegalStateException
    @Test
    void decrementOccupiedCount는_occupiedCount가_0이면_IllegalStateException() {
        Course course = newCourse(); // occupiedCount=0

        assertThatThrownBy(course::decrementOccupiedCount)
            .isInstanceOf(IllegalStateException.class);
    }

    // C-U-7: occupiedCount == capacity에서 increment → IllegalStateException
    @Test
    void incrementOccupiedCount는_정원에_도달하면_IllegalStateException() {
        Course course = newCourse(); // capacity=10
        for (int i = 0; i < 10; i++) {
            course.incrementOccupiedCount();
        }

        assertThat(course.getOccupiedCount()).isEqualTo(10);
        assertThatThrownBy(course::incrementOccupiedCount)
            .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------
    // 헬퍼
    // ---------------------------------------------------------------

    private Course newCourse() {
        return new Course(
            1L,                                      // creatorId
            "Java 마스터 클래스",                      // title
            "Spring Boot 실전",                       // description
            150_000L,                                // price
            10,                                      // capacity
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 8, 31),
            7                                        // cancellationDeadlineDays
        );
    }
}
