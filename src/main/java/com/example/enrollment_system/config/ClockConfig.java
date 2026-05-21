package com.example.enrollment_system.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    /**
     * 시스템 시계 기반 Asia/Seoul Clock.
     * 모든 서비스 레이어 시간 생성(OffsetDateTime.now(clock))이 이 Bean을 사용한다.
     *
     * 테스트에서는 @TestConfiguration으로 Clock.fixed(...) 또는 MutableClock을 주입해
     * 시각을 mock 가능. (docs/TEST_SCENARIOS.md 12)
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
