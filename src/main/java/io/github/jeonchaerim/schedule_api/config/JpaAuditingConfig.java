package io.github.jeonchaerim.schedule_api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing	// 이걸 안붙이면 리스너가 등록이 안되서 createdDt가 null로 들어감
// 메인 애플리케이션 클래스가 아닌 여기서 활성화 (슬라이스 테스트 격리)
public class JpaAuditingConfig {
}
