package com.example.enrollment_system.course;

import com.example.enrollment_system.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "courses")
public class Course extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    private Integer capacity;

    @Column(name = "occupied_count", nullable = false)
    private Integer occupiedCount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseStatus status;

    @Column(name = "cancellation_deadline_days", nullable = false)
    private Integer cancellationDeadlineDays;

    protected Course() {
        // JPA
    }

    public Course(Long creatorId,
                  String title,
                  String description,
                  Long price,
                  Integer capacity,
                  LocalDate startDate,
                  LocalDate endDate,
                  Integer cancellationDeadlineDays) {
        this.creatorId = creatorId;
        this.title = title;
        this.description = description;
        this.price = price;
        this.capacity = capacity;
        this.occupiedCount = 0;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = CourseStatus.DRAFT;
        this.cancellationDeadlineDays = cancellationDeadlineDays;
    }

    // ---------------------------------------------------------------
    // 상태 전이
    // ---------------------------------------------------------------

    public void open() {
        if (status != CourseStatus.DRAFT) {
            throw new IllegalStateException(
                "강의를 OPEN으로 전이할 수 없습니다. 현재 상태=" + status);
        }
        this.status = CourseStatus.OPEN;
    }

    public void close() {
        if (status != CourseStatus.OPEN) {
            throw new IllegalStateException(
                "강의를 CLOSED로 전이할 수 없습니다. 현재 상태=" + status);
        }
        this.status = CourseStatus.CLOSED;
    }

    // ---------------------------------------------------------------
    // 정원 카운터
    // ---------------------------------------------------------------

    public void incrementOccupiedCount() {
        if (occupiedCount >= capacity) {
            throw new IllegalStateException(
                "정원을 초과할 수 없습니다. capacity=" + capacity + ", occupiedCount=" + occupiedCount);
        }
        this.occupiedCount = occupiedCount + 1;
    }

    public void decrementOccupiedCount() {
        if (occupiedCount <= 0) {
            throw new IllegalStateException(
                "점유 인원이 0 이하로 떨어질 수 없습니다. occupiedCount=" + occupiedCount);
        }
        this.occupiedCount = occupiedCount - 1;
    }

    // ---------------------------------------------------------------
    // 조회 편의
    // ---------------------------------------------------------------

    public boolean isOwnedBy(Long userId) {
        return creatorId.equals(userId);
    }

    public boolean isOpen() {
        return status == CourseStatus.OPEN;
    }

    public boolean hasCapacity() {
        return occupiedCount < capacity;
    }

    public int getRemainingCount() {
        return capacity - occupiedCount;
    }

    // ---------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------

    public Long getId() { return id; }
    public Long getCreatorId() { return creatorId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Long getPrice() { return price; }
    public Integer getCapacity() { return capacity; }
    public Integer getOccupiedCount() { return occupiedCount; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public CourseStatus getStatus() { return status; }
    public Integer getCancellationDeadlineDays() { return cancellationDeadlineDays; }
}
