package com.example.enrollment_system.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.OffsetDateTime;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    /**
     * Spring Data Auditing의 기본 DateTimeProvider는 LocalDateTime을 반환하는데,
     * 본 시스템의 BaseTimeEntity는 OffsetDateTime을 사용하므로 호환되지 않는다.
     * OffsetDateTime을 반환하는 커스텀 DateTimeProvider를 등록한다.
     *
     * 추후 ClockConfig 도입 시 Clock을 주입해 testability를 강화한다 (Phase 3).
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now());
    }
}
