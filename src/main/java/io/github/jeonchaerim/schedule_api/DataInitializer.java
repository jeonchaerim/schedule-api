package io.github.jeonchaerim.schedule_api;

import io.github.jeonchaerim.schedule_api.domain.Category;
import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import io.github.jeonchaerim.schedule_api.repository.CategoryRepository;
import io.github.jeonchaerim.schedule_api.repository.MemberRepository;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

	private final MemberRepository memberRepository;
	private final CategoryRepository categoryRepository;
	private final ScheduleRepository scheduleRepository;

	@Override
	public void run(String... args) {
		Category work = categoryRepository.save(
				Category.builder().name("업무").color("#FF5733").build());
		Category study = categoryRepository.save(
				Category.builder().name("공부").color("#33FF57").build());

		for (int i = 1; i <= 1000; i++) {
			Member m = memberRepository.save(
					Member.builder()
							.email("user" + i + "@test.com")
							.name("회원" + i)
							.build());

			for (int j = 1; j <= 2; j++) {
				scheduleRepository.save(Schedule.builder()
						.title("일정 " + i + "-" + j)
						.content("내용")
						.startAt(LocalDateTime.now())
						.endAt(LocalDateTime.now().plusHours(1))
						.member(m)
						.category(j == 1 ? work : study)
						.build());
			}
		}
	}
}