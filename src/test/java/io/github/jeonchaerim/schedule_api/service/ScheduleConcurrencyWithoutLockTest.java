package io.github.jeonchaerim.schedule_api.service;

import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import io.github.jeonchaerim.schedule_api.repository.MemberRepository;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// "락 적용 전" 재현용 — Redisson 도입 전 create()가 그랬듯, 겹침 검사·락 없이
// Repository.save()만 여러 스레드에서 동시에 호출했을 때 겹치는 시간대의 일정이
// 실제로 전부 중복 저장되는지 확인한다.
//
// 수동 실행: ./gradlew concurrencyTest (기본 ./gradlew test에서는 제외됨)
@Tag("concurrency")
@SpringBootTest
class ScheduleConcurrencyWithoutLockTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;

    private Member member;
    private LocalDateTime startAt;
    private LocalDateTime endAt;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(Member.builder()
                .email("concurrency-without-lock@test.com")
                .name("동시성테스트(락없음)")
                .build());
        startAt = LocalDateTime.of(2026, 9, 12, 10, 0);
        endAt = LocalDateTime.of(2026, 9, 12, 11, 0);
    }

    @Test
    @DisplayName("락 없이 동시에 저장하면, 겹치는 시간대의 일정이 그대로 중복 저장된다")
    void withoutLock_duplicateSchedulesAreSaved() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int idx = i;
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();   // 스레드 전부가 동시에 출발하도록 대기
                    Schedule schedule = Schedule.builder()
                            .title("동시 등록 " + idx)
                            .content("동시성 재현(락 없음)")
                            .startAt(startAt)
                            .endAt(endAt)
                            .member(member)
                            .build();
                    // 겹침 검사도, 락도 없이 그냥 저장 — 락 적용 전 create()와 동일한 무방비 상태
                    scheduleRepository.save(schedule);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        long savedCount = scheduleRepository.findAll().stream()
                .filter(s -> s.getMember().getId().equals(member.getId()))
                .count();

        // 막는 게 없으니 THREAD_COUNT개 요청이 전부 성공해서 그대로 다 저장됨 — 재현하려던 문제
        assertThat(savedCount).isEqualTo(THREAD_COUNT);
    }
}
