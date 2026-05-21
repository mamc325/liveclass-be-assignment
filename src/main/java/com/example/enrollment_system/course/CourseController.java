package com.example.enrollment_system.course;

import com.example.enrollment_system.common.auth.AuthUser;
import com.example.enrollment_system.common.auth.CurrentUser;
import com.example.enrollment_system.course.dto.CourseCreateRequest;
import com.example.enrollment_system.course.dto.CourseResponse;
import com.example.enrollment_system.course.dto.CourseStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
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
}
