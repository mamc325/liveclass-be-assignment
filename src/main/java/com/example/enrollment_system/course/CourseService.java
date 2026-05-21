package com.example.enrollment_system.course;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class CourseService {

    private static final int DEFAULT_CANCELLATION_DEADLINE_DAYS = 7;

    private final CourseRepository courseRepository;

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
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
}
