package io.github.jeonchaerim.schedule_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @EnableCaching은 CacheConfig로, @EnableJpaAuditing은 JpaAuditingConfig로 이동
// (메인 클래스에 직접 붙이면 @WebMvcTest/@DataJpaTest가 이 클래스를 그대로 재사용하면서
//  컴포넌트 스캔은 걷어내도 이 어노테이션들은 그대로 적용되어, 슬라이스 테스트가 깨짐)
@SpringBootApplication
public class ScheduleApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScheduleApiApplication.class, args);
	}

}
