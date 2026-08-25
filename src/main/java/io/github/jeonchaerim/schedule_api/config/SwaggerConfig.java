package io.github.jeonchaerim.schedule_api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("schedule-api")
                        .description("JPA 기반 일정 관리 API — N+1 해결 및 Redis 캐시 적용")
                        .version("v1.0"));
    }
}