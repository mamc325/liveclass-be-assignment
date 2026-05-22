package com.example.enrollment_system.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("라이브클래스 BE-A 수강 신청 시스템 API")
                .version("v1")
                .description("""
                    수강 신청 / 결제 확정 / 대기열 자동 승격 API.

                    모든 요청은 `X-USER-ID` 헤더로 사용자를 식별합니다.

                    Seed 사용자:
                    - 1, 2 → CREATOR
                    - 10~19 → STUDENT
                    """))
            .servers(List.of(new Server().url("http://localhost:8080").description("local")));
    }
}
