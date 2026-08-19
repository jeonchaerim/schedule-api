package io.github.jeonchaerim.schedule_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing	// 이걸 안붙이면 리스너가 등록이 안되서 createdDt가 null로 들어감
// Auditing기능 자체를 켜는 것
public class ScheduleApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScheduleApiApplication.class, args);
	}

}
