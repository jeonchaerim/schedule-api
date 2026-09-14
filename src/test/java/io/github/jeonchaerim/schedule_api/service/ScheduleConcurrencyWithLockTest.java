package io.github.jeonchaerim.schedule_api.service;

import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.dto.ScheduleCreateRequest;
import io.github.jeonchaerim.schedule_api.exception.LockAcquisitionException;
import io.github.jeonchaerim.schedule_api.exception.ScheduleConflictException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// "락 적용 후" 검증용 — 실제 ScheduleService.create()를 여러 스레드에서 동시에
// 호출해도, 같은 회원의 겹치는 시간대 일정은 딱 하나만 저장되는지 확인한다.
//
// 실제 Redis(RedissonClient)가 떠있어야 함 — docker-compose up 또는 redis-server로
// 로컬 Redis를 켠 상태에서 ./gradlew concurrencyTest로 실행 (기본 test에서는 제외됨)
@Tag("concurrency")
@SpringBootTest
class ScheduleConcurrencyWithLockTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private ScheduleService scheduleService;
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
                .email("concurrency-with-lock@test.com")
                .name("동시성테스트(락적용)")
                .build());
        startAt = LocalDateTime.of(2026, 9, 12, 14, 0);
        endAt = LocalDateTime.of(2026, 9, 12, 15, 0);
    }

    @Test
    @DisplayName("분산락 적용 후엔, 겹치는 시간대 요청이 동시에 와도 딱 하나만 저장된다")
    void withLock_onlyOneScheduleIsSaved() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger blockedCount = new AtomicInteger();

        for (int i = 0; i < THREAD_COUNT; i++) {
            int idx = i;
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ScheduleCreateRequest request = new ScheduleCreateRequest(
                            "동시 등록 " + idx, "동시성 재현(락 적용)",
                            startAt, endAt, member.getId(), null);
                    scheduleService.create(request);
                    successCount.incrementAndGet();
                } catch (ScheduleConflictException | LockAcquisitionException e) {
                    blockedCount.incrementAndGet();   // 겹침 판정 또는 락 대기 초과 — 둘 다 "막혔다"는 뜻
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        long savedCount = scheduleRepository.findAll().stream()
                .filter(s -> s.getMember().getId().equals(member.getId()))
                .count();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(blockedCount.get()).isEqualTo(THREAD_COUNT - 1);
        assertThat(savedCount).isEqualTo(1);   // 락 덕분에 하나만 저장됨 — 재현되던 문제가 해결됐음을 확인
    }
}
